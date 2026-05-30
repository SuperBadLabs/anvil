(ns anvil.web.tu3-pages-test
  "Route-level smokes for TU3 — build/compare/artifacts pages + retry
   POST. Each exercises the HTML structure that the etaoin browser
   tests + a future Codex reviewer will read on."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "anvil-tu3-test"
                                      (into-array FileAttribute []))))

(defn- register-build!
  "Register a job + insert a synthetic build with full effects /
   parameters / log-path. Returns {:job ... :n ...}."
  [{:keys [job-name effects parameters log-content workspace-files result]
    :or {result :success}}]
  (jobs/register-job! {:name job-name
                       :jenkinsfile-source "pipeline { agent any }"
                       :buildable? true
                       :max-concurrent-builds 1})
  (let [n (jobs/record-build-start! job-name {:parameters parameters})
        d (tmp-dir)
        ws (io/file d (str n))
        logs (io/file d "logs")
        log-file (io/file logs (str n ".log"))]
    (.mkdirs ws)
    (.mkdirs logs)
    (when log-content (spit log-file log-content))
    (doseq [[rel content] workspace-files]
      (let [f (io/file ws rel)]
        (.mkdirs (.getParentFile f))
        (spit f content)))
    (jobs/record-build-end! job-name n
                            {:result result
                             :effects effects
                             :log-path (.getAbsolutePath log-file)})
    {:job-name job-name :n n :workspace ws}))

(use-fixtures :each (fn [t] (t)))

;; ===========================================================================
;; TU3.1 + TU3.2 — job page + build page render
;; ===========================================================================

(deftest job-page-renders-builds-table-fragment-with-live-attrs
  (register-build! {:job-name "tu3-job-a"
                    :effects []
                    :log-content ""})
  (let [h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/tu3-job-a"})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (testing "builds-table is wired for htmx-sse refresh"
      (is (str/includes? body "hx-ext=\"sse\""))
      (is (str/includes? body "sse-connect"))
      (is (str/includes? body "topics=job:tu3-job-a"))
      (is (str/includes? body "hx-trigger=\"sse:build-started, sse:build-done\"")))
    (testing "sparkline rendered"
      (is (str/includes? body "job-sparkline")))))

