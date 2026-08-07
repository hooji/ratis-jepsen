;; Copyright 2026 the ratis-jepsen authors.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ratis-jepsen.checker
  "The liveness checker (PLAN Q11, M1): a cluster that stops serving
  while healthy is invisible to linearizability checking — RATIS-2523 /
  RATIS-2500-class stuck states grade as merely slow. This checker flags
  a run :valid? false when clients kept asking a fault-free cluster for
  work and got zero acknowledgements for a whole window.

  Nemesis-aware gating comes from the history's nemesis events, never
  wall-clock guesswork:

  - A fault is active from its fault op's INVOCATION (the fault begins
    somewhere between invocation and completion; earlier is the
    conservative gate) until its heal op's COMPLETION (the heal is only
    trusted once done), plus a grace period G for recovery/elections.
    Fault/heal op pairs come from ratis-jepsen.nemesis/fault->heal.
  - The remaining, fault-free stretches of the run are *calm regions*.
  - Within a calm region, a violation is a stretch of >= T seconds with
    zero client :ok completions in which invocations were being attempted
    *throughout* — attempts no more than `max-attempt-gap-s` apart. The
    throughout condition is what separates a stalled cluster (workers
    keep invoking; every op times out or fails) from an idle generator
    (clients simply stopped asking — e.g. the register workload's op
    budget exhausting minutes before --time-limit), which must not flag.

  Composed into the register workload's checker stack as :liveness; runs
  with --nemesis none get one calm region spanning the whole history.

  Also here (Job 07): the install-snapshot evidence checker — see its
  section below."
  (:require [clojure.string :as str]
            [jepsen.checker :as checker]
            [jepsen.store :as store]
            [ratis-jepsen.nemesis :as nemesis]))

(def default-opts
  "T (:window-s): the no-progress window that convicts — 60 s of majority-
  healthy silence (PLAN Q11's generous T). G (:grace-s): recovery slack
  after each heal completes, 15 s (three election-timeout maxima).
  :max-attempt-gap-s: the longest silence between invocation attempts
  that still counts as continuously attempting — twice the harness-side
  5 s invocation timeout, so workers cycling through timed-out ops chain."
  {:window-s          60
   :grace-s           15
   :max-attempt-gap-s 10
   :fault->heal       nemesis/fault->heal})

(defn- s->ns [s] (long (* s 1e9)))
(defn- ns->s [t] (/ (Math/round (/ t 1e6)) 1000.0))

(defn client-op?
  "Client ops carry integer :process ids; the nemesis (and other
  internal processes) use keywords."
  [op]
  (integer? (:process op)))

;; ---------------------------------------------------------------------------
;; Fault windows from nemesis events
;; ---------------------------------------------------------------------------

(defn unhealthy-intervals
  "Walks nemesis ops (history order) and returns [start end] nanosecond
  intervals during which the liveness check is gated off; `end` is nil
  for a fault never healed by history end.

  Pairing invocations with completions needs no op metadata beyond :f:
  the nemesis is a single sequential process, so occurrences of a given
  :f strictly alternate invocation/completion — odd occurrences are
  invocations, even ones completions. A fault op's invocation opens its
  kind's window; the matching heal op's completion closes it at
  completion time + grace. Heals with nothing open (e.g. defensive
  resumes) close nothing; a heal whose completion never arrives leaves
  the window open — conservative in every direction."
  [nemesis-ops fault->heal grace-ns]
  (let [heal->fault (into {} (map (fn [[f h]] [h f])) fault->heal)]
    (loop [ops       (seq nemesis-ops)
           seen      {}   ; f -> occurrences so far
           open      {}   ; fault-f -> window start time
           intervals []]
      (if ops
        (let [op          (first ops)
              f           (:f op)
              n           (inc (get seen f 0))
              invocation? (odd? n)
              seen        (assoc seen f n)]
          (cond
            ;; A fault op's invocation opens its window (keep the earliest
            ;; start if somehow already open).
            (and (contains? fault->heal f) invocation?)
            (recur (next ops) seen
                   (if (contains? open f) open (assoc open f (:time op)))
                   intervals)

            ;; A heal op's completion closes its fault's window, + grace.
            (and (contains? heal->fault f) (not invocation?))
            (let [fault-f (heal->fault f)]
              (if-let [start (get open fault-f)]
                (recur (next ops) seen (dissoc open fault-f)
                       (conj intervals [start (+ (:time op) grace-ns)]))
                (recur (next ops) seen open intervals)))

            :else
            (recur (next ops) seen open intervals)))
        ;; Anything still open never healed: gate through history end.
        (into intervals (map (fn [[_f start]] [start nil])) open)))))

