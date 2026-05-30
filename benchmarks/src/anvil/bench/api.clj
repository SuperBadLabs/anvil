(ns anvil.bench.api
  "Bench the Jenkins REST shim's response times.

   Runs the handler directly (no HTTP socket) — measuring the inner
   loop without networking noise. A separate Babashka script
   (`benchmarks/scripts/jenkins-compare.bb`) drives the comparison
   against a real Jenkins instance over HTTP, which folds in network
   + container costs both products incur."
  (:require [anvil.web.routes :as routes]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.bench.measure :as m]
            [clojure.string :as str]))

(defn- mk-req
  ([method uri] (mk-req method uri {}))
  ([method uri {:keys [query-params form-params]}]
   {:request-method method
    :uri uri
    :scheme :http
    :headers {"host" "anvil.bench"}
    :query-params (or query-params {})
    :form-params (or form-params {})}))

(defn- setup-jobs! []
  (jobs/clear!)
  (jobs/register-job!
   {:name "bench-simple"
    :jenkinsfile-source "pipeline { agent any; stages { stage('S') {
                          steps { echo 'hi' } } } }"})
  (jobs/register-job!
   {:name "bench-multi"
    :jenkinsfile-source "pipeline { agent any; stages {
                          stage('A') { steps { sh 'a1'; sh 'a2' } }
                          stage('B') { steps { sh 'b1'; sh 'b2'; sh 'b3' } }
                          stage('C') { steps { sh 'c1' } }
                        } }"}))

(def ^:private bench-points
  "Endpoints we measure. Each entry is [label make-req-fn]."
  [[:root             #(mk-req :get  "/jenkins/api/json")]
   [:crumb            #(mk-req :get  "/jenkins/crumbIssuer/api/json")]
   [:queue            #(mk-req :get  "/jenkins/queue/api/json")]
   [:job-summary      #(mk-req :get  "/jenkins/job/bench-simple/api/json")]
   [:job-not-found    #(mk-req :get  "/jenkins/job/no-such/api/json")]
   [:create-item-501  #(mk-req :post "/jenkins/createItem")]
   [:status-html      #(mk-req :get  "/")]
   [:status-json      #(mk-req :get  "/api/status")]])

(defn run
  ([] (run {}))
  ([{:keys [iterations warmup] :or {iterations 200 warmup 20} :as opts}]
   (setup-jobs!)
   (let [handler (routes/make-handler)
         per-endpoint
         (mapv (fn [[label mk]]
                 {:label label
                  :summary (m/measure opts #(handler (mk)))})
               bench-points)]
     {:bench :api
      :iterations iterations
      :warmup warmup
      :per-endpoint per-endpoint})))

(defn print-report [{:keys [per-endpoint]}]
  (println)
  (println "  ── REST API benchmark ────────────────────────────────────────────────────")
  (println (format "    %-20s %s" "ENDPOINT" "TIMINGS"))
  (doseq [{:keys [label summary]} per-endpoint]
    (println (format "    %-20s %s" (name label) (m/format-summary summary)))))
