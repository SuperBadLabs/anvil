(ns anvil.storage.persistence-test
  "TX9 phase 3 — verify that jobs + builds persist across simulated
   daemon restart.

   'Daemon restart' is simulated by: (a) using anvil.storage.db API to
   close the connection, (b) re-init-ing with the same DB path, (c)
   confirming the atom cache is empty but reads still return the data
   (lazy-loaded from disk)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as persist]
            [anvil.web.jenkins-api.jobs :as jobs]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-persist-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  (jobs/clear!)
  (try (f)
       (finally
         (jobs/clear!)
         (db/close!)
         (.delete (io/file tmp-db-path)))))

(use-fixtures :each with-fresh-db)

;; ---------------------------------------------------------------------------
;; Direct persist-layer round-trip
;; ---------------------------------------------------------------------------

(deftest job-upsert-roundtrip-test
  (testing "upsert + find returns the job"
    (persist/upsert-job!
     {:name "demo" :jenkinsfile-source "pipeline { agent any; stages { stage('S') { steps { sh 'hi' } } } }"})
    (let [j (persist/find-job "demo")]
      (is (= "demo" (:name j)))
      (is (.contains (:jenkinsfile-source j) "pipeline"))
      (is (true? (:buildable? j)))
      (is (= :notbuilt (:color j))))))

(deftest job-upsert-with-scm-roundtrip-test
  (testing "scm config round-trips through SQLite"
    (persist/upsert-job!
     {:name "with-scm"
      :jenkinsfile-source "pipeline { }"
      :scm {:type :git
            :url "https://github.com/example/repo.git"
            :branch "main"}})
    (let [j (persist/find-job "with-scm")]
      (is (= "with-scm" (:name j)))
      (is (= :git (-> j :scm :type)))
      (is (= "https://github.com/example/repo.git" (-> j :scm :url)))
      (is (= "main" (-> j :scm :branch))))))

(deftest job-without-scm-has-no-scm-key
  (testing "jobs registered without :scm have no :scm key in the result map"
    (persist/upsert-job! {:name "no-scm" :jenkinsfile-source "pipeline { }"})
    (let [j (persist/find-job "no-scm")]
      (is (= "no-scm" (:name j)))
      (is (not (contains? j :scm)) "absence of :scm distinguishes legacy from configured"))))

(deftest list-jobs-empty-then-populated-test
  (testing "list-jobs starts empty; reflects upserts"
    (is (= [] (persist/list-jobs)))
    (persist/upsert-job! {:name "a" :jenkinsfile-source "pipeline { }"})
    (persist/upsert-job! {:name "b" :jenkinsfile-source "pipeline { }"})
    (is (= ["a" "b"] (mapv :name (persist/list-jobs))))))

(deftest build-start-end-roundtrip-test
  (testing "insert + end produces a complete build row"
    (persist/upsert-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (persist/insert-build-start!
     {:job-name "demo" :number 1 :parameters {"VERSION" "1.0"}})
    (let [b1 (persist/find-build "demo" 1)]
      (is (true? (:building? b1)))
      (is (= 1 (:number b1)))
      (is (= {"VERSION" "1.0"} (:parameters b1))))

    (persist/update-build-end!
     {:job-name "demo" :number 1
      :result :success
      :effects [[:sh {:cmd "echo hi"}] [:stdout "hi"]]
      :console-log "+ echo hi\nhi"
      :duration-ms 1234})
    (let [b2 (persist/find-build "demo" 1)]
      (is (false? (:building? b2)))
      (is (= :success (:result b2)))
      (is (= [[:sh {:cmd "echo hi"}] [:stdout "hi"]] (:effects b2)))
      (is (= "+ echo hi\nhi" (:console-log b2)))
      (is (= 1234 (:duration-ms b2))))))

(deftest allocate-build-number-is-monotonic-test
  (testing "allocate-build-number! returns consecutive integers"
    (persist/upsert-job! {:name "demo" :jenkinsfile-source "pipeline { }"})
    (is (= 1 (persist/allocate-build-number! "demo")))
    (persist/insert-build-start! {:job-name "demo" :number 1})
    (is (= 2 (persist/allocate-build-number! "demo")))
    (persist/insert-build-start! {:job-name "demo" :number 2})
    (is (= 3 (persist/allocate-build-number! "demo")))))

;; ---------------------------------------------------------------------------
;; Cross-restart persistence — the actual TX9 exit-gate-adjacent claim
;; ---------------------------------------------------------------------------

(deftest job-survives-simulated-restart-test
  (testing "a job registered before close! is still readable after re-init!"
    (jobs/register-job! {:name "long-lived"
                         :jenkinsfile-source "pipeline { agent any; stages { stage('S') { steps { sh 'persist!' } } } }"})
    (is (some? (jobs/find-job "long-lived")))

    ;; Simulate daemon restart: clear the atom cache + close+reopen DB.
    (reset! @#'jobs/state {:jobs {} :builds [] :next-build-number {}})
    (db/close!)
    (db/init! tmp-db-path)

    ;; The atom is empty …
    (is (= {} (:jobs @@#'jobs/state)))
    ;; … but find-job triggers a merge-disk-into-atom and the row comes
    ;; back via list-jobs / find-job from the persistence layer.
    (let [j (jobs/find-job "long-lived")]
      (is (some? j))
      (is (.contains (:jenkinsfile-source j) "persist!")))))

(deftest build-survives-simulated-restart-test
  (testing "a build's effects + console log persist across close!/init!"
    (jobs/register-job! {:name "demo"
                         :jenkinsfile-source "pipeline { }"})
    (let [n (jobs/record-build-start! "demo" {})]
      (jobs/record-build-end!
       "demo" n
       {:result :success
        :effects [[:sh {:cmd "echo persist"}] [:stdout "persist"]]}))

    ;; Restart.
    (reset! @#'jobs/state {:jobs {} :builds [] :next-build-number {}})
    (db/close!)
    (db/init! tmp-db-path)

    (let [b (jobs/find-build "demo" 1)]
      (is (some? b))
      (is (= :success (:result b)))
      (is (= [[:sh {:cmd "echo persist"}] [:stdout "persist"]]
             (:effects b)))
      (is (.contains (:console-log b) "echo persist")))))

(deftest no-persistence-no-error-test
  (testing "when db isn't initialized, jobs.clj falls back to atom-only"
    ;; This test runs WITHOUT db init.
    (db/close!)
    (jobs/clear!)                                  ; atom only
    (jobs/register-job! {:name "ephemeral"
                         :jenkinsfile-source "pipeline { }"})
    (is (some? (jobs/find-job "ephemeral"))
        "atom-only mode still works")))