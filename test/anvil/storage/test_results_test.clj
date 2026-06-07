(ns anvil.storage.test-results-test
  "Tests for the test-results persistence layer (T1.3).

   Uses a temp SQLite file so migration 005 runs against a real
   schema. Each test gets a fresh DB."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.storage.test-results :as tr]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-test-results-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  ;; Seed a job + a build so the FK constraints can land.
  (jobs-persist/upsert-job!
   {:name "anvil-self"
    :jenkinsfile-source "pipeline { agent any; stages { stage('s') { steps { sh 'true' } } } }"})
  (jobs-persist/insert-build-start! {:job-name "anvil-self" :number 42 :parameters {}})
  (try (f)
       (finally
         (db/close!)
         (.delete (io/file tmp-db-path)))))

(use-fixtures :each with-fresh-db)

(defn- sample-tree
  "A 3-case tree with one of each outcome, modeling what
   parse-surefire-tree returns."
  []
  {:suites [{:name "demo.SuiteA"
             :tests 3 :passed 1 :failed 1 :errored 0 :skipped 1
             :duration-ms 234
             :cases [{:test-id "demo.SuiteA#happy"
                      :name "happy" :class "demo.SuiteA"
                      :status :passed :duration-ms 12
                      :failure-msg nil :failure-type nil :failure-trace nil}
                     {:test-id "demo.SuiteA#broken"
                      :name "broken" :class "demo.SuiteA"
                      :status :failed :duration-ms 200
                      :failure-msg "expected 1 got 2"
                      :failure-type "AssertionError"
                      :failure-trace "    at demo.SuiteA.broken(SuiteA.java:42)"}
                     {:test-id "demo.SuiteA#wip"
                      :name "wip" :class "demo.SuiteA"
                      :status :skipped :duration-ms 0
                      :failure-msg "TODO" :failure-type nil :failure-trace nil}]}]
   :totals {:tests 3 :passed 1 :failed 1 :errored 0 :skipped 1 :duration-ms 234}
   :parse-errors []})