(defn merge-intervals
  "Sorts [start end] intervals (nil end -> `end-time`) and merges
  overlaps into a minimal disjoint ascending set."
  [intervals end-time]
  (->> intervals
       (map (fn [[s e]] [s (max s (or e end-time))]))
       (sort-by first)
       (reduce (fn [acc [s e]]
                 (let [[ps pe] (peek acc)]
                   (if (and pe (<= s pe))
                     (conj (pop acc) [ps (max pe e)])
                     (conj acc [s e]))))
               [])))

(defn calm-regions
  "The complement of the merged unhealthy intervals within [t0 t1]:
  the stretches where a healthy majority is expected."
  [merged t0 t1]
  (let [edges  (concat [[nil t0]] merged [[t1 nil]])
        pairs  (partition 2 1 edges)]
    (->> pairs
         (keep (fn [[[_ prev-end] [next-start _]]]
                 (let [a prev-end, b next-start]
                   (when (< a b) [a b]))))
         vec)))

;; ---------------------------------------------------------------------------
;; Stall detection inside calm regions
;; ---------------------------------------------------------------------------

(defn- chains
  "Splits ascending times into runs whose successive gaps are <= max-gap."
  [ts max-gap]
  (reduce (fn [acc t]
            (let [cur (peek acc)]
              (if (and cur (<= (- t (peek cur)) max-gap))
                (conj (pop acc) (conj cur t))
                (conj acc [t]))))
          []
          ts))

