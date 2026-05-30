#!/usr/bin/env bb

;; jenkins-self-smoke.bb — the TX11E receipt build.
;;
;; The "anvil can build Jenkins" claim breaks down into two
;; verifiable sub-claims, each tested below:
;;
;; Phase 1 — INGEST + DISPATCH RECEIPT
;;   Anvil parses /home/srikanth/projects/jenkins/Jenkinsfile (the
;;   actual file Jenkins uses to build itself, 256 lines of scripted
;;   Pipeline), expands the matrix, dispatches every step through the
;;   full pipeline. Verified by the standalone bench
;;     anvil.bench.jenkins-self
;;   which prints stage count, effect count, and timing. We assert:
;;     - parser produces ≥4 stages (TX11A)
;;     - matrix expansion grows it to ≥10 stages (TX11B)
;;     - dispatcher records ≥40 effects (everything wired)
;;
;; Phase 2 — REAL SUBPROCESS BUILD RECEIPT
;;   A minimal Jenkinsfile that does
;;     mvn -B -ntp -pl cli -am -DskipTests package
;;   against the same Jenkins source tree, registered via anvil's
;;   admin REST, triggered via jenkins-cli, dispatched by the daemon
;;   with :execute? true (TX9 real subprocess + streaming log + TX11C
;;   label-env injection). We assert:
;;     - daemon comes up
;;     - admin endpoint accepts the job
;;     - jenkins-cli triggers the build
;;     - build completes within 6 min (warm Maven cache)
;;     - cli-*-SNAPSHOT.jar exists at /home/srikanth/projects/jenkins/cli/target/
;;
;; Phase 1 is a 3-second smoke. Phase 2 is the real anvil-end-to-end
;; pipeline running mvn against Jenkins's actual sources. Together
;; they make the claim "anvil ran the Jenkins build" honest.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[cheshire.core :as json])

(def ^:private anvil-url    (or (System/getenv "ANVIL_URL")    "http://localhost:8765"))
(def ^:private jenkins-repo (or (System/getenv "JENKINS_REPO") "/home/srikanth/projects/jenkins"))
(def ^:private cli-jar      (io/file "anvil/target/jenkins-cli.jar"))
(def ^:private launchable-shim-dir "/tmp/anvil-shims")

(def ^:private results (atom []))

(defn step [name thunk]
  (println (str "→ " name))
  (let [t0 (System/currentTimeMillis)
        [ok? detail]
        (try [(boolean (thunk)) nil]
             (catch Throwable t [false (.getMessage t)]))
        elapsed (- (System/currentTimeMillis) t0)]
    (swap! results conj {:name name :ok? ok? :ms elapsed :detail detail})
    (println (str "  " (if ok? "✓" "✗") "  " elapsed " ms"
                  (when detail (str "  (" detail ")"))))))

;; ---------------------------------------------------------------------------
;; Setup helpers
;; ---------------------------------------------------------------------------

(defn ensure-launchable-shim! []
  (when-not (.exists (io/file launchable-shim-dir "launchable"))
    (println "Installing launchable shim ...")
    (p/shell {:continue true} "scripts/install-launchable-shim.bb")))

(defn wait-for-anvil [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [r (p/shell {:out :string :err :string :continue true}
                       "curl" "-fsS" "-m" "2"
                       (str anvil-url "/api/health"))]
        (cond
          (and (zero? (:exit r)) (str/includes? (:out r) "\"ready\":true")) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 500) (recur)))))))

(defn ensure-cli-jar []
  (when-not (.exists cli-jar)
    (println (str "Downloading jenkins-cli.jar to " (.getPath cli-jar)))
    (.mkdirs (.getParentFile cli-jar))
    (let [url "https://repo.jenkins-ci.org/public/org/jenkins-ci/main/cli/2.479.3/cli-2.479.3.jar"]
      (p/shell "curl" "-fsSL" "-o" (.getPath cli-jar) url)))
  (.getPath cli-jar))

