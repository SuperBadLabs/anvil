#!/usr/bin/env bb

;; bench-vs-jenkins.bb — apples-to-apples comparison of anvil vs
;; a real Jenkins LTS running in Docker. Two measurement axes:
;;
;;   A. REST API latency  (in-JVM HTTP — no curl startup overhead)
;;   B. Build wall-clock  (trigger → build done)
;;
;; Uses babashka.http-client so each request is a true HTTP round-trip
;; without the per-call curl-process startup cost (~2-3ms otherwise).
;;
;; Assumes both servers are already up:
;;   Jenkins: http://localhost:8090   (anonymous, setup wizard skipped)
;;   Anvil:   http://localhost:8765
;;
;; Usage:
;;   ./bench-vs-jenkins.bb                    # 200 iters per endpoint, 5 builds
;;   ./bench-vs-jenkins.bb --iters 500 --builds 10
;;   ./bench-vs-jenkins.bb --rest-only        # skip the build phase

(require '[babashka.http-client :as http]
         '[clojure.string :as str]
         '[clojure.tools.cli :as cli]
         '[cheshire.core :as json])

(def cli-options
  [[nil "--iters N"   "REST iterations per endpoint" :default 200 :parse-fn #(Integer/parseInt %)]
   [nil "--builds N"  "Number of builds to time"      :default 5   :parse-fn #(Integer/parseInt %)]
   [nil "--jenkins-url URL" "" :default "http://localhost:8090"]
   [nil "--anvil-url URL"   "" :default "http://localhost:8765"]
   [nil "--rest-only"       "Skip the build wall-clock phase"]
   [nil "--build-only"      "Skip the REST phase"]
   ["-h" "--help"]])

;; ---------------------------------------------------------------------------
;; Timing
;; ---------------------------------------------------------------------------

(defn- now-ns [] (System/nanoTime))
(defn- ms [ns] (/ (double ns) 1e6))

(defn- pct [sorted p]
  (let [n (count sorted)]
    (if (zero? n) 0.0
        (nth sorted (max 0 (min (dec n) (int (Math/floor (* p (dec n))))))))))

(defn- summarize [ns-lats]
  (let [sorted (sort ns-lats)
        n (count sorted)
        total (reduce + 0 sorted)]
    {:n n
     :min  (ms (first sorted))
     :p50  (ms (pct sorted 0.50))
     :p95  (ms (pct sorted 0.95))
     :p99  (ms (pct sorted 0.99))
     :max  (ms (last sorted))
     :mean (ms (if (pos? n) (/ total n) 0))}))

(defn- time-http
  "GET `url` `n` times via babashka.http-client (no curl overhead).
   Returns latency summary in ms. Throws on HTTP errors so we don't
   silently average 404s in."
  [url n]
  ;; Warm-up — first few requests build up the connection pool / TLS state.
  (dotimes [_ 5] (http/get url {:throw false}))
  (summarize
   (for [_ (range n)]
     (let [t0 (now-ns)]
       (http/get url {:throw false})
       (- (now-ns) t0)))))

(defn- fmt-row [label dist]
  (format "  %-30s  n=%-4d  min=%6.3f  p50=%6.3f  p95=%6.3f  p99=%6.3f  mean=%6.3f"
          label (:n dist) (:min dist) (:p50 dist) (:p95 dist) (:p99 dist) (:mean dist)))

;; ---------------------------------------------------------------------------
;; Jenkins crumb (must travel on same JSESSIONID as the subsequent POST)
;; ---------------------------------------------------------------------------

(defn- jenkins-crumb-and-cookie [jenkins-url]
  (let [r (http/get (str jenkins-url "/crumbIssuer/api/json")
                    {:throw false})
        crumb (some-> r :body (json/parse-string true) :crumb)
        cookie (some-> r :headers (get "set-cookie")
                       (#(if (vector? %) (first %) %))
                       (str/split #";" 2) first)]
    {:crumb crumb :cookie cookie}))

;; ---------------------------------------------------------------------------
;; Job setup
;; ---------------------------------------------------------------------------

(def ^:private demo-pipeline
  "pipeline {
     agent any
     stages {
       stage('S') {
         steps {
           echo 'hello'
           sh 'echo step-1'
           sh 'echo step-2'
         }
       }
     }
   }")

(def ^:private jenkins-pipeline-config-xml
  (str "<flow-definition plugin=\"workflow-job\">"
       "<actions/>"
       "<description></description>"
       "<keepDependencies>false</keepDependencies>"
       "<properties/>"
       "<definition class=\"org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition\" "
       "            plugin=\"workflow-cps\">"
       "<script>" demo-pipeline "</script>"
       "<sandbox>true</sandbox>"
       "</definition>"
       "<triggers/>"
       "<disableConcurrentBuilds>false</disableConcurrentBuilds>"
       "</flow-definition>"))

(defn- jenkins-create-job! [jenkins-url job-name]
  ;; Delete first so re-runs are idempotent
  (let [{:keys [crumb cookie]} (jenkins-crumb-and-cookie jenkins-url)]
    (http/post (str jenkins-url "/job/" job-name "/doDelete")
               {:throw false
                :headers (cond-> {"Jenkins-Crumb" (or crumb "")}
                           cookie (assoc "Cookie" cookie))})
    (let [{:keys [crumb cookie]} (jenkins-crumb-and-cookie jenkins-url)
          r (http/post (str jenkins-url "/createItem?name=" job-name)
                       {:throw false
                        :headers (cond-> {"Content-Type" "application/xml"
                                          "Jenkins-Crumb" (or crumb "")}
                                   cookie (assoc "Cookie" cookie))
                        :body jenkins-pipeline-config-xml})]
      [(< (:status r) 300) (:status r)])))

(defn- anvil-create-job! [anvil-url job-name]
  (http/delete (str anvil-url "/anvil/admin/jobs/" job-name) {:throw false})
  (let [r (http/post (str anvil-url "/anvil/admin/jobs")
                     {:throw false
                      :headers {"Content-Type" "application/json"}
                      :body (json/generate-string
                             {:name job-name
                              :jenkinsfile_source demo-pipeline})})]
    [(< (:status r) 300) (:status r)]))

;; `prefix` is "" for Jenkins (root-mounted Hudson surface) and "/jenkins"
;; for anvil (its Jenkins-compat shim is mounted under /jenkins/*).
;; Without this, anvil requests 404 and time-build silently times out twice.

(defn- last-build-num [base-url prefix job-name]
  (let [r (http/get (str base-url prefix "/job/" job-name "/api/json") {:throw false})]
    (try (-> r :body (json/parse-string true) :lastBuild :number)
         (catch Exception _ nil))))

(defn- build-done? [base-url prefix job-name n]
  (try
    (let [r (http/get (str base-url prefix "/job/" job-name "/" n "/api/json")
                      {:throw false})
          m (json/parse-string (:body r) true)]
      (and (not (:building m)) (some? (:result m))))
    (catch Exception _ false)))

(defn- trigger! [base-url prefix job-name kind]
  (let [{:keys [crumb cookie]} (when (= kind :jenkins)
                                 (jenkins-crumb-and-cookie base-url))
        r (http/post (str base-url prefix "/job/" job-name "/build")
                     {:throw false
                      :headers (cond-> {}
                                 crumb (assoc "Jenkins-Crumb" crumb)
                                 cookie (assoc "Cookie" cookie))})]
    [(< (:status r) 400) (:status r)]))

(defn- time-build [base-url prefix job-name kind]
  (let [start (now-ns)
        prev-n (or (last-build-num base-url prefix job-name) 0)
        [trig-ok trig-st] (trigger! base-url prefix job-name kind)]
    (when-not trig-ok
      (println (format "    !! trigger failed for %s: HTTP %s" base-url trig-st)))
    ;; Wait until a new lastBuild appears
    (loop [tries 0]
      (let [cur (last-build-num base-url prefix job-name)]
        (cond
          (> tries 600) :timeout
          (or (nil? cur) (= cur prev-n)) (do (Thread/sleep 100) (recur (inc tries)))
          :else cur)))
    (let [target (or (last-build-num base-url prefix job-name) (inc prev-n))
          deadline (+ (System/currentTimeMillis) 60000)]
      (loop []
        (cond
          (build-done? base-url prefix job-name target) :done
          (> (System/currentTimeMillis) deadline) :timeout
          :else (do (Thread/sleep 100) (recur)))))
    (- (now-ns) start)))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [{:keys [options]} (cli/parse-opts args cli-options)
        {:keys [iters builds jenkins-url anvil-url rest-only build-only help]} options]
    (when help (println "see header comment") (System/exit 0))

    (println (str "\n══════════════════════════════════════════════════════════════════════"))
    (println (str "  anvil vs Jenkins LTS — head-to-head bench"))
    (println (str "  Jenkins: " jenkins-url))
    (println (str "  Anvil:   " anvil-url))
    (println (str "══════════════════════════════════════════════════════════════════════\n"))

    (when-not build-only
      (println "── A. REST endpoint latency (in-JVM HTTP, after 5-req warmup) ────────")
      (println (format "  (each row: %d sequential GETs)\n" iters))
      (let [endpoints
            [["root"        "/api/json"             "/jenkins/api/json"]
             ["queue"       "/queue/api/json"       "/jenkins/queue/api/json"]
             ["crumb"       "/crumbIssuer/api/json" "/jenkins/crumbIssuer/api/json"]
             ["computer"    "/computer/api/json"    "/jenkins/computer/api/json"]]]
        (doseq [[label j-path a-path] endpoints]
          (let [j-dist (time-http (str jenkins-url j-path) iters)
                a-dist (time-http (str anvil-url a-path) iters)
                ratio  (when (and (pos? (:p50 a-dist)) (pos? (:p50 j-dist)))
                         (/ (:p50 j-dist) (:p50 a-dist)))]
            (println (fmt-row (str "Jenkins " label) j-dist))
            (println (fmt-row (str "Anvil   " label) a-dist))
            (println (format "                                → anvil is %.1fx %s on p50\n"
                             (or ratio 0.0)
                             (if (and ratio (> ratio 1)) "FASTER" "slower")))))))

    (when-not rest-only
      (println "\n── B. Build wall-clock (3-step pipeline) ─────────────────────────────")
      (println "  Creating bench-job on both servers ...")
      (let [job "bench-job"
            [j-ok j-st] (jenkins-create-job! jenkins-url job)
            [a-ok a-st] (anvil-create-job! anvil-url job)]
        (println (format "    Jenkins: %s (HTTP %s),  Anvil: %s (HTTP %s)" j-ok j-st a-ok a-st))
        (when (and j-ok a-ok)
          (println (format "  Running %d builds on each (sequential)\n" builds))
          (let [j-times (vec (for [i (range builds)]
                               (let [t (time-build jenkins-url "" job :jenkins)]
                                 (println (format "    Jenkins build #%d: %.2f s"
                                                  (inc i) (/ (ms t) 1000)))
                                 t)))
                a-times (vec (for [i (range builds)]
                               (let [t (time-build anvil-url "/jenkins" job :anvil)]
                                 (println (format "    Anvil   build #%d: %.2f s"
                                                  (inc i) (/ (ms t) 1000)))
                                 t)))
                j-sum (summarize j-times)
                a-sum (summarize a-times)]
            (println)
            (println (format "  Jenkins  median=%6.2f s  min=%6.2f  max=%6.2f"
                             (/ (:p50 j-sum) 1000)
                             (/ (:min j-sum) 1000)
                             (/ (:max j-sum) 1000)))
            (println (format "  Anvil    median=%6.2f s  min=%6.2f  max=%6.2f"
                             (/ (:p50 a-sum) 1000)
                             (/ (:min a-sum) 1000)
                             (/ (:max a-sum) 1000)))
            (when (and (pos? (:p50 a-sum)) (pos? (:p50 j-sum)))
              (let [ratio (/ (:p50 j-sum) (:p50 a-sum))]
                (println (format "                          → anvil is %.1fx %s on median wall-clock"
                                 ratio
                                 (if (> ratio 1) "FASTER" "slower")))))))))
    (println (str "\n══════════════════════════════════════════════════════════════════════"))))

(apply -main *command-line-args*)
