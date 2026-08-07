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

(ns ratis-jepsen.core-test
  "Unit tests for core's pure version-matrix parts (Job 12): the
  node→starting-version assignment and the classpath client-version
  parse behind the version-skew guard."
  (:require [clojure.test :refer [deftest is testing]]
            [ratis-jepsen.core :as core]))

(def voters ["n1" "n2" "n3" "n4" "n5"])

(deftest initial-version-assignment
  (testing "single version: everything at --ratis-version"
    (is (= (zipmap voters (repeat "3.3.0"))
           (core/initial-version-map voters "3.3.0" nil false))))
  (testing "static mixed split: first ceil(n/2) OLD, rest NEW"
    (is (= {"n1" "3.2.2" "n2" "3.2.2" "n3" "3.2.2"
            "n4" "3.3.0" "n5" "3.3.0"}
           (core/initial-version-map voters "3.2.2"
                                     ["3.2.2" "3.3.0"] false))))
  (testing "rolling upgrade: everything starts OLD"
    (is (= (zipmap voters (repeat "3.2.2"))
           (core/initial-version-map voters "3.2.2"
                                     ["3.2.2" "3.3.0"] true))))
  (testing "even node counts still split majority-old"
    (is (= {"n1" "a" "n2" "a" "n3" "b" "n4" "b"}
           (core/initial-version-map ["n1" "n2" "n3" "n4"] "a"
                                     ["a" "b"] false)))))

(deftest classpath-client-version-parse
  (testing "the Maven-repo jar path yields its version"
    (is (= "3.2.2"
           (core/classpath-ratis-client-version
             (str "/x/other-1.0.jar:"
                  "/repo/org/apache/ratis/ratis-client/3.2.2/"
                  "ratis-client-3.2.2.jar:/y/clojure-1.12.1.jar")
             ":")))
    (is (= "3.3.0"
           (core/classpath-ratis-client-version
             "/m2/ratis-client-3.3.0.jar" ":"))))
  (testing "no recognizable jar -> nil (guard warns instead of lying)"
    (is (nil? (core/classpath-ratis-client-version
                "/x/ratis-grpc-3.2.2.jar:/y/app.jar" ":")))
    (is (nil? (core/classpath-ratis-client-version "" ":"))))
  (testing "sources/javadoc classifiers do not fool the parse"
    ;; ratis-client-3.2.2-sources.jar must not be read as version
    ;; "3.2.2-sources" *when the real jar is also present* — the regex
    ;; matches both, so precision here documents the accepted limit:
    ;; the first match on the classpath wins.
    (is (= "3.2.2"
           (core/classpath-ratis-client-version
             (str "/repo/ratis-client-3.2.2.jar:"
                  "/repo/ratis-client-3.2.2-sources.jar")
             ":")))))

(deftest check-client-version-guard
  (testing "a matching classpath passes and reports the version"
    ;; the test JVM really runs ratis-client (a harness dep) — the guard
    ;; against the version deps.edn pins must pass
    (is (string? (core/check-client-version!
                   (core/classpath-ratis-client-version)))))
  (testing "a mismatch throws the version-skew error"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"version skew"
          (core/check-client-version! "0.0.0-not-on-classpath")))))