(defn stalls-in-region
  "Violations inside one calm region [a b]: for each gap between
  successive progress points (region edges and :ok completions) of at
  least `window-ns`, any chain of invocation attempts spaced <=
  max-gap-ns whose span — extended max-gap-ns past its last attempt, to
  cover that attempt's own pending time, but never past the gap —
  reaches window-ns is a stall."
  [[a b] ok-times invoke-times window-ns max-gap-ns]
  (let [oks    (filterv #(and (<= a %) (<= % b)) ok-times)
        points (concat [a] oks [b])]
    (->> (partition 2 1 points)
         (mapcat
           (fn [[p q]]
             (when (>= (- q p) window-ns)
               (let [invs (filterv #(and (< p %) (< % q)) invoke-times)]
                 (keep (fn [chain]
                         (let [from (first chain)
                               to   (min (+ (peek chain) max-gap-ns) q)]
                           (when (>= (- to from) window-ns)
                             {:stall-start-s (ns->s from)
                              :stall-end-s   (ns->s to)
                              :duration-s    (ns->s (- to from))
                              :attempts      (count chain)})))
                       (chains invs max-gap-ns))))))
         vec)))

;; ---------------------------------------------------------------------------
;; The checker
;; ---------------------------------------------------------------------------

(defn check-liveness
  "Pure core of the liveness checker: a history (any seqable of op maps
  in history order) and options in, verdict map out."
  [history opts]
  (let [{:keys [window-s grace-s max-attempt-gap-s fault->heal]} opts
        ops (vec history)]
    (if (empty? ops)
      {:valid? true
       :window-s window-s :grace-s grace-s :max-attempt-gap-s max-attempt-gap-s
       :calm-regions-s [] :violations []}
      (let [window       (s->ns window-s)
            grace        (s->ns grace-s)
            max-gap      (s->ns max-attempt-gap-s)
            relevant-fs  (into (set (keys fault->heal)) (vals fault->heal))
            nemesis-ops  (filterv #(and (not (client-op? %))
                                        (contains? relevant-fs (:f %)))
                                  ops)
            client-ops   (filterv client-op? ops)
            t0           (transduce (map :time) min Long/MAX_VALUE ops)
            t1           (transduce (map :time) max Long/MIN_VALUE ops)
            ok-times     (->> client-ops
                              (filterv #(= :ok (:type %)))
                              (mapv :time)
                              sort vec)
            invoke-times (->> client-ops
                              (filterv #(= :invoke (:type %)))
                              (mapv :time)
                              sort vec)
            unhealthy    (unhealthy-intervals nemesis-ops fault->heal grace)
            merged       (merge-intervals unhealthy t1)
            calm         (calm-regions merged t0 t1)
            violations   (into []
                               (mapcat #(stalls-in-region % ok-times invoke-times
                                                          window max-gap))
                               calm)]
        {:valid?            (empty? violations)
         :window-s          window-s
         :grace-s           grace-s
         :max-attempt-gap-s max-attempt-gap-s
         :calm-regions-s    (mapv (fn [[a b]] [(ns->s a) (ns->s b)]) calm)
         :violations        violations}))))

(defn liveness
  "The liveness checker, composed into the register workload's stack.
  Options (all optional) override default-opts; see the ns docstring for
  semantics."
  ([] (liveness {}))
  ([opts]
   (let [opts (merge default-opts opts)]
     (reify checker/Checker
       (check [_this _test history _copts]
         (check-liveness history opts))))))

;; ---------------------------------------------------------------------------
;; Install-snapshot evidence (Job 07): a snapshot-churn run that never
;; exercised install-snapshot is a broken test, not a green one (the
;; Review 01 lesson). Judged from the nodes' collected logs — the copies
;; jepsen snarfs into store/ before db teardown, because teardown wipes
;; the on-node logs before analysis runs.
;; ---------------------------------------------------------------------------

(def install-snapshot-patterns
  "The ratis-3.2.2 log lines that prove install-snapshot happened, pinned
  by observation on live snapshot-churn runs (Job 07 shakedown store
  ratis-kv-register-snapshot-churn/20260805T171416.540Z; full lines in
  the report):

  :send — the leader's appender discovering the follower is behind its
  purged log start and streaming the snapshot (GrpcLogAppender, INFO):
    \"n5@group-…->n2-GrpcLogAppender: followerNextIndex = 1048 but
     logStartIndex = 1103, send snapshot SingleFileSnapshotInfo(t:4,
     i:1239):[…/sm/snapshot.4_1239] to follower\"

  :receive — the follower handling the installation request
  (SnapshotInstallationHandler, INFO):
    \"n2@group-…: receive installSnapshot: n5->n2#0-t4,chunk:…\"

  (The notifyInstallSnapshot phrasing — \"notify follower to install
  snapshot\" — never occurs here: that is the external-state-machine
  path; our SUT streams the snapshot file directly.) Both patterns are
  re-find'd per log line; either side alone proves the path ran, both
  are counted for the report."
  {:send    #"but logStartIndex = \d+, send snapshot"
   :receive #"receive installSnapshot"})

(defn count-install-evidence
  "Pure: {node log-content} in, {:total n, :counts {node {:send s
  :receive r}}} out — occurrences counted per line so multi-event logs
  count each event."
  [node->content patterns]
  (let [counts (into {}
                     (map (fn [[node content]]
                            (let [lines (str/split-lines (or content ""))]
                              [node
                               (into {}
                                     (map (fn [[k pat]]
                                            [k (count
                                                 (filter #(re-find pat %)
                                                         lines))]))
                                     patterns)])))
                     node->content)]
    {:total  (reduce + 0 (mapcat vals (vals counts)))
     :counts counts}))

(defn evidence-verdict
  "Pure: the evidence decision. `required?` is whether this run OWES
  install-snapshot evidence: only a dedicated snapshot-churn run does
  (its sustained write stream is sized to cross the server's
  purge.gap=1024 milestones). mixed-all composes churn segments for
  fault diversity, but its churn share sits below the purge gap by
  design, so it reports counts without requiring them; runs without
  churn ops likewise."
  [required? {:keys [total counts]}]
  (cond
    (not required?)
    {:valid? true
     :note "no snapshot-churn ops in history — evidence not required"
     :total total
     :counts counts}

    (pos? total)
    {:valid? true, :total total, :counts counts}

    :else
    {:valid? false
     :error  :no-install-snapshot-evidence
     :note   (str "snapshot-churn ran but no install-snapshot log line "
                  "matched on any node — the run never exercised the "
                  "path it exists to test")
     :total  0
     :counts counts}))

(defn churn-ops?
  "Does this history contain snapshot-churn nemesis activity?"
  [history]
  (boolean (some #(and (not (client-op? %)) (= :churn-kill (:f %)))
                 history)))

(defn- node-log-content
  "The snarfed store copy of a node's SUT log, or nil when absent (e.g.
  a node that died before producing one)."
  [test node]
  (let [f (store/path test (name node) "ratis-kv.log")]
    (when (.exists ^java.io.File f)
      (slurp f))))

