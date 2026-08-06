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

(ns ratis-jepsen.outcome
  "THE OUTCOME MAP (DESIGN 2.4) — classification of every invocation result
  into Jepsen's :ok / :fail / :info verdicts.

  This is the one place a subtle bug silently corrupts every future run's
  analysis, so it favors explicitness over cleverness:

  - `classify` is pure: (op-kind, wire-reply-string-or-Throwable) in,
    verdict map out. Anything that must be logged loudly is returned under
    ::loud instead of logged here; the side-effecting `classify!` wrapper
    (used by the client) does the logging.
  - Writes distinguish definite-not-applied (:fail) from ambiguous (:info).
    Reads are never :info: a read has no side effect, so on any error it
    simply did not happen (:fail).
  - Anything unrecognized is :info for writes (safe pessimism — it *may*
    have applied) and :fail for reads, plus a loud log for triage.

  The DESIGN 2.4 table, as implemented (op-kinds: :write = PUT, :cas = CAS,
  :add = ADD (M3), :read = GET; \"write\" rows apply to :write, :cas
  and :add — ADD is deliberately non-idempotent, which is why its
  ambiguity discipline matters most: an :info ADD is the 0-or-1 case
  the counter checker reasons about):

  | Outcome from client                        | write/cas       | read  |
  |--------------------------------------------|-----------------|-------|
  | reply OK                                   | :ok             | —     |
  | reply VAL v / ABSENT (read)                | —               | :ok   |
  | reply MISMATCH/ABSENT (cas)                | :fail (:error :precondition) | — |
  | reply ERR or wrong-shaped                  | :fail + loud (harness/SUT bug) | :fail + loud |
  | NotLeaderException                         | :info           | :fail |
  | LeaderNotReadyException                    | :info           | :fail |
  | RaftRetryFailureException, null cause      | :info           | :fail |
  |   (the form NotLeader/LeaderNotReady take on retry exhaustion —
  |    see below)                              |                 |       |
  | RaftRetryFailureException, non-null cause  | :info           | :fail |
  | ServerNotReadyException                    | :info           | :fail |
  |   (division STARTING at boot or CLOSED after a membership removal —
  |    routine in bulk during crash restarts and membership churn; the
  |    REQUEST was rejected, but an earlier same-callId attempt may have
  |    applied, so writes stay ambiguous)              |         |       |
  | ResourceUnavailableException               | :fail           | :fail |
  | LeaderSteppingDownException                | :fail (pre-append admission
  |   reject during transfer; single throw site — see row comment) | :fail + loud (cannot happen) |
  | GroupMismatchException                     | :fail + loud (setup bug)   | same |
  | StateMachineException                      | :fail + loud (SUT bug in M0) | same |
  | ReadException / ReadIndexException         | (write: cannot happen —    | :fail |
  |                                            |  pessimism :info + loud)   |       |
  | TimeoutIOException                         | :info           | :fail |
  | generic IOException (transport)            | :info           | :fail |
  | AlreadyClosedException                     | :info           | :fail |
  | interrupt (InterruptedIOException/-Exception) | :info        | :fail |
  | harness-side timeout (j.u.c.TimeoutException) | :info        | :fail |
  | unrecognized RaftException / any other Throwable | :info + loud | :fail + loud |

  Why the leadership rows are ambiguous — DESIGN 2.4 as amended
  2026-08-05 after Review 05's false-red discovery (see
  reviews/05-nemesis-breadth/05_report.md for the full triage, verified
  against ratis-client 3.2.2 source): a NotLeaderException reply is NOT
  proof of non-application. A leader deposed mid-term completes its
  appended-but-uncommitted pending requests with NotLeaderException on
  the step-down path, and those entries — already in its log — can
  replicate and commit under the successor's term. Grading that reply
  \"definite :fail\" convicted a healthy cluster (knossos false-red in a
  mixed-nemesis run: a write both graded not-applied and visible to
  subsequent reads). So every leadership-shaped rejection of a write is
  :info.

  The funnel mechanics (verified at 3.2.2, M0, and unchanged by the
  amendment): when a reply carries NotLeaderException or
  LeaderNotReadyException the client *nulls the reply* instead of
  throwing, routes it through the retry policy, and on exhaustion with a
  null throwable builds RaftRetryFailureException with a null cause —
  that row is those exceptions' usual surface form. The client's bounded
  same-callId retry policy (client.clj, DESIGN 2.3/Q3 as amended)
  resolves most of these before exhaustion: retries re-send the same
  (ClientId, callId), the server retry cache deduplicates, and an
  appended-then-deposed write's retry returns the cached true success.
  What remains after exhaustion is genuinely ambiguous. A non-null cause
  means the last attempt died on a real error (timeout, transport) —
  reachable and routine under a multi-attempt policy, and earlier
  attempts may have applied: same ambiguity, cause preserved in :error.

  The direct NotLeaderException / LeaderNotReadyException rows are kept
  regardless: the classifier must not depend on which wrapping the library
  chooses (some paths, e.g. the async api or future Ratis versions, may
  throw them raw)."
  (:require [clojure.tools.logging :as log])
  (:import (java.io InterruptedIOException IOException)
           (java.util.concurrent ExecutionException TimeoutException)
           (java.util.concurrent CompletionException)
           (org.apache.ratis.protocol.exceptions
             AlreadyClosedException
             GroupMismatchException
             LeaderNotReadyException
             LeaderSteppingDownException
             NotLeaderException
             RaftException
             RaftRetryFailureException
             ReadException
             ReadIndexException
             ResourceUnavailableException
             ServerNotReadyException
             StateMachineException
             TimeoutIOException)))

