(ns anvil.web.tour-capture-test
  "TU6.5 + TU6.6 — captures the screenshot tour for docs/anvil-ui/tour.md
   and a lightweight perf audit receipt.

   Tagged ^:tour-capture so it's NOT in the default `lein test :browser`
   sweep — it writes PNGs + EDN to docs/anvil-ui/tour/ which is
   intentionally side-effect-y and should run only on demand:

     lein test :tour-capture anvil.web.tour-capture-test

   Boots anvil on a random port, registers three demo jobs with
   varied history, navigates the headless browser to every important
   page, snaps a PNG, and timestamps how long each first paint took."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [etaoin.api :as e]
            [anvil.events.bus :as bus]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.server :as server])
  (:import (java.net ServerSocket)
           (java.time Instant)))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *driver* nil)
(def ^:dynamic *base-url* nil)

;; Path is relative to the REPO ROOT (i.e. ../docs/...) because
;; lein test runs from the anvil/ project subdirectory. The repo's
;; doc-path lint resolves PNG references in tour.md against
;; docs/anvil-ui/tour/, so artifacts have to land at the repo root,
;; not under anvil/docs/.
(def ^:private tour-dir "../docs/anvil-ui/tour")

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- seed-demo-data! []
  ;; Three jobs with histories so the dashboard / jobs page have
  ;; something interesting to show.
  (doseq [name ["build-api"
                "build-ui"
                "build-db-migration"]]
    (jobs/register-job!
     {:name name
      :jenkinsfile-source
      (str "pipeline { agent any\n"
           "  parameters {\n"
           "    string(name: 'BRANCH', defaultValue: 'main', description: 'git branch')\n"
           "    choice(name: 'ENV', choices: ['dev', 'stg', 'prod'])\n"
           "    booleanParam(name: 'CLEAN', defaultValue: false, description: 'wipe ws')\n"
           "  }\n"
           "  stages {\n"
           "    stage('compile') { steps { sh 'make' } }\n"
           "    stage('test') { steps { sh 'make test' } }\n"
           "  }\n"
           "}")
      :buildable? true
      :max-concurrent-builds 2}))
  ;; Sprinkle some builds across them — success / failure / running mix.
  (doseq [[job result] [["build-api" :success]
                        ["build-api" :success]
                        ["build-api" :failure]
                        ["build-api" :success]
                        ["build-ui" :success]
                        ["build-ui" :failure]
                        ["build-db-migration" :success]]]
    (let [n (jobs/record-build-start! job {})]
      (jobs/record-build-end! job n
                              {:result result
                               :effects [[:agent/stage-enter {:stage "compile"}]
                                         [:sh {:cmd "make" :exit (if (= result :success) 0 2)}]
                                         [:agent/stage-leave {:stage "compile"}]]
                               :log-path nil})))
  ;; Leave one running for the dashboard's "Running" card to be non-zero.
  (jobs/record-build-start! "build-ui" {}))

(declare write-receipt-now!)

(defn- fixture [t]
  (let [port (free-port)]
    (server/start! {:port port :host "127.0.0.1"})
    (bus/unsubscribe-all!)
    (seed-demo-data!)
    (.mkdirs (io/file tour-dir))
    (let [driver (e/firefox {:headless true
                             :args ["--width=1280" "--height=900"]})]
      (try
        (binding [*driver* driver
                  *base-url* (str "http://127.0.0.1:" port)]
          (t))
        (finally
          (try (e/quit driver) (catch Exception _ nil))
          ;; Write the receipt AFTER every deftest has settled. Doing
          ;; this as a 9th deftest races with parallel execution and
          ;; ends up serializing only the first 4 records.
          (write-receipt-now!)
          (server/stop!))))))

(use-fixtures :once fixture)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- snap! [filename]
  (let [path (str tour-dir "/" filename)]
    (e/screenshot *driver* path)
    path))

(defn- time-load!
  "Navigate to `path`, measure wall-clock until the URL changes (a
   crude first-paint-ish proxy — for honest perf use bench-ui.bb's
   server-side TTFB)."
  [path]
  (let [t0 (System/nanoTime)]
    (e/go *driver* (str *base-url* path))
    (Thread/sleep 100)  ; small settle for SSE attach + initial swap
    (/ (- (System/nanoTime) t0) 1e6)))

;; ---------------------------------------------------------------------------
;; The actual tour
;; ---------------------------------------------------------------------------

(def perf-receipt (atom {:run-at (str (Instant/now))
                         :viewport "1280x900"
                         :browser  "firefox-headless"
                         :pages    {}}))

(defn- record! [page-key path ms screenshot]
  ;; Strip the ../ from the screenshot path so the EDN looks
  ;; repo-root-relative (matches tour.md's link form).
  (let [pretty (str/replace screenshot #"^\.\./" "")]
    (swap! perf-receipt assoc-in [:pages page-key]
           {:path path
            :load-ms (Math/round (double ms))
            :screenshot pretty})))

(deftest ^:tour-capture capture-dashboard
  (let [ms (time-load! "/")
        png (snap! "01-dashboard.png")]
    (record! :dashboard "/" ms png)
    (is (e/exists? *driver* {:id "dashboard-stats"}))))

(deftest ^:tour-capture capture-jobs-list
  (let [ms (time-load! "/jobs")
        png (snap! "02-jobs-list.png")]
    (record! :jobs "/jobs" ms png)
    (is (e/exists? *driver* {:css "table"}))))

(deftest ^:tour-capture capture-job-page
  (let [ms (time-load! "/jobs/build-api")
        png (snap! "03-job-page.png")]
    (record! :job-detail "/jobs/build-api" ms png)
    (is (e/exists? *driver* {:css "div.builds-table-frame"}))))

(deftest ^:tour-capture capture-build-page
  (let [ms (time-load! "/jobs/build-api/3")
        png (snap! "04-build-page.png")]
    (record! :build-detail "/jobs/build-api/3" ms png)
    (is (e/exists? *driver* {:css "details.stage-fold"}))))

(deftest ^:tour-capture capture-console
  (let [ms (time-load! "/jobs/build-api/3/console")
        png (snap! "05-console.png")]
    (record! :console "/jobs/build-api/3/console" ms png)
    (is (e/exists? *driver* {:css "details.stage-fold"}))))

(deftest ^:tour-capture capture-build-form
  (let [ms (time-load! "/jobs/build-api/build-form")
        png (snap! "06-build-form.png")]
    (record! :build-form "/jobs/build-api/build-form" ms png)
    (is (e/exists? *driver* {:id "build-form"}))))

(deftest ^:tour-capture capture-queue
  (let [ms (time-load! "/queue")
        png (snap! "07-queue.png")]
    (record! :queue "/queue" ms png)
    (is (e/exists? *driver* {:id "queue-frame"}))))

(deftest ^:tour-capture capture-executors
  (let [ms (time-load! "/executors")
        png (snap! "08-executors.png")]
    (record! :executors "/executors" ms png)))

(defn write-receipt-now!
  "Called from the fixture's teardown after every capture-* deftest
   has completed. Writing the receipt as a deftest itself raced
   with clojure.test's parallel execution and dropped half the
   entries — the fixture path runs strictly after all tests."
  []
  (let [edn (str tour-dir "/perf-receipt.edn")]
    (with-open [w (io/writer edn)]
      (binding [*out* w]
        (pp/pprint @perf-receipt)))))