(defn install-snapshot-evidence
  "The evidence checker. Composed unconditionally; counts always
  reported. Evidence is REQUIRED (zero ⇒ :valid? false) only when
  :require-evidence? is set (the workload sets it for --nemesis
  snapshot-churn) AND churn ops actually appear in the history."
  ([] (install-snapshot-evidence {}))
  ([opts]
   (let [patterns (merge install-snapshot-patterns (:patterns opts))
         require? (boolean (:require-evidence? opts))]
     (reify checker/Checker
       (check [_this test history _copts]
         (evidence-verdict
           (and require? (churn-ops? history))
           (count-install-evidence
             (into {}
                   (map (fn [node] [node (node-log-content test node)]))
                   (:nodes test))
             patterns)))))))

;; ---------------------------------------------------------------------------
;; Membership evidence (Job 08): a membership run must PROVE committed
;; configuration changes happened — the evidence-assertion law from
;; Job 07, applied to the new nemesis. Judged from the snarfed store
;; logs, same as install-snapshot evidence.
;;
;; The discriminating line (ServerState.setRaftConf at ratis-3.2.2;
;; phrasing observed live in the SUT's JoinModeTest and the shakedowns):
;;
;;   n1@group-…: set configuration conf: {index: 3, cur=peers:[n1|…,
;;   n2|…]|listeners:[], old=peers:[n1|…]|listeners:[]}
;;
;; Only a REAL reconfiguration appends a transitional (old,new) conf
;; entry — old= is non-null. Every other conf line is stable (old=null):
;; the initial conf at index 0, the conf every NEW LEADER re-appends at
;; its startup index (LeaderStateImpl.StartupLogEntry — elections would
;; otherwise masquerade as conf changes), the stable entry that follows
;; a committed reconfiguration, and boot-time recovery replays. Distinct
;; transitional indexes across all node logs therefore count actual
;; reconfigurations, deduplicated across replicas and restarts.
;; ---------------------------------------------------------------------------

(def conf-transition-pattern
  "Matches a transitional (old,new) conf-adoption line and captures its
  log index."
  #"set configuration conf: \{index: (\d+), [^\n]*old=peers:")

(defn conf-transition-indexes
  "Pure: {node log-content} in, the sorted distinct log indexes of
  transitional conf entries observed anywhere, out."
  [node->content]
  (->> (vals node->content)
       (mapcat (fn [content]
                 (->> (str/split-lines (or content ""))
                      (keep #(second (re-find conf-transition-pattern %))))))
       (map #(Long/parseLong %))
       distinct
       sort
       vec))

(defn membership-verdict
  "Pure: the membership-evidence decision. `required?` = this run OWES
  conf-change evidence (a dedicated membership kind with membership ops
  actually in the history); min-changes = the floor on distinct
  committed reconfigurations."
  [required? min-changes indexes]
  (cond
    (not required?)
    {:valid? true
     :note "membership evidence not required for this run"
     :transitions (count indexes)
     :indexes indexes}

    (>= (count indexes) min-changes)
    {:valid? true, :transitions (count indexes), :indexes indexes}

    :else
    {:valid? false
     :error  :no-conf-change-evidence
     :note   (str "membership nemesis ran but only " (count indexes)
                  " committed configuration change(s) appear in the node "
                  "logs (transitional conf entries; " min-changes
                  " required) — the run never exercised the path it "
                  "exists to test")
     :transitions (count indexes)
     :indexes indexes}))

(defn membership-ops?
  "Does this history contain membership nemesis activity (moves or
  probe)?"
  [history]
  (boolean (some #(and (not (client-op? %))
                       (#{:member-add :member-remove :member-replace-dead
                          :listener-add} (:f %)))
                 history)))

(defn membership-evidence
  "The conf-change evidence checker. Composed unconditionally; counts
  always reported; REQUIRED only when :require-evidence? (the workload
  sets it for the dedicated membership kinds) and membership ops appear
  in the history."
  ([] (membership-evidence {}))
  ([opts]
   (let [require?    (boolean (:require-evidence? opts))
         min-changes (long (or (:min-changes opts) 2))]
     (reify checker/Checker
       (check [_this test history _copts]
         (membership-verdict
           (and require? (membership-ops? history))
           min-changes
           (conf-transition-indexes
             (into {}
                   (map (fn [node] [node (node-log-content test node)]))
                   (:nodes test)))))))))

;; ---------------------------------------------------------------------------
;; Joiner-install evidence (Job 08, the combined membership+snapshot-churn
;; requirement): a replace-dead/add composed with snapshot churn must
;; show install-snapshot activity ON A NODE THAT JOINED during the run —
;; the bootstrap-catch-up-via-install-snapshot path. Joined nodes come
;; from the history (successful add moves); the install evidence is the
;; follower-side receive pattern on those nodes' logs.
;; ---------------------------------------------------------------------------

(defn joined-nodes
  "Nodes the history shows being committed INTO the conf: successful
  :member-add ops and successful adds inside :member-replace-done."
  [history]
  (->> history
       (remove client-op?)
       (keep (fn [{:keys [f value]}]
               (when (map? value)
                 (case f
                   :member-add
                   (when (get-in value [:result :success?]) (:target value))
                   :member-replace-done
                   (when (get-in value [:add :result :success?])
                     (get-in value [:add :target]))
                   nil))))
       distinct
       vec))

(defn joiner-install-verdict
  "Pure: the joiner-install decision. Requires >= 1 install-snapshot
  receive line on at least one joined node when `required?`."
  [required? joined node->receives]
  (let [with-installs (->> joined
                           (filter #(pos? (get node->receives % 0)))
                           vec)]
    (cond
      (not required?)
      {:valid? true
       :note "joiner-install evidence not required for this run"
       :joined joined
       :joined-with-installs with-installs}

      (empty? joined)
      {:valid? false
       :error  :no-committed-join
       :note   (str "combined membership+snapshot-churn run committed no "
                    "add — no joining node exists to install a snapshot")
       :joined joined
       :joined-with-installs with-installs}

      (seq with-installs)
      {:valid? true
       :joined joined
       :joined-with-installs with-installs
       :receive-counts (select-keys node->receives joined)}

      :else
      {:valid? false
       :error  :no-joiner-install-evidence
       :note   (str "nodes " (pr-str joined) " joined during the run but "
                    "none of their logs shows an install-snapshot receive "
                    "— the bootstrap-catch-up-via-install-snapshot path "
                    "never ran")
       :joined joined
       :joined-with-installs with-installs})))

(defn joiner-install-evidence
  "The joining-node install-snapshot checker (REQUIRED only for the
  dedicated combined kind)."
  ([] (joiner-install-evidence {}))
  ([opts]
   (let [require? (boolean (:require-evidence? opts))]
     (reify checker/Checker
       (check [_this test history _copts]
         (let [receives (into {}
                              (map (fn [node]
                                     [(name node)
                                      (->> (str/split-lines
                                             (or (node-log-content test node) ""))
                                           (filter #(re-find (:receive install-snapshot-patterns) %))
                                           count)]))
                              (:nodes test))]
           (joiner-install-verdict
             (and require? (membership-ops? history) (churn-ops? history))
             (joined-nodes history)
             receives)))))))

;; ---------------------------------------------------------------------------
;; Counter checking (Job 09, M3): the exactly-once increment workload.
;;
;; Choice documented per the brief: NOT jepsen's stock counter checker —
;; an explicit per-key bounds checker instead, because (a) our outcome
;; map gives definite :fail semantics (a :fail add is PROVEN not applied,
;; so it tightens the upper bound; the stock checker cannot assume that),
;; (b) per-key independent histories keep the interval arithmetic exact
;; and cheap, and (c) the final-read phase makes the generic read-bounds
;; rule subsume final-value exactness: the last :ok read after the last
;; add completion has lower = Σ ok-deltas (loss convicts) and
;; upper = Σ ok+info+pending deltas (any double beyond the 0-or-1 :info
;; allowance convicts).
;;
;; Soundness of the bounds (linearizable reads; positive deltas):
;;   lower(read)  = Σ deltas of :ok adds whose COMPLETION precedes the
;;                  read's INVOCATION — an :ok add is applied by its
;;                  completion, and a read invoked later must observe it.
;;   upper(read)  = Σ deltas of non-:fail adds whose INVOCATION precedes
;;                  the read's COMPLETION — nothing invoked after the
;;                  read completed can be visible, a :fail add is
;;                  definitely-not-applied (outcome-map guarantee), and
;;                  every :ok/:info/pending add counts ONCE — exactly-once
;;                  for :ok, 0-or-1 for :info/pending.
;; A double-applied :ok add pushes a later read above upper; a lost :ok
;; add pulls the final read below lower.
;;
;; Also asserted: no two :ok adds report the same :observed total (ADD
;; replies VAL <total-after-apply>; with adds-only keys and positive
;; deltas totals strictly increase, so each apply's total is unique and a
;; deduplicated retry returns the CACHED original — two ops sharing a
;; total means the reply cache handed one op another op's reply, the
;; repliedIndex-linearizability signal RATIS-2542 names).
;; ---------------------------------------------------------------------------

(defn- pair-counter-ops
  "Folds one key's history (plain per-key values — independent tuples
  already unwrapped) into {:adds [...], :reads [...]}: adds carry
  {:delta :inv :comp :type :observed}, reads {:v :inv :comp :final?}
  (:ok reads only; :comp/:type nil for ops with no completion — a
  crashed worker's pending invocation)."
  [ops]
  (loop [ops   (seq ops)
         i     0
         open  {}   ; process -> {:f ... :inv i :value v :final? b}
         adds  []
         reads []]
    (if-not ops
      {:adds  (into adds (map (fn [[_ o]] (assoc o :comp nil :type nil))
                              (filter (fn [[_ o]] (= :add (:f o))) open)))
       :reads reads}
      (let [{:keys [type f process value] :as op} (first ops)]
        (cond
          (= :invoke type)
          (recur (next ops) (inc i)
                 (assoc open process {:f f :inv i :delta value
                                      :final? (boolean (:final? op))})
                 adds reads)

          ;; completion of whatever this process had open
          (contains? open process)
          (let [{:keys [f inv delta final?]} (get open process)
                open (dissoc open process)]
            (case f
              :add  (recur (next ops) (inc i) open
                           (conj adds {:delta delta :inv inv :comp i
                                       :type type :observed (:observed op)})
                           reads)
              :read (recur (next ops) (inc i) open adds
                           (if (= :ok type)
                             (conj reads {:v (:value op) :inv inv :comp i
                                          :final? final?})
                             reads))
              (recur (next ops) (inc i) open adds reads)))

          :else
          (recur (next ops) (inc i) open adds reads))))))

(defn check-counter-key
  "Pure: one key's ops in, verdict out (shape in the section comment)."
  [ops]
  (let [{:keys [adds reads]} (pair-counter-ops ops)
        ok-adds     (filterv #(= :ok (:type %)) adds)
        info-adds   (filterv #(= :info (:type %)) adds)
        fail-adds   (filterv #(= :fail (:type %)) adds)
        pending     (filterv #(nil? (:type %)) adds)
        maybe-adds  (into (into ok-adds info-adds) pending)
        bounds-violation
        ;; The shared interval rule: an observation `v` made by an op
        ;; invoked at `inv` and completed at `rc` must satisfy
        ;; lower <= v <= upper. Sound for reads AND for the totals :ok
        ;; adds report: the total is the state at the add's (first)
        ;; apply, which lies between its invocation and its completion —
        ;; for a deduplicated retry the reply is the cached ORIGINAL, so
        ;; a late completion only widens `upper`, never unsounds it.
        (fn [what v inv rc self-inv extra]
          ;; self-inv: for an add's own pre-state check, the add itself
          ;; must not appear in its upper bound (its delta is not part
          ;; of its pre-state); nil for reads.
          (let [v     (or v 0)   ; ABSENT = an untouched counter = 0
                lower (transduce (clojure.core/comp
                                   (filter #(and (< (:comp % Long/MAX_VALUE) inv)
                                                 (not= (:inv %) self-inv)))
                                   (map :delta))
                                 + 0 ok-adds)
                upper (transduce (clojure.core/comp
                                   (filter #(and (< (:inv %) rc)
                                                 (not= (:inv %) self-inv)))
                                   (map :delta))
                                 + 0 maybe-adds)]
            (when-not (<= lower v upper)
              (merge {:kind  (if (< v lower) :lost-update :double-count)
                      what   (assoc extra :value v)
                      :lower lower
                      :upper upper}))))
        read-violations
        ;; NB: the read's completion index must not be destructured as
        ;; `comp` — it would shadow clojure.core/comp above (found the
        ;; hard way).
        (into []
              (keep (fn [{:keys [v inv final?] :as r}]
                      (bounds-violation :read v inv (:comp r) nil
                                        {:final? final?})))
              reads)
        ;; Every :ok add's reported total is bounds-checked as an
        ;; observation too (Job 09 second pass): the Q14 forensics
        ;; needed exactly this — without final reads the read-only
        ;; bounds are slack, but the totals pin the state at every
        ;; single apply.
        observed-violations
        (into []
              (keep (fn [{:keys [observed delta inv] :as a}]
                      (when observed
                        ;; The pre-state this add's total reveals
                        ;; (observed − its own delta) is bounds-checked
                        ;; like a read, with the add itself excluded
                        ;; from its own bounds.
                        (bounds-violation :observed-total (- observed delta)
                                          inv (:comp a) inv
                                          {:total observed :delta delta}))))
              ok-adds)
        dup-observed
        (->> ok-adds
             (keep :observed)
             frequencies
             (filter (fn [[_ n]] (< 1 n)))
             (mapv (fn [[total n]]
                     {:kind :duplicate-observed-value
                      :observed total
                      :ok-adds-sharing-it n})))
        violations (-> read-violations
                       (into observed-violations)
                       (into dup-observed))
        final      (->> reads (filter :final?) last)]
    {:valid?     (empty? violations)
     :adds       {:ok (count ok-adds) :info (count info-adds)
                  :fail (count fail-adds) :pending (count pending)}
     :ok-sum     (transduce (map :delta) + 0 ok-adds)
     :info-sum   (transduce (map :delta) + 0 (into info-adds pending))
     :reads      (count reads)
     :final-read final
     :violations violations}))

(defn counter
  "The per-key counter checker (composed under jepsen.independent)."
  []
  (reify checker/Checker
    (check [_this _test history _copts]
      (check-counter-key history))))

;; ---------------------------------------------------------------------------
;; Retry evidence (Job 09): the dedup law. A dedicated dedup run must
;; prove client retries actually happened — the library's same-callId
;; retries are invisible unless counted, and a dedup test that never
;; retried tested nothing. The client records each invocation's failed
;; attempts (its retry activity) as :retries on the completion.
;; ---------------------------------------------------------------------------

(defn retry-totals
  "Pure: total retry activity in a history — {:total n, :by-f {...}}."
  [history]
  (let [with (->> history
                  (filter client-op?)
                  (filter :retries))]
    {:total (transduce (map :retries) + 0 with)
     :ops   (count with)
     :by-f  (reduce (fn [m op] (update m (:f op) (fnil + 0) (:retries op)))
                    {} with)}))

(defn retry-verdict
  "Pure: the retry-evidence decision."
  [required? {:keys [total] :as totals}]
  (cond
    (not required?)
    (assoc totals :valid? true
           :note "retry evidence not required for this run")

    (pos? total)
    (assoc totals :valid? true)

    :else
    (assoc totals :valid? false
           :error :no-retry-evidence
           :note (str "a dedup run owes proof that client retries happened, "
                      "but no invocation recorded any retry activity — the "
                      "retry cache was never exercised"))))

(defn retry-evidence
  "The retry-evidence checker; REQUIRED when :require-evidence? (the
  counter workload sets it for fault-bearing runs)."
  ([] (retry-evidence {}))
  ([opts]
   (let [require? (boolean (:require-evidence? opts))]
     (reify checker/Checker
       (check [_this _test history _copts]
         (retry-verdict require? (retry-totals history)))))))

;; ---------------------------------------------------------------------------
;; Mount evidence (Job 11, M4): the durability law. A durability run that
;; silently executed on the plain filesystem tested nothing at all — its
;; green is a lie, because the fault it injected could not have touched
;; anything. db.clj already fails a run whose mount never appears; this
;; checker is the after-the-fact proof from collected artifacts, so a
;; reviewer reading store/ can see that every node really ran on lazyfs.
;;
;; The evidence is the kernel's own /proc/mounts line, appended to each
;; node's lazyfs log at mount time (db/mount-lazyfs!):
;;
;;   lazyfs /var/lib/ratis-kv fuse.lazyfs rw,nosuid,nodev,relatime,...
;;
;; and the drop acknowledgements lazyfs writes there when the nemesis
;; sends it a fault, which prove the fault reached the filesystem.
;; ---------------------------------------------------------------------------

(def mount-evidence-pattern
  "The /proc/mounts line proving the storage dir is a lazyfs mount."
  #"fuse\.lazyfs")

(def drop-evidence-pattern
  "lazyfs's acknowledgement of a cache-clearing fault command."
  #"(?i)clear.cache|cache cleared|faults.worker")

(defn count-mount-evidence
  "Pure: {node log-content} in, {:mounted #{nodes}, :unmounted #{nodes},
  :drops {node n}} out."
  [node->content]
  (reduce (fn [acc [node content]]
            (let [lines (str/split-lines (or content ""))
                  mount? (boolean (some #(re-find mount-evidence-pattern %) lines))
                  drops  (count (filter #(re-find drop-evidence-pattern %) lines))]
              (-> acc
                  (update (if mount? :mounted :unmounted) conj node)
                  (assoc-in [:drops node] drops))))
          {:mounted #{} :unmounted #{} :drops {}}
          node->content))

(defn mount-evidence-verdict
  "Pure: the mount-evidence decision. Required only for durability runs;
  every node must show its mount."
  [required? {:keys [mounted unmounted drops] :as ev}]
  (cond
    (not required?)
    (assoc ev :valid? true
           :note "mount evidence not required for this run")

    (seq unmounted)
    (assoc ev :valid? false
           :error :no-lazyfs-mount-evidence
           :note (str "nodes " (pr-str (sort unmounted)) " show no lazyfs "
                      "mount in their collected logs — a durability run "
                      "that was not actually on lazyfs proves nothing"))

    (empty? mounted)
    (assoc ev :valid? false
           :error :no-lazyfs-mount-evidence
           :note "no node produced a lazyfs log at all")

    :else
    (assoc ev :valid? true
           :total-drops (reduce + 0 (vals drops)))))

(defn mount-evidence
  "The mount-evidence checker; REQUIRED when :require-evidence? (the
  workloads set it for durability nemeses)."
  ([] (mount-evidence {}))
  ([opts]
   (let [require? (boolean (:require-evidence? opts))]
     (reify checker/Checker
       (check [_this test _history _copts]
         (mount-evidence-verdict
           require?
           (count-mount-evidence
             (into {}
                   (map (fn [node]
                          [(name node)
                           (let [f (store/path test (name node) "lazyfs.log")]
                             (when (.exists ^java.io.File f) (slurp f)))]))
                   (:nodes test)))))))))
