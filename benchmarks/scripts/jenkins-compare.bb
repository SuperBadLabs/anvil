#!/usr/bin/env bb

;; jenkins-compare.bb — drive the "anvil vs Jenkins" REST-shim
;; comparison the README documents. v1 runs entirely outside of the
;; anvil JVM; both products are timed via HTTP.
;;
;; Approach:
;;   1. Spin up a Jenkins LTS in Docker on :8080 with a pre-seeded job.
;;   2. Spin up anvil on :8765 (lein run) with the same Jenkinsfile.
;;   3. For each endpoint, time N HTTP requests against each.
;;   4. Print min/p50/p95/p99 side-by-side.
;;
;; What the comparison is and isn't:
;;
;;   FAIR:
;;     - GET /api/json response time (both serve JSON, no real work)
;;     - GET /job/<n>/api/json response time
;;     - GET /queue/api/json response time
;;     - GET /consoleText for a completed build
;;
;;   UNFAIR (today):
;;     - POST /build wall-clock to build completion
;;       Jenkins: real subprocess execution
;;       Anvil:   records effects (no real exec until TX9)
;;       Skip these comparisons until TX9 wires real execution.
;;
;; Usage:
;;   ./jenkins-compare.bb         (uses defaults — needs Docker + lein)
;;   ./jenkins-compare.bb --iterations 200 --skip-jenkins
;;
;; This is a scaffolding script. It will refuse to claim a "win" until
;; real subprocess execution lands; for now it produces measurements
;; the README explicitly contextualises.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.tools.cli :as cli]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp])

(def cli-options
  [["-i" "--iterations N" "HTTP requests per endpoint" :default 100 :parse-fn #(Integer/parseInt %)]
   [nil  "--skip-jenkins"   "Skip Docker Jenkins, time anvil only"]
   [nil  "--skip-anvil"     "Skip anvil, time Jenkins only (you bring the Jenkins)"]
   [nil  "--anvil-url URL"  "Where anvil is listening" :default "http://localhost:8765"]
   [nil  "--jenkins-url URL" "Where Jenkins is listening" :default "http://localhost:8080"]
   [nil  "--jenkins-image IMG" "Jenkins Docker image" :default "jenkins/jenkins:lts-jdk21"]
   ["-h" "--help" "Print this message"]])

(defn die [msg & {:keys [exit] :or {exit 2}}]
  (binding [*out* *err*] (println "ERROR:" msg))
  (System/exit exit))

(defn- nanos [] (System/nanoTime))

(defn- percentile [sorted p]
  (let [n (count sorted)
        i (max 0 (min (dec n) (int (Math/floor (* p (dec n))))))]
    (nth sorted i)))

(defn- time-curl
  "Time `iterations` curl GETs against `url`. Returns the latency
   distribution in ms. Uses -o /dev/null so the body isn't slurped."
  [url iterations]
  (let [latencies
        (vec
         (for [_ (range iterations)]
           (let [t0 (nanos)]
             (p/shell {:out :string :err :string :continue true}
                      "curl" "-s" "-o" "/dev/null" "-m" "10" url)
             (/ (- (nanos) t0) 1000000.0))))]
    (let [sorted (sort latencies)
          n (count sorted)
          total (reduce + sorted)]
      {:url url
       :n n
       :min (first sorted)
       :p50 (percentile sorted 0.5)
       :p95 (percentile sorted 0.95)
       :p99 (percentile sorted 0.99)
       :max (last sorted)
       :mean (/ total n)})))

(defn- summary-row [label dist]
  (format "  %-26s n=%-4d min=%6.2f  p50=%6.2f  p95=%7.2f  p99=%7.2f  max=%7.2f  mean=%6.2f ms"
          label (:n dist) (:min dist) (:p50 dist) (:p95 dist) (:p99 dist) (:max dist) (:mean dist)))

(defn- start-jenkins! [image]
  (println (str "Starting Jenkins (" image ") on :8080 …"))
  (p/shell {:out :inherit :err :inherit :continue true}
           "docker" "run" "-d" "--rm" "--name" "anvil-bench-jenkins"
           "-p" "8080:8080" "-p" "50000:50000"
           "-e" "JAVA_OPTS=-Djenkins.install.runSetupWizard=false -Xmx512m"
           image)
  (println "Waiting for Jenkins to come up …")
  (let [deadline (+ (System/currentTimeMillis) 90000)]
    (loop []
      (let [r (p/shell {:out :string :err :string :continue true}
                       "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}"
                       "-m" "2" "http://localhost:8080/api/json")]
        (cond
          (= "200" (str/trim (:out r))) :ready
          (> (System/currentTimeMillis) deadline) (die "Jenkins didn't come up in 90s")
          :else (do (Thread/sleep 1500) (recur)))))))

(defn- stop-jenkins! []
  (p/shell {:out :inherit :err :string :continue true}
           "docker" "kill" "anvil-bench-jenkins"))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (when (seq errors)
      (doseq [e errors] (println "ERROR:" e))
      (die summary))
    (when (:help options) (println summary) (System/exit 0))

    (let [{:keys [iterations skip-jenkins skip-anvil anvil-url jenkins-url jenkins-image]} options
          endpoints
          (cond->> [["/api/json"               :root]
                    ["/crumbIssuer/api/json"   :crumb]
                    ["/queue/api/json"         :queue]]
            true vec)
          results (atom {})]

      (when-not skip-jenkins
        (start-jenkins! jenkins-image))

      (try
        (doseq [[path label] endpoints]
          (when-not skip-anvil
            (let [dist (time-curl (str anvil-url "/jenkins" path) iterations)]
              (println (summary-row (str "anvil   " label) dist))
              (swap! results assoc-in [:anvil label] dist)))
          (when-not skip-jenkins
            (let [dist (time-curl (str jenkins-url path) iterations)]
              (println (summary-row (str "jenkins " label) dist))
              (swap! results assoc-in [:jenkins label] dist))))

        (when (and (not skip-jenkins) (not skip-anvil))
          (println)
          (println "── Comparison (median, ms) ──")
          (doseq [[_ label] endpoints]
            (let [a (get-in @results [:anvil label :p50])
                  j (get-in @results [:jenkins label :p50])
                  ratio (when (and a j (pos? a)) (/ j a))]
              (println (format "  %-12s anvil=%6.2f  jenkins=%6.2f  ratio=%s"
                               (name label)
                               (or a Double/NaN) (or j Double/NaN)
                               (if ratio (format "%.1fx" ratio) "—"))))))

        (finally
          (when-not skip-jenkins (stop-jenkins!))))

      (let [out (str "benchmarks/results/jenkins-compare-"
                     (.toEpochMilli (java.time.Instant/now))
                     ".edn")]
        (spit out (with-out-str (pp/pprint @results)))
        (println (str "\nRaw results → " out))))))

(apply -main *command-line-args*)