(deftest record-then-read-back-roundtrips
  (let [summary (tr/record-build-results! "anvil-self" 42 (sample-tree))]
    (testing "returned summary mirrors the totals"
      (is (= 3 (:tests summary)))
      (is (= 1 (:passed summary)))
      (is (= 1 (:failed summary)))
      (is (= 1 (:skipped summary)))
      (is (= 0 (:parse-errors summary))))
    (testing "find-summary returns the persisted row"
      (let [s (tr/find-summary "anvil-self" 42)]
        (is (= 3 (:tests s)))
        (is (= 234 (:duration-ms s)))))
    (testing "find-results returns the per-case rows"
      (let [results (tr/find-results "anvil-self" 42)]
        (is (= 3 (count results)))
        (is (= "demo.SuiteA#broken"
               (:test-id (first (filter #(= :failed (:status %)) results)))))))
    (testing "find-failed-results returns only :failed and :errored"
      (let [fails (tr/find-failed-results "anvil-self" 42)]
        (is (= 1 (count fails)))
        (is (= :failed (:status (first fails))))))))

(deftest re-record-replaces-prior-results
  (testing "scan, re-scan with different results — old rows gone, new rows present"
    (tr/record-build-results! "anvil-self" 42 (sample-tree))
    (is (= 3 (count (tr/find-results "anvil-self" 42))))
    (tr/record-build-results!
     "anvil-self" 42
     {:suites [{:name "demo.SuiteA" :tests 1 :passed 1 :failed 0
                :errored 0 :skipped 0 :duration-ms 5
                :cases [{:test-id "demo.SuiteA#only-one"
                         :name "only-one" :class "demo.SuiteA"
                         :status :passed :duration-ms 5
                         :failure-msg nil :failure-type nil :failure-trace nil}]}]
      :totals {:tests 1 :passed 1 :failed 0 :errored 0 :skipped 0 :duration-ms 5}
      :parse-errors []})
    (is (= 1 (count (tr/find-results "anvil-self" 42))))
    (is (= 1 (:tests (tr/find-summary "anvil-self" 42))))))

(deftest recent-summaries-orders-by-build-number-desc
  (jobs-persist/insert-build-start! {:job-name "anvil-self" :number 43 :parameters {}})
  (jobs-persist/insert-build-start! {:job-name "anvil-self" :number 44 :parameters {}})
  (tr/record-build-results! "anvil-self" 42 (sample-tree))
  (tr/record-build-results! "anvil-self" 43 (sample-tree))
  (tr/record-build-results! "anvil-self" 44 (sample-tree))
  (let [recent (tr/recent-summaries "anvil-self" 2)]
    (is (= 2 (count recent)))
    (is (= [44 43] (mapv :build-number recent)))))

(deftest find-test-history-orders-most-recent-first
  (jobs-persist/insert-build-start! {:job-name "anvil-self" :number 43 :parameters {}})
  (tr/record-build-results! "anvil-self" 42 (sample-tree))
  (tr/record-build-results! "anvil-self" 43 (sample-tree))
  (let [hist (tr/find-test-history "anvil-self" "demo.SuiteA#happy" 5)]
    (is (= 2 (count hist)))
    (is (= [43 42] (mapv :build-number hist)))))

(deftest empty-tree-still-records-summary
  (testing "a build with no test artifacts produces a zero-summary, no rows"
    (tr/record-build-results!
     "anvil-self" 42
     {:suites [] :totals {:tests 0 :passed 0 :failed 0 :errored 0 :skipped 0 :duration-ms 0}
      :parse-errors []})
    (is (empty? (tr/find-results "anvil-self" 42)))
    (let [s (tr/find-summary "anvil-self" 42)]
      (is (= 0 (:tests s))))))

(deftest parse-errors-count-recorded
  (tr/record-build-results!
   "anvil-self" 42
   {:suites [] :totals {:tests 0 :passed 0 :failed 0 :errored 0 :skipped 0 :duration-ms 0}
    :parse-errors [{:source "garbage1.xml" :message "..." :exception "..."}
                   {:source "garbage2.xml" :message "..." :exception "..."}]})
  (is (= 2 (:parse-errors (tr/find-summary "anvil-self" 42)))))

;; ---------------------------------------------------------------------------
;; v0.4 T1.2 — per-attempt rows + flaky write-back
;; ---------------------------------------------------------------------------

(defn- one-case-tree
  "Tree containing exactly one testcase row, in the named status.
   Lets us simulate per-attempt scans without mocking surefire XML."
  [test-id status]
  {:suites [{:name "demo.RetrySuite"
             :tests 1
             :passed (if (= status :passed) 1 0)
             :failed (if (= status :failed) 1 0)
             :errored (if (= status :errored) 1 0)
             :skipped (if (= status :skipped) 1 0)
             :duration-ms 5
             :cases [{:test-id test-id
                      :name (subs test-id (inc (.indexOf test-id "#")))
                      :class (subs test-id 0 (.indexOf test-id "#"))
                      :status status
                      :duration-ms 5
                      :failure-msg (when (#{:failed :errored} status) "boom")
                      :failure-type (when (#{:failed :errored} status) "Boom")
                      :failure-trace (when (#{:failed :errored} status) "stack")}]}]
   :totals {:tests 1
            :passed (if (= status :passed) 1 0)
            :failed (if (= status :failed) 1 0)
            :errored (if (= status :errored) 1 0)
            :skipped (if (= status :skipped) 1 0)
            :duration-ms 5}
   :parse-errors []})

(deftest attempt-aware-record-keeps-prior-attempts
  (testing "calling record-build-results! with successive :attempt-number does NOT clobber prior attempts"
    (tr/record-build-results! "anvil-self" 42
                              (one-case-tree "demo.RetrySuite#flaky" :failed)
                              {:attempt-number 1})
    (tr/record-build-results! "anvil-self" 42
                              (one-case-tree "demo.RetrySuite#flaky" :passed)
                              {:attempt-number 2})
    (let [rows (tr/find-results-all-attempts "anvil-self" 42)]
      (is (= 2 (count rows)))
      (is (= [1 2] (mapv :attempt-number rows))
          "rows ordered by attempt_number ASC")
      (is (= [:failed :passed] (mapv :status rows))))))

(deftest single-attempt-call-still-replaces-attempt-1
  (testing "back-compat: arity-3 call replaces prior attempt-1 rows only (delete-then-insert in same attempt slot)"
    (tr/record-build-results! "anvil-self" 42
                              (one-case-tree "demo.RetrySuite#a" :failed))
    (tr/record-build-results! "anvil-self" 42
                              (one-case-tree "demo.RetrySuite#a" :passed))
    (let [rows (tr/find-results-all-attempts "anvil-self" 42)]
      (is (= 1 (count rows)))
      (is (= 1 (:attempt-number (first rows))))
      (is (= :passed (:status (first rows)))))))

(deftest write-flaky-flags-rewrites-rows-and-clears-stale-flags
  (testing "write-flaky-flags! flips flaky_bool + retry_count across all attempt rows for the named tests"
    (tr/record-build-results! "anvil-self" 42
                              (one-case-tree "demo#flaked" :failed)
                              {:attempt-number 1})
    (tr/record-build-results! "anvil-self" 42
                              (one-case-tree "demo#flaked" :passed)
                              {:attempt-number 2})
    (tr/record-build-results! "anvil-self" 42
                              (one-case-tree "demo#stable" :passed)
                              {:attempt-number 1})
    (let [n (tr/write-flaky-flags! "anvil-self" 42 {"demo#flaked" 1})]
      ;; 2 flaked rows + 1 stable row are all UPDATEd by the clear-then-set
      ;; pattern, but jdbc/update-count aggregates across the second
      ;; UPDATE (the named-test one) — just verify it touched something
      ;; positive.  Behavior verified via the SELECTs below.
      (is (pos? n)))
    (let [flaky (tr/find-flaky-tests "anvil-self" 42)]
      (is (= 1 (count flaky)))
      (is (= "demo#flaked" (:test-id (first flaky))))
      (is (true? (:flaky? (first flaky))))
      (is (= 1 (:retry-count (first flaky))))
      ;; Latest-attempt — the one that PASSED
      (is (= 2 (:attempt-number (first flaky))))
      (is (= :passed (:status (first flaky)))))
    (testing "stable test has flaky=false / retry-count=0 written through"
      (let [rows (tr/find-results-all-attempts "anvil-self" 42)
            stable (first (filter #(= "demo#stable" (:test-id %)) rows))]
        (is (false? (:flaky? stable)))
        (is (= 0 (:retry-count stable))))))
  (testing "re-running write-flaky-flags! with empty map clears prior flake flags"
    (tr/write-flaky-flags! "anvil-self" 42 {})
    (is (empty? (tr/find-flaky-tests "anvil-self" 42)))))