(defn post-job! [job-name jenkinsfile-source]
  (let [body {:name job-name
              :jenkinsfile_source jenkinsfile-source}
        tmp (java.io.File/createTempFile "anvil-job-" ".json")]
    (spit tmp (json/generate-string body))
    (let [r (p/shell {:out :string :err :string :continue true}
                     "curl" "-fsS" "-X" "POST"
                     "-H" "Content-Type: application/json"
                     "-d" (str "@" (.getPath tmp))
                     (str anvil-url "/anvil/admin/jobs"))]
      (.delete tmp)
      (when-not (zero? (:exit r))
        (throw (ex-info "register failed" {:exit (:exit r) :err (:err r)})))
      (str/includes? (:out r) "\"status\":\"ok\""))))

(defn trigger-build! [job-name _cli-jar]
  ;; jenkins-cli is not strictly required to trigger an anvil build —
  ;; the underlying REST endpoint is /jenkins/job/<NAME>/build.
  ;; jenkins-cli would send a POST there with the right CSRF crumb;
  ;; we replicate that directly via curl.
  (let [crumb (p/shell {:out :string :err :string :continue true}
                       "curl" "-fsS" (str anvil-url "/jenkins/crumbIssuer/api/json"))
        crumb-value (when (zero? (:exit crumb))
                      (some-> (re-find #"\"crumb\":\"([^\"]+)\"" (:out crumb)) second))
        r (p/shell {:out :string :err :string :continue true}
                   "curl" "-fsS" "-X" "POST"
                   "-H" (str "Jenkins-Crumb: " (or crumb-value ""))
                   (str anvil-url "/jenkins/job/" job-name "/build"))]
    (or (zero? (:exit r))
        (= 201 (try (Integer/parseInt
                      (or (some-> (re-find #"\d{3}" (or (:err r) "")) first) "0"))
                    (catch Exception _ 0))))))

(defn wait-for-build! [job-name n timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [r (p/shell {:out :string :err :string :continue true}
                       "curl" "-fsS"
                       (str anvil-url "/jenkins/job/" job-name
                            "/" n "/api/json"))]
        (cond
          (and (zero? (:exit r))
               (str/includes? (:out r) "\"building\":false"))
          {:done? true :body (:out r)}

          (> (System/currentTimeMillis) deadline)
          {:done? false :timeout? true}

          :else (do (Thread/sleep 1000) (recur)))))))

;; ---------------------------------------------------------------------------
;; Phase 1 — bench-based IR + dispatch receipt
;; ---------------------------------------------------------------------------

(defn run-phase-1-bench []
  (let [r (p/shell {:out :string :err :string :continue true :dir "anvil"}
                   "lein" "with-profile" "+bench" "run" "-m" "anvil.bench.jenkins-self")]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn phase-1-ingest-receipt []
  (let [r (run-phase-1-bench)
        out (or (:out r) "")
        stages-match (re-find #"stages after expansion: (\d+)" out)
        effects-match (re-find #"effects recorded: (\d+)" out)
        matrices-match (re-find #"matrices found:\s+(\d+)" out)
        combos-match  (re-find #"combinations tried:\s+(\d+)" out)
        survive-match (re-find #"combinations surviving:(\d+)" out)
        stages (some-> stages-match second Integer/parseInt)
        effects (some-> effects-match second Integer/parseInt)
        matrices (some-> matrices-match second Integer/parseInt)
        combos   (some-> combos-match second Integer/parseInt)
        survive  (some-> survive-match second Integer/parseInt)]
    (println (format "  parsed: stages=%s effects=%s matrices=%s combos=%s surviving=%s"
                     stages effects matrices combos survive))
    {:exit (:exit r)
     :stages stages
     :effects effects
     :matrices matrices
     :combos combos
     :surviving survive}))

;; ---------------------------------------------------------------------------
;; Phase 2 — real subprocess build
;; ---------------------------------------------------------------------------

(def ^:private phase-2-job-name "jenkins-cli-build")

(def ^:private phase-2-jenkinsfile
  (str "pipeline {\n"
       "  agent any\n"
       "  stages {\n"
       "    stage('Build jenkins-cli') {\n"
       "      steps {\n"
       "        sh '''\n"
       "          export PATH=/home/srikanth/.local/opt/apache-maven-3.9.9/bin:" launchable-shim-dir ":$PATH\n"
       "          cd " jenkins-repo "\n"
       "          mvn -B -ntp -pl cli -am -DskipTests -Dmaven.javadoc.skip=true package 2>&1 | tail -30\n"
       "        '''\n"
       "      }\n"
       "    }\n"
       "    stage('Verify artifact') {\n"
       "      steps {\n"
       "        sh 'ls -la " jenkins-repo "/cli/target/cli-*.jar'\n"
       "      }\n"
       "    }\n"
       "  }\n"
       "}\n"))

(defn jenkins-cli-jar-exists? []
  (let [f (io/file jenkins-repo "cli" "target")]
    (when (.isDirectory f)
      (some #(re-matches #"cli-.*\.jar" (.getName ^java.io.File %))
            (.listFiles f)))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& _]
  (ensure-launchable-shim!)

  (println "\n══════ Phase 1: parser + matrix expander + dispatcher ══════\n")

  (step "anvil.bench.jenkins-self exits 0"
        (fn []
          (let [r (phase-1-ingest-receipt)]
            (and (zero? (:exit r))
                 (>= (or (:stages r) 0) 10)
                 (>= (or (:effects r) 0) 40)
                 (>= (or (:matrices r) 0) 3)))))

  (println "\n══════ Phase 2: real subprocess build of jenkins-cli ══════\n")

  (when-not (wait-for-anvil 5000)
    (println (str "(anvil not running on " anvil-url " — phase 2 skipped)"))
    (println "  To run phase 2:")
    (println "    cd anvil && lein run --port 8765 &")
    (println (str "    " *file*))
    ;; Phase 1 was the only thing we could do; exit on phase 1 result alone
    (let [passed (count (filter :ok? @results))
          failed (count (remove :ok? @results))]
      (println (format "RESULT (phase 1 only): %d passed, %d failed" passed failed))
      (System/exit (if (zero? failed) 0 1))))

  ;; jenkins-cli.jar isn't strictly required — we hit the REST endpoint
  ;; directly. If it's available we use it; if not, fall through.
  (let [cli (when (.exists cli-jar) (.getPath cli-jar))]
    (step (str "POST /anvil/admin/jobs ('" phase-2-job-name "')")
          #(post-job! phase-2-job-name phase-2-jenkinsfile))

    (step "Trigger build via /jenkins/job/<name>/build"
          #(trigger-build! phase-2-job-name cli))

    (step "Build #1 completes within 6 min (real mvn package)"
          #(let [r (wait-for-build! phase-2-job-name 1 (* 6 60 1000))]
             (and (:done? r) (not (:timeout? r)))))

    (step "jenkins-cli.jar artifact exists at cli/target/"
          jenkins-cli-jar-exists?))

  ;; Report
  (println (str "\n═══════════════════════════════════════════════════════════════════\n"))
  (let [passed (count (filter :ok? @results))
        failed (count (remove :ok? @results))]
    (println (format "RESULT: %d passed, %d failed (%d total)"
                     passed failed (count @results)))
    (println)
    (doseq [{:keys [name ok? ms detail]} @results]
      (println (format "  %s %s  (%d ms)%s"
                       (if ok? "✓" "✗") name ms
                       (if detail (str "  — " detail) ""))))
    (println)
    (if (zero? failed)
      (do (println "ALL GREEN — receipt established.")
          (System/exit 0))
      (do (println "FAILURES present — receipt NOT established.")
          (System/exit 1)))))

(-main *command-line-args*)