(def op-kinds
  "The operation kinds. :write is PUT, :cas is CAS, :add is ADD (M3) —
  all write-path; :read is GET (read-path)."
  #{:write :cas :add :read})

(defn write-kind?
  "PUT, CAS and ADD are write-path operations: they may have applied even
  when the invocation errors, so ambiguity must surface as :info."
  [op-kind]
  (case op-kind
    (:write :cas :add) true
    :read false))

;; ---------------------------------------------------------------------------
;; Wire replies (success path — the reply string decoded from
;; reply.getMessage(); grammar per DESIGN 1.4:
;;   OK | VAL <long> | ABSENT | MISMATCH <long> | ERR <reason>)
;; ---------------------------------------------------------------------------

(defn parse-reply
  "Parses a wire reply string into a tagged value:
  [:ok] | [:val <long>] | [:absent] | [:mismatch <long>] | [:err <reason>]
  | [:unparseable <string>]. Total — never throws."
  [^String s]
  (cond
    (nil? s)                  [:unparseable nil]
    (= s "OK")                [:ok]
    (= s "ABSENT")            [:absent]
    :else
    (if-let [[_ v] (re-matches #"VAL (-?\d+)" s)]
      [:val (Long/parseLong v)]
      (if-let [[_ v] (re-matches #"MISMATCH (-?\d+)" s)]
        [:mismatch (Long/parseLong v)]
        (if-let [[_ reason] (re-matches #"ERR (.+)" s)]
          [:err reason]
          [:unparseable s])))))

(defn- unexpected-reply
  "A reply that our op kind can never legally receive: a definite outcome
  (the round-trip completed) but a protocol/harness/SUT bug — :fail, loudly."
  [op-kind ^String reply]
  {:type  :fail
   :error [:unexpected-reply reply]
   ::loud (str "outcome map: protocol violation — op kind " op-kind
               " received reply " (pr-str reply)
               "; this is a harness or SUT bug, not a legitimate op outcome")})

(defn- classify-reply
  "Classifies a decoded wire reply for an op kind. The reply arrived, so the
  outcome is always definite (:ok or :fail) — never :info."
  [op-kind ^String reply]
  (let [[tag x] (parse-reply reply)]
    (case op-kind
      :write (case tag
               :ok {:type :ok}
               (unexpected-reply op-kind reply))
      :cas   (case tag
               :ok       {:type :ok}
               ;; The op failed, definitively, by design: the CAS committed
               ;; and applied, and its precondition did not hold.
               :mismatch {:type :fail, :error :precondition, :current x}
               :absent   {:type :fail, :error :precondition}
               (unexpected-reply op-kind reply))
      ;; ADD replies the value AFTER this apply (absent key counted as
      ;; 0). The total rides :observed so the checker keeps the op's
      ;; delta in :value while still seeing what the server reported —
      ;; on a deduplicated retry this is the CACHED original total.
      :add   (case tag
               :val {:type :ok, :observed x}
               (unexpected-reply op-kind reply))
      :read  (case tag
               :val    {:type :ok, :value x}
               :absent {:type :ok, :value nil}
               (unexpected-reply op-kind reply)))))

;; ---------------------------------------------------------------------------
;; Throwables
;; ---------------------------------------------------------------------------

(defn- definite-fail
  "A definite not-applied / not-performed: :fail for every op kind."
  ([error] {:type :fail, :error error})
  ([error loud] {:type :fail, :error error, ::loud loud}))

(defn- ambiguous
  "May or may not have applied: :info for writes (safe pessimism), :fail
  for reads (a read has no side effect — it simply did not happen)."
  ([op-kind error]
   {:type (if (write-kind? op-kind) :info :fail), :error error})
  ([op-kind error loud]
   (assoc (ambiguous op-kind error) ::loud loud)))

(defn- unknown-throwable
  "The pessimism-plus-loud-log bucket for anything unrecognized."
  [op-kind ^Throwable t]
  (ambiguous op-kind
             [:unknown-throwable (str (.getName (class t)) ": " (.getMessage t))]
             (str "outcome map: UNRECOGNIZED throwable "
                  (.getName (class t)) " (" (.getMessage t) ") for op kind "
                  op-kind " — classified pessimistically ("
                  (if (write-kind? op-kind) ":info" ":fail")
                  "); add an explicit row for it")))

(defn- unwrap
  "Strips ExecutionException/CompletionException wrappers (the harness runs
  each invocation in a future) down to the underlying cause."
  ^Throwable [^Throwable t]
  (if (and (or (instance? ExecutionException t)
               (instance? CompletionException t))
           (.getCause t))
    (recur (.getCause t))
    t))

(defn- classify-throwable
  "Classifies a Throwable for an op kind, per the table in the ns docstring.
  Dispatch is an explicit instance? chain; order matters (most exception
  types here extend RaftException, which extends IOException)."
  [op-kind ^Throwable t*]
  (let [t (unwrap t*)]
    (condp instance? t
      ;; NotLeader/LeaderNotReady are NOT proof of non-application for the
      ;; write path (DESIGN 2.4 as amended 2026-08-05, Review 05): a
      ;; deposed leader completes appended-but-uncommitted pending
      ;; requests with NotLeaderException, and those entries can commit
      ;; under its successor. Ambiguous ⇒ :info for writes, :fail for
      ;; reads (no side effect either way).
      NotLeaderException          (ambiguous op-kind :not-leader)
      LeaderNotReadyException     (ambiguous op-kind :leader-not-ready)

      ;; The addressed division is not RUNNING: STARTING during a boot,
      ;; or CLOSED — a membership removal's self-shutdown (Job 08; the
      ;; leader answers a removed peer's vote request with
      ;; shouldShutdown and the division closes itself at 3.2.2).
      ;; Routine in bulk during crash restarts and membership churn.
      ;; THIS request was rejected, but the invocation may be a retry
      ;; whose earlier attempt applied — same ambiguity discipline as
      ;; the leadership rows: :info for writes, :fail for reads.
      ServerNotReadyException     (ambiguous op-kind :server-not-ready)

      ;; Definite not-appended: admission control rejects pre-append.
      ResourceUnavailableException (definite-fail :resource-unavailable)

      ;; Definite not-appended (M2, reached by leadership transfers):
      ;; thrown ONLY by the pre-append admission check
      ;; (RaftServerImpl.checkLeaderState, single construction site at
      ;; 3.2.2) while a leader hands off — the request was never
      ;; appended, and pending appended requests are completed with
      ;; NotLeaderException instead (which is why THAT row is :info and
      ;; this one is :fail). The isReadOnly() guard means reads can
      ;; never legally receive it; treat that impossibility with
      ;; pessimism like the ReadException-on-write rows.
      LeaderSteppingDownException
      (if (write-kind? op-kind)
        (definite-fail :leader-stepping-down)
        (unknown-throwable op-kind t))

      ;; Definite, but also a bug in the test setup or the SUT — flag the
      ;; run loudly (DESIGN 2.4).
      GroupMismatchException
      (definite-fail :group-mismatch
                     (str "outcome map: GroupMismatchException — the "
                          "addressed server hosts no such group. In a "
                          "membership run this can be a worker racing a "
                          "node's return to the pool (benign; the client's "
                          "next NotLeaderException refreshes its peers); "
                          "anywhere else it is a test-setup bug — flag the "
                          "run: " (.getMessage t)))
      StateMachineException
      (definite-fail :state-machine
                     (str "outcome map: StateMachineException — our state "
                          "machine never throws from apply, so reaching this "
                          "means a SUT bug; flag the run: " (.getMessage t)))

      ;; Read-path failures: the read never happened. On the write path
      ;; these cannot occur; treat that impossibility with pessimism.
      ReadException
      (if (write-kind? op-kind)
        (unknown-throwable op-kind t)
        (definite-fail :read))
      ReadIndexException
      (if (write-kind? op-kind)
        (unknown-throwable op-kind t)
        (definite-fail :read-index))

      ;; Retry exhaustion (see ns docstring). Null cause = the
      ;; NotLeader/LeaderNotReady funnel: every attempt got a
      ;; leadership-shaped rejection, but any of them may have reached a
      ;; leader that appended before stepping down — ambiguous, exactly
      ;; like the raw exceptions above. Non-null cause = the last attempt
      ;; failed with a real error (timeout, transport); earlier attempts
      ;; may have applied — ambiguous, with the cause preserved for
      ;; diagnosis.
      RaftRetryFailureException
      (if (nil? (.getCause t))
        (ambiguous op-kind :not-leader-or-not-ready)
        (ambiguous op-kind
                   [:retry-failure (str (.getName (class (.getCause t))) ": "
                                        (.getMessage ^Throwable (.getCause t)))]))

      ;; Ambiguous: the request may have reached the leader and applied.
      TimeoutIOException          (ambiguous op-kind :timeout)
      AlreadyClosedException      (ambiguous op-kind :already-closed)
      InterruptedIOException      (ambiguous op-kind :interrupted)
      InterruptedException        (ambiguous op-kind :interrupted)
      ;; The harness-side invocation deadline (client wraps every call).
      TimeoutException            (ambiguous op-kind :harness-timeout)

      ;; An unrecognized RaftException is a protocol condition we have no
      ;; row for — loud pessimism, not the quiet generic-IO row.
      RaftException               (unknown-throwable op-kind t)

      ;; Generic transport-level IO error (connection refused, channel
      ;; broken, grpc UNAVAILABLE wrapped by the client): quiet ambiguity —
      ;; expected in bulk during faults.
      IOException                 (ambiguous op-kind :io)

      ;; Anything else — including RuntimeException and Error.
      (unknown-throwable op-kind t))))

;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

(defn classify
  "THE outcome map (DESIGN 2.4). Pure.

  op-kind: :write (PUT) | :cas (CAS) | :read (GET).
  outcome: the decoded wire reply String on success, or the Throwable the
  invocation raised (ExecutionException/CompletionException wrappers from
  the harness's future are unwrapped).

  Returns a verdict map to merge into the Jepsen op:
    {:type :ok | :fail | :info}
  plus, where applicable, :error (detail), :value (reads: the long read, or
  nil for ABSENT), :current (cas mismatch: the value the register held),
  and ::loud (a triage message the caller must log at error level —
  `classify!` does this)."
  [op-kind outcome]
  (assert (contains? op-kinds op-kind) (str "unknown op kind: " op-kind))
  (if (instance? Throwable outcome)
    (classify-throwable op-kind outcome)
    (classify-reply op-kind outcome)))

(defn classify!
  "classify, plus the loud-log side effect: when the verdict carries ::loud,
  logs it at error level (with the throwable's stack trace when there is
  one) and strips the key from the returned verdict."
  [op-kind outcome]
  (let [verdict (classify op-kind outcome)]
    (if-let [msg (::loud verdict)]
      (do (if (instance? Throwable outcome)
            (log/error outcome msg)
            (log/error msg))
          (dissoc verdict ::loud))
      verdict)))
