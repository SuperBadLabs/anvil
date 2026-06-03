(ns anvil.compat.jenkins.h-junit-test
  "Integration test for the v0.3 T1.6 junit step upgrade.

   When the :junit feature flag is on AND ctx provides a workspace +
   job/build identity, the junit step scans the workspace, persists
   the results, and publishes :test-completed on the bus. Otherwise
   it falls back to the legacy recorder-only behavior."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.storage.test-results :as tr]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.features :as features]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as ad]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-h-junit-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  (jobs-persist/upsert-job!
   {:name "demo" :jenkinsfile-source "pipeline {}"})
  (jobs-persist/insert-build-start! {:job-name "demo" :number 7 :parameters {}})
  (try (f)
       (finally
         (bus/unsubscribe-all!)
         (db/close!)
         (.delete (io/file tmp-db-path)))))

(use-fixtures :each with-fresh-db)

(defn- effects [dispatcher]
  @(:effects dispatcher))

(defn- mk-workspace-with-surefire []
  (let [ws (.toFile (java.nio.file.Files/createTempDirectory
                      "anvil-h-junit-ws-"
                      (into-array java.nio.file.attribute.FileAttribute [])))
        reports (io/file ws "target/surefire-reports")]
    (.mkdirs reports)
    (io/copy (io/file "test/resources/junit-fixtures/junit4-surefire-2x.xml")
             (io/file reports "TEST-com.example.CalcTest.xml"))
    ws))

(defn- rm-rf [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rm-rf c)))
  (.delete f))

;; ---------------------------------------------------------------------------
;; Off-mode = recorder-only (regression guard against TX2-era tests)
;; ---------------------------------------------------------------------------

(deftest h-junit-with-flag-off-records-only
  (features/set! :junit false)
  (let [ws (mk-workspace-with-surefire)
        dsp (ad/make)]
    (try
      (d/dispatch dsp
                  {:type :jenkins/junit :results "target/surefire-reports/*.xml"}
                  {:workspace (str ws) :job-name "demo" :build-number 7})
      (let [evs (effects dsp)
            junit-eff (->> evs (filter #(= :junit (first %))) first)]
        (is (some? junit-eff))
        (is (re-find #"recorder-only" (str (:note (second junit-eff))))))
      ;; And nothing was persisted
      (is (nil? (tr/find-summary "demo" 7)))
      (finally (rm-rf ws)))))

;; ---------------------------------------------------------------------------
;; On-mode = real scan + persist + publish
;; ---------------------------------------------------------------------------

(deftest h-junit-with-flag-on-scans-persists-and-publishes
  (features/set! :junit true)
  (let [ws (mk-workspace-with-surefire)
        events (atom [])
        token (bus/subscribe! (topics/topic-build "demo" 7)
                              #(swap! events conj %))
        dsp (ad/make)]
    (try
      (d/dispatch dsp
                  {:type :jenkins/junit :results "target/surefire-reports/*.xml"}
                  {:workspace (str ws) :job-name "demo" :build-number 7})

      (testing "summary lands in the DB"
        (let [s (tr/find-summary "demo" 7)]
          (is (some? s))
          (is (= 5 (:tests s)))
          (is (= 1 (:failed s)))
          (is (= 1 (:errored s)))
          (is (= 1 (:skipped s)))))

      (testing "individual cases land in the DB"
        (let [rows (tr/find-results "demo" 7)]
          (is (= 5 (count rows)))
          ;; find-failed-results returns failed OR errored (1 + 1 = 2)
          (is (= 2 (count (tr/find-failed-results "demo" 7))))))

      (testing ":test-completed published on the build topic"
        (is (= 1 (count @events)))
        (is (= :test-completed (:type (first @events))))
        (is (= 5 (:tests (first @events)))))

      (testing "effects ledger carries the real counts (not the recorder-only note)"
        (let [junit-eff (->> (effects dsp)
                             (filter #(= :junit (first %)))
                             first
                             second)]
          (is (= 5 (:tests junit-eff)))
          (is (= 1 (:scanned-files junit-eff)))
          (is (nil? (:note junit-eff)))))
      (finally
        (bus/unsubscribe! token)
        (rm-rf ws)))))

(deftest h-junit-tolerates-comma-separated-globs
  (features/set! :junit true)
  (let [ws (mk-workspace-with-surefire)]
    ;; Add a second fixture under a different layout
    (let [gradle-dir (io/file ws "build/test-results/test")]
      (.mkdirs gradle-dir)
      (io/copy (io/file "test/resources/junit-fixtures/surefire-3x.xml")
               (io/file gradle-dir "TEST-second.xml")))
    (let [dsp (ad/make)]
      (try
        (d/dispatch dsp
                    {:type :jenkins/junit
                     :results "target/surefire-reports/*.xml, build/test-results/test/*.xml"}
                    {:workspace (str ws) :job-name "demo" :build-number 7})
        (let [s (tr/find-summary "demo" 7)]
          (is (= 11 (:tests s)) "junit4 5 + surefire 6 = 11 across both globs"))
        (finally (rm-rf ws))))))
