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

(ns ratis-jepsen.test-runner
  "Git-free test entry point (Job 17, capstone §2.4).

  The previous `:test` alias declared cognitect test-runner as a GIT
  dependency, so building the classpath needed a working `git` — which
  the shipped control container deliberately does not have (run.sh
  test stubs git for jepsen's provenance logging), making the
  documented `clojure -M:test` fail before a single test loaded. This
  runner is plain clojure.test plus tools.namespace (both ordinary
  Maven deps), so the classpath resolves with no network beyond the
  Maven repository, which the maven-repo volume already caches.

  Discovery is directory-based (every namespace found under test/),
  never a hardcoded list, so a newly added test namespace cannot be
  silently skipped; this namespace itself is excluded (it defines no
  tests). Exits 0 iff every discovered test passes; any failure,
  error, or an empty discovery result exits 1 — an empty suite is a
  wiring bug, not a pass. The explicit System/exit matters: the
  integration test boots in-process ratis-kv servers whose non-daemon
  threads would otherwise keep the JVM alive after the summary."
  (:require [clojure.java.io :as io]
            [clojure.test :as test]
            [clojure.tools.namespace.find :as find]))

(defn test-namespaces
  "Every namespace under `dir`, sorted, minus this runner. Pure given
  the directory contents."
  [dir]
  (->> (find/find-namespaces-in-dir (io/file dir))
       (remove #{'ratis-jepsen.test-runner})
       sort
       vec))

(defn run-all
  "Requires and runs the given test namespaces; returns clojure.test's
  merged summary map ({:test :pass :fail :error ...})."
  [namespaces]
  (doseq [n namespaces] (require n))
  (apply test/run-tests namespaces))

(defn -main [& _args]
  (let [namespaces (test-namespaces "test")]
    (println "test-runner: discovered" (count namespaces)
             "test namespaces:" (pr-str namespaces))
    (when (empty? namespaces)
      (println "test-runner: FAIL — no test namespaces found under"
               "test/ (running from the wrong directory?)")
      (System/exit 1))
    (let [{:keys [fail error] :as summary} (run-all namespaces)]
      (println "test-runner: summary" (pr-str summary))
      (System/exit (if (zero? (+ (long fail) (long error))) 0 1)))))
