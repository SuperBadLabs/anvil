(ns anvil.web.test-results-route-test
  "T1.7 partial — route-level smoke that the build page renders the
   test-results panel end-to-end (parser → store → view → ring
   response), no etaoin needed.

   The etaoin browser smoke (`^:browser` tagged below) covers the
   same surface but requires Firefox + geckodriver, so it's opt-in
   via `lein test :browser`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.storage.test-results :as tr]
            [anvil.features :as features]))

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-tr-route-test.db"))

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

(defn- seed-build-with-tests [job-name n]
  (jobs-persist/upsert-job! {:name job-name :jenkinsfile-source "pipeline {}"})
  (jobs/register-job! {:name job-name
                       :jenkinsfile-source "pipeline { agent any }"
                       :buildable? true
                       :max-concurrent-builds 1})
  (jobs/record-build-start! job-name {:parameters {}})
  (jobs/record-build-end! job-name n
                          {:result :success
                           :effects []
                           :console-log ""
                           :duration-ms 100})
  ;; Now slot in some test results
  (tr/record-build-results!
   job-name n
   {:suites [{:name "demo.CalcTest"
              :tests 4 :passed 2 :failed 1 :errored 1 :skipped 0
              :duration-ms 234
              :cases [{:test-id "demo.CalcTest#happy"
                       :name "happy" :class "demo.CalcTest"
                       :status :passed :duration-ms 10
                       :failure-msg nil :failure-type nil :failure-trace nil}
                      {:test-id "demo.CalcTest#fast"
                       :name "fast" :class "demo.CalcTest"
                       :status :passed :duration-ms 5
                       :failure-msg nil :failure-type nil :failure-trace nil}
                      {:test-id "demo.CalcTest#divides_by_zero"
                       :name "divides_by_zero" :class "demo.CalcTest"
                       :status :failed :duration-ms 200
                       :failure-msg "Expected ArithmeticException"
                       :failure-type "java.lang.AssertionError"
                       :failure-trace "  at demo.CalcTest.divides_by_zero(CalcTest.java:42)"}
                      {:test-id "demo.CalcTest#overflow"
                       :name "overflow" :class "demo.CalcTest"
                       :status :errored :duration-ms 19
                       :failure-msg "NPE"
                       :failure-type "java.lang.NullPointerException"
                       :failure-trace "  at demo.CalcTest.overflow(CalcTest.java:88)"}]}]
    :totals {:tests 4 :passed 2 :failed 1 :errored 1 :skipped 0 :duration-ms 234}
    :parse-errors []}))

(defn- get-page [path]
  (let [h (routes/make-handler)]
    (h {:request-method :get :uri path})))

(deftest build-page-renders-test-panel-when-flag-on-and-results-exist
  (features/set! :junit true)
  (seed-build-with-tests "demo" 1)
  (let [resp (get-page "/jobs/demo/1")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (testing "section anchor present"
      (is (str/includes? body "test-results")))
    (testing "summary pills render with counts"
      (is (str/includes? body "2 passed"))
      (is (str/includes? body "1 failed"))
      (is (str/includes? body "1 errored")))
    (testing "failure block shows class#name and message"
      (is (str/includes? body "demo.CalcTest"))
      (is (str/includes? body "Expected ArithmeticException")))
    (testing "results-table opener present"
      (is (str/includes? body "All 4 tests")))))

(deftest build-page-omits-panel-when-flag-off
  (features/set! :junit false)
  (seed-build-with-tests "demo" 1)
  (let [resp (get-page "/jobs/demo/1")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (testing "summary pills NOT rendered when flag is off"
      (is (not (str/includes? body "2 passed")))
      (is (not (str/includes? body "1 failed"))))))

(deftest build-page-omits-panel-when-no-test-results-recorded
  (features/set! :junit true)
  (jobs-persist/upsert-job! {:name "demo2" :jenkinsfile-source "pipeline {}"})
  (jobs/register-job! {:name "demo2"
                       :jenkinsfile-source "pipeline { agent any }"
                       :buildable? true
                       :max-concurrent-builds 1})
  (jobs/record-build-start! "demo2" {:parameters {}})
  (jobs/record-build-end! "demo2" 1
                          {:result :success :effects [] :console-log "" :duration-ms 50})
  (let [resp (get-page "/jobs/demo2/1")
        body (:body resp)]
    (is (= 200 (:status resp)))
    (testing "build-page renders without the test-results section when no scan ran"
      (is (not (str/includes? body "summary-pill"))))))

;; ---------------------------------------------------------------------------
;; etaoin browser smoke — opt-in via `lein test :browser`. Requires Firefox.
;; ---------------------------------------------------------------------------

(deftest ^:browser ^:test-results-browser test-panel-visible-in-firefox
  ;; Minimal smoke: confirms the panel's .test-results section appears
  ;; in a real browser DOM after seeding. Heavier interactive testing
  ;; (clicking the failure-details, sparkline alt-text) lives in T8.3's
  ;; refreshed tour.
  ;;
  ;; Skipped at default test run because :browser is filtered out.
  ;; Opt in: `lein test :browser anvil.web.test-results-route-test`.
  (require '[etaoin.api :as e]
           '[anvil.web.server :as server])
  (let [e-driver (resolve 'etaoin.api/firefox)
        e-go    (resolve 'etaoin.api/go)
        e-exists?(resolve 'etaoin.api/exists?)
        e-quit  (resolve 'etaoin.api/quit)
        start!  (resolve 'anvil.web.server/start!)
        stop!   (resolve 'anvil.web.server/stop!)
        port 18765]
    (features/set! :junit true)
    (seed-build-with-tests "demo" 1)
    (start! {:port port :host "127.0.0.1"})
    (let [driver (e-driver {:headless true})]
      (try
        (e-go driver (str "http://127.0.0.1:" port "/jobs/demo/1"))
        (is (e-exists? driver {:css ".test-results"}))
        (is (e-exists? driver {:css ".test-summary"}))
        (is (e-exists? driver {:css ".test-failures"}))
        (finally
          (e-quit driver)
          (stop!))))))
