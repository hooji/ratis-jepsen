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
