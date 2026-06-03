(ns anvil.storage.problems-test
  "Tests for the problem-matcher persistence layer (T2.3)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.storage.problems :as p]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-problems-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  (jobs-persist/upsert-job! {:name "demo" :jenkinsfile-source "pipeline {}"})
  (jobs-persist/insert-build-start! {:job-name "demo" :number 1 :parameters {}})
  (try (f)
       (finally
         (db/close!)
         (.delete (io/file tmp-db-path)))))

(use-fixtures :each with-fresh-db)

(deftest record-and-read-roundtrips
  (p/record-problem! "demo" 1 100
                     {:source "gcc" :severity :error
                      :file "a.c" :line 12 :column 3
                      :message "expected ;"})
  (p/record-problem! "demo" 1 102
                     {:source "gcc" :severity :warning
                      :file "a.c" :line 14 :column 1
                      :message "unused variable"})
  (p/record-problem! "demo" 1 130
                     {:source "::workflow" :severity :note
                      :file "b.rs" :line 1 :column nil
                      :message "by the way"})
  (testing "all problems land in log_seq order"
    (let [probs (p/find-problems "demo" 1)]
      (is (= 3 (count probs)))
      (is (= [100 102 130] (mapv :log-seq probs)))))
  (testing "summary aggregates by severity"
    (let [s (p/find-summary "demo" 1)]
      (is (= 1 (:errors s)))
      (is (= 1 (:warnings s)))
      (is (= 1 (:notes s)))
      (is (= 0 (:infos s)))))
  (testing "find-problems-by-severity filters"
    (let [errs (p/find-problems-by-severity "demo" 1 #{:error})]
      (is (= 1 (count errs)))
      (is (= :error (:severity (first errs)))))
    (let [non-info (p/find-problems-by-severity "demo" 1 #{:error :warning :note})]
      (is (= 3 (count non-info))))))

(deftest summary-counts-multiple-of-same-severity
  (dotimes [i 5]
    (p/record-problem! "demo" 1 i
                       {:source "gcc" :severity :error
                        :file (str "x" i ".c") :line i
                        :message (str "err " i)}))
  (let [s (p/find-summary "demo" 1)]
    (is (= 5 (:errors s)))
    (is (= 0 (:warnings s)))))
