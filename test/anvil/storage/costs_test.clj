(ns anvil.storage.costs-test
  "Tests for anvil.storage.costs (v0.5 T2.1).

   Covers: record!/for-build roundtrip, idempotent upsert, top-by-cost
   ordering, totals-30d aggregation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.storage.costs :as costs]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-costs-storage-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  (jobs-persist/upsert-job! {:name "proj" :jenkinsfile-source "pipeline {}"})
  (doseq [n [1 2 3 4]]
    (jobs-persist/insert-build-start! {:job-name "proj" :number n :parameters {}}))
  (try (f)
       (finally
         (db/close!)
         (.delete (io/file tmp-db-path)))))

(use-fixtures :each with-fresh-db)

(def ^:private base-record
  {:job-name "proj" :build-number 1 :host "builder-a"
   :duration-ms 90000 :rate-per-min 0.012 :currency :usd
   :total-cents 18 :cache-saved-cents 0})

;; ---------------------------------------------------------------------------
;; record! / for-build roundtrip
;; ---------------------------------------------------------------------------

(deftest record-and-read-roundtrip
  (costs/record! base-record)
  (let [row (costs/for-build "proj" 1)]
    (is (some? row))
    (is (= "proj"     (:job-name row)))
    (is (= 1          (:build-number row)))
    (is (= "builder-a" (:host row)))
    (is (= 90000      (:duration-ms row)))
    (is (= 0.012      (:rate-per-min row)))
    (is (= :usd       (:currency row)))
    (is (= 18         (:total-cents row)))
    (is (= 0          (:cache-saved-cents row)))))

(deftest for-build-returns-nil-when-not-found
  (is (nil? (costs/for-build "proj" 99))))

(deftest record-idempotent-upsert
  (testing "re-recording the same build updates the existing row"
    (costs/record! base-record)
    ;; Re-record with different cost values (simulates recorder refire)
    (costs/record! (assoc base-record :total-cents 999 :cache-saved-cents 10))
    (let [row (costs/for-build "proj" 1)]
      (is (= 999 (:total-cents row))   "updated cents")
      (is (= 10  (:cache-saved-cents row)) "updated savings"))))

;; ---------------------------------------------------------------------------
;; top-by-cost
;; ---------------------------------------------------------------------------

(deftest top-by-cost-ordering
  (testing "most expensive build appears first"
    (costs/record! (assoc base-record :build-number 1 :total-cents 10))
    (costs/record! (assoc base-record :build-number 2 :total-cents 500))
    (costs/record! (assoc base-record :build-number 3 :total-cents 50))
    (let [rows (costs/top-by-cost {:limit 10 :days 30})]
      (is (= 3 (count rows)))
      (is (= 500 (:total-cents (first rows))) "most expensive first")
      (is (= 10  (:total-cents (last rows)))  "cheapest last"))))

(deftest top-by-cost-respects-limit
  (doseq [n [1 2 3 4]]
    (costs/record! (assoc base-record :build-number n :total-cents (* n 100))))
  (let [rows (costs/top-by-cost {:limit 2})]
    (is (= 2 (count rows)) "limit respected")))

(deftest top-by-cost-respects-min-cents
  (costs/record! (assoc base-record :build-number 1 :total-cents 5))
  (costs/record! (assoc base-record :build-number 2 :total-cents 100))
  (let [rows (costs/top-by-cost {:min-cents 50})]
    (is (= 1 (count rows)))
    (is (= 100 (:total-cents (first rows))))))

(deftest top-by-cost-empty-when-no-rows
  (is (empty? (costs/top-by-cost {}))))

;; ---------------------------------------------------------------------------
;; totals-30d
;; ---------------------------------------------------------------------------

(deftest totals-30d-sums-correctly
  (costs/record! (assoc base-record :build-number 1 :total-cents 100 :cache-saved-cents 20))
  (costs/record! (assoc base-record :build-number 2 :total-cents 200 :cache-saved-cents 30))
  (costs/record! (assoc base-record :build-number 3 :total-cents 50  :cache-saved-cents 0))
  (let [t (costs/totals-30d)]
    (is (= 350 (:total-cents t))       "sum of total_cents")
    (is (= 50  (:cache-saved-cents t)) "sum of cache_saved_cents")
    (is (= 3   (:build-count t))       "count of builds")))

(deftest totals-30d-zeroes-when-no-rows
  (let [t (costs/totals-30d)]
    (is (= 0 (:total-cents t)))
    (is (= 0 (:cache-saved-cents t)))
    (is (= 0 (:build-count t)))))
