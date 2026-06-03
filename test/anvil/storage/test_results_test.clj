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