(deftest build-page-renders-stage-timeline
  (register-build!
   {:job-name "tu3-stages"
    :effects [[:agent/stage-enter {:stage "Build"}]
              [:sh {:cmd "make" :exit 0}]
              [:agent/stage-leave {:stage "Build"}]
              [:agent/stage-enter {:stage "Deploy"}]
              [:sh {:cmd "scp .." :exit 1}]
              [:agent/stage-leave {:stage "Deploy"}]]
    :log-content ""})
  (let [h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/tu3-stages/1"})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (is (str/includes? body "Build"))
    (is (str/includes? body "Deploy"))
    (is (str/includes? body "make"))
    (is (str/includes? body "exit 1"))
    (testing "toolbar links present"
      (is (str/includes? body "/jobs/tu3-stages/1/console"))
      (is (str/includes? body "/jobs/tu3-stages/1/artifacts"))
      (is (str/includes? body "/jobs/tu3-stages/1/retry"))
      (is (str/includes? body "btn-retry")))))

;; ===========================================================================
;; TU3.3 — retry POST
;; ===========================================================================

(deftest retry-post-enqueues-new-build-with-same-params
  (register-build! {:job-name "tu3-retry"
                    :parameters {"FOO" "bar"}
                    :effects []})
  (let [before (count (queue/queue-snapshot))
        h (routes/make-handler)
        resp (h {:request-method :post
                 :uri "/jobs/tu3-retry/1/retry"
                 :headers {"hx-request" "true"}})]
    (is (= 200 (:status resp)))
    (is (str/includes? (get-in resp [:headers "HX-Redirect"]) "/jobs/tu3-retry"))
    (let [after-snap (queue/queue-snapshot)
          new-items (filter #(= "tu3-retry" (:job-name %)) after-snap)]
      (is (= (inc before) (count after-snap))
          "one new queue item")
      (is (= {"FOO" "bar"} (-> new-items last :parameters))
          "parameters carried over")
      ;; Drain so other tests don't see this item.
      (queue/cancel! (-> after-snap last :queue-id)))))

(deftest retry-non-htmx-returns-303-redirect
  (register-build! {:job-name "tu3-retry-303"
                    :parameters {}
                    :effects []})
  (let [h (routes/make-handler)
        resp (h {:request-method :post :uri "/jobs/tu3-retry-303/1/retry"})]
    (is (= 303 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) "/jobs/tu3-retry-303"))))

(deftest retry-unknown-build-404s
  (let [h (routes/make-handler)
        resp (h {:request-method :post :uri "/jobs/never-existed/99/retry"})]
    (is (= 404 (:status resp)))))

;; ===========================================================================
;; TU3.4 — compare
;; ===========================================================================

(deftest compare-page-renders-two-builds-side-by-side
  (register-build!
   {:job-name "tu3-cmp"
    :effects [[:agent/stage-enter {:stage "Build"}]
              [:sh {:cmd "make" :exit 0}]
              [:agent/stage-leave {:stage "Build"}]]
    :result :success})
  (register-build!
   {:job-name "tu3-cmp"
    :effects [[:agent/stage-enter {:stage "Build"}]
              [:sh {:cmd "make" :exit 1}]
              [:agent/stage-leave {:stage "Build"}]]
    :result :failure})
  (let [h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/tu3-cmp/2/compare"
                 :query-string "vs=1"})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (is (str/includes? body "compare-grid"))
    (is (str/includes? body "#1"))
    (is (str/includes? body "#2"))
    (is (str/includes? body "Build"))))

(deftest compare-page-defaults-to-last-successful
  (register-build! {:job-name "tu3-cmp-default" :effects [] :result :success})
  (register-build! {:job-name "tu3-cmp-default" :effects [] :result :failure})
  (let [h (routes/make-handler)
        resp (h {:request-method :get :uri "/jobs/tu3-cmp-default/2/compare"})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (is (str/includes? body "#1") "should default-pick build #1 (last-successful)")))

;; ===========================================================================
;; TU3.5 — artifacts list + permalink download
;; ===========================================================================

(deftest artifacts-page-lists-matching-files
  (let [{:keys [job-name n]} (register-build!
                              {:job-name "tu3-arts"
                               :effects [[:archive {:artifacts "*.jar"}]]
                               :workspace-files [["app.jar" "JARBYTES"]
                                                 ["readme.txt" "docs"]]})
        h (routes/make-handler)
        resp (h {:request-method :get :uri (str "/jobs/" job-name "/" n "/artifacts")})
        body (str (:body resp))]
    (is (= 200 (:status resp)))
    (is (str/includes? body "app.jar"))
    (is (not (str/includes? body "readme.txt"))
        "readme.txt does NOT match *.jar glob — must not appear")
    (is (str/includes? body (str "/jobs/" job-name "/" n "/artifact/app.jar")))))

(deftest artifact-download-serves-bytes
  (let [{:keys [job-name n]} (register-build!
                              {:job-name "tu3-arts-dl"
                               :effects [[:archive {:artifacts "*.jar"}]]
                               :workspace-files [["app.jar" "BYTES"]]})
        h (routes/make-handler)
        resp (h {:request-method :get
                 :uri (str "/jobs/" job-name "/" n "/artifact/app.jar")})]
    (is (= 200 (:status resp)))
    (is (= "application/java-archive" (get-in resp [:headers "Content-Type"])))
    (is (str/includes? (get-in resp [:headers "Content-Disposition"]) "app.jar"))
    (let [body-str (slurp (:body resp))]
      (is (= "BYTES" body-str)))))

(deftest artifact-download-blocks-path-traversal
  (let [{:keys [job-name n]} (register-build!
                              {:job-name "tu3-traversal"
                               :effects [[:archive {:artifacts "*.jar"}]]
                               :workspace-files [["app.jar" "OK"]]})
        h (routes/make-handler)
        resp (h {:request-method :get
                 :uri (str "/jobs/" job-name "/" n "/artifact/../../etc/passwd")})]
    (is (= 404 (:status resp))
        "path-traversal attempt must return 404, not the host file")))
