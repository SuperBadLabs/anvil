#!/usr/bin/env bb

;; jenkins-cli-fixture.bb — End-to-end integration test that drives
;; the actual jenkins-cli.jar binary against a running anvil.
;;
;; Verifies anvil's REST shim is jenkins-cli compatible at the binary
;; level — what a real Jenkins user would see when they point their
;; existing tools at anvil.
;;
;; Usage:
;;   ./jenkins-cli-fixture.bb                  # auto-starts anvil if not running
;;   ./jenkins-cli-fixture.bb --anvil-url URL  # use an already-running anvil
;;   ./jenkins-cli-fixture.bb --skip-build     # skip the build-and-wait stage
;;
;; CI usage:
;;   GitHub Actions workflow (.github/workflows/jenkins-cli-integration.yml)
;;   calls this with --no-start (anvil started separately).
;;
;; Requirements:
;;   - bb
;;   - lein (when --no-start NOT passed)
;;   - java (for jenkins-cli.jar)
;;   - curl (for direct REST calls)
;;
;; Exits 0 on full pass; non-zero if any step fails.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.tools.cli :as cli]
         '[clojure.java.io :as io])

(def cli-options
  [[nil "--anvil-url URL" "Where anvil is listening" :default "http://localhost:8765"]
   [nil "--no-start" "Don't try to launch anvil (assume it's already running)"]
   [nil "--skip-build" "Skip the build-and-wait step (fast smoke test)"]
   [nil "--cli-url URL" "Override the jenkins-cli.jar download URL"
    :default "https://updates.jenkins.io/download/war/2.426.3/jenkins.war"]
   [nil "--cli-path PATH" "Use a pre-downloaded jenkins-cli.jar"]
   [nil "--skip-cli" "Skip the jenkins-cli.jar binary checks; verify REST + admin only.
                     Useful when CI runs against a jenkins-cli build whose
                     auth/transport doesn't line up with anvil's read-only shim
                     (e.g. 2.479.x defaults to WebSocket which anvil v1 doesn't
                     implement)."]
   ["-h" "--help" "Print usage and exit"]])

(def step-results (atom []))

(defn step
  "Run `thunk`, capture pass/fail, log it. `thunk` returns truthy on pass,
   falsy or throws on fail."
  [name thunk]
  (println (str "→ " name))
  (let [start (System/currentTimeMillis)
        [ok? detail] (try [(boolean (thunk)) nil]
                          (catch Throwable t [false (.getMessage t)]))
        elapsed (- (System/currentTimeMillis) start)]
    (swap! step-results conj {:name name :ok? ok? :elapsed-ms elapsed :detail detail})
    (println (str "  " (if ok? "✓" "✗") "  " elapsed " ms"
                  (when detail (str "  (" detail ")"))))))

;; ---------------------------------------------------------------------------
;; Setup
;; ---------------------------------------------------------------------------

(defn locate-jenkins-cli
  "Resolve a path to jenkins-cli.jar. If --cli-path was supplied, use it.
   Otherwise download from a running anvil's /jenkins/jnlpJars/jenkins-cli.jar
   if available, else from updates.jenkins.io.

   v1 strategy: pull from a known stable URL into target/."
  [{:keys [cli-path]}]
  (or cli-path
      (let [tgt (io/file "target/jenkins-cli.jar")]
        (when-not (.exists tgt)
          (.mkdirs (.getParentFile tgt))
          (println "→ Downloading jenkins-cli.jar to" (.getPath tgt))
          (let [;; The CLI is published at the same URL across Jenkins
                ;; versions; we pull the LTS one.
                ;; 2.426.3 jar-with-dependencies was 404 as of 2026-05-29.
                ;; 2.479.3 published under /public/ is the current stable
                ;; CLI artifact for Jenkins LTS clients.
                url "https://repo.jenkins-ci.org/public/org/jenkins-ci/main/cli/2.479.3/cli-2.479.3.jar"]
            (let [r (p/shell {:continue true}
                             "curl" "-fsSL" "-o" (.getPath tgt) url)]
              (when-not (zero? (:exit r))
                (println "ERROR: failed to download jenkins-cli.jar from" url)
                (System/exit 3)))))
        (.getPath tgt))))

(defn wait-for-anvil [url timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [r (p/shell {:continue true :out :string :err :string}
                       "curl" "-fsS" "-m" "2"
                       (str url "/api/health"))]
        (cond
          (and (zero? (:exit r)) (str/includes? (:out r) "\"ready\":true")) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 500) (recur)))))))

(defn start-anvil! []
  (println "→ Starting anvil daemon ... (output to target/anvil-fixture.log)")
  (.mkdirs (io/file "target"))
  (let [pb (p/process {:dir "."
                       :out (java.lang.ProcessBuilder$Redirect/appendTo
                             (io/file "target/anvil-fixture.log"))
                       :err (java.lang.ProcessBuilder$Redirect/appendTo
                             (io/file "target/anvil-fixture.log"))
                       :continue true}
                      "lein" "run" "--port" "8765")]
    (println "  PID:" (.pid (:proc pb)))
    pb))

;; ---------------------------------------------------------------------------
;; Job source — a minimal Jenkinsfile the CLI fixture exercises
;; ---------------------------------------------------------------------------

(def demo-jenkinsfile
  "pipeline {
     agent any
     stages {
       stage('Build') {
         steps {
           echo 'building'
           sh 'echo step-1-output'
           sh 'echo step-2-output'
         }
       }
       stage('Test') {
         steps {
           sh 'echo all-tests-passed'
         }
       }
     }
   }")

;; ---------------------------------------------------------------------------
;; The actual fixture sequence
;; ---------------------------------------------------------------------------

(defn run-fixture [{:keys [anvil-url cli-jar skip-build skip-cli]}]
  (println "")
  (println "anvil jenkins-cli integration fixture")
  (println "  anvil:" anvil-url)
  (println "  cli:  " cli-jar)
  (println "")

  ;; 1. Health check
  (step "GET /api/health → ready"
        #(let [r (p/shell {:out :string :err :string :continue true}
                          "curl" "-fsS" (str anvil-url "/api/health"))]
           (and (zero? (:exit r)) (str/includes? (:out r) "\"ready\":true"))))

  ;; 2. Jenkins root API responds with Hudson shape
  (step "GET /jenkins/api/json returns hudson.model.Hudson"
        #(let [r (p/shell {:out :string :err :string :continue true}
                          "curl" "-fsS" (str anvil-url "/jenkins/api/json"))]
           (and (zero? (:exit r))
                (str/includes? (:out r) "\"_class\":\"hudson.model.Hudson\""))))

  ;; 3. Register a demo job via anvil-native admin
  (step "POST /anvil/admin/jobs registers 'cli-demo'"
        #(let [r (p/shell {:out :string :err :string :continue true}
                          "curl" "-fsS" "-X" "POST"
                          "-H" "Content-Type: application/json"
                          "-d" (str "{\"name\":\"cli-demo\",\"jenkinsfile_source\":"
                                    (pr-str demo-jenkinsfile) "}")
                          (str anvil-url "/anvil/admin/jobs"))]
           (and (zero? (:exit r))
                (str/includes? (:out r) "\"status\":\"ok\""))))

  ;; 4. jenkins-cli list-jobs shows the demo job
  (when-not skip-cli
    (step "jenkins-cli list-jobs shows 'cli-demo'"
          #(let [r (p/shell {:out :string :err :string :continue true}
                            "java" "-jar" cli-jar "-s" (str anvil-url "/jenkins/")
                            "list-jobs")]
             ;; jenkins-cli's list-jobs uses /api/json with depth=1 and prints
             ;; each "name" field. If our shim's job listing is correct, the
             ;; demo job name appears.
             (str/includes? (:out r) "cli-demo"))))

  (when-not skip-build
    ;; 5. Build trigger. When --skip-cli, hit the REST endpoint directly
    ;; (same one jenkins-cli's `build` command POSTs to internally) so
    ;; the build still runs and the next polling step can verify it
    ;; completes.
    (if skip-cli
      (step "POST /jenkins/job/cli-demo/build (REST, skip-cli mode)"
            #(let [r (p/shell {:out :string :err :string :continue true}
                              "curl" "-fsS" "-X" "POST"
                              (str anvil-url "/jenkins/job/cli-demo/build"))]
               (zero? (:exit r))))
      (step "jenkins-cli build cli-demo enqueues a build"
            #(let [r (p/shell {:out :string :err :string :continue true}
                              "java" "-jar" cli-jar "-s" (str anvil-url "/jenkins/")
                              "build" "cli-demo")]
               ;; jenkins-cli exits 0 if the build was queued.
               (zero? (:exit r)))))

    ;; 6. Poll /jenkins/job/cli-demo/api/json for builds[0]
    (step "Build completes within 30s"
          #(let [deadline (+ (System/currentTimeMillis) 30000)]
             (loop []
               (let [r (p/shell {:out :string :err :string :continue true}
                                "curl" "-fsS"
                                (str anvil-url "/jenkins/job/cli-demo/api/json"))]
                 (cond
                   (and (zero? (:exit r))
                        (str/includes? (:out r) "\"lastSuccessfulBuild\":{\"_class\""))
                   true

                   (> (System/currentTimeMillis) deadline)
                   false

                   :else (do (Thread/sleep 1000) (recur)))))))

    ;; 7. jenkins-cli console cli-demo shows the recorded output
    (when-not skip-cli
      (step "jenkins-cli console cli-demo prints step output"
            #(let [r (p/shell {:out :string :err :string :continue true}
                              "java" "-jar" cli-jar "-s" (str anvil-url "/jenkins/")
                              "console" "cli-demo")]
               (and (zero? (:exit r))
                    (str/includes? (:out r) "all-tests-passed"))))))

  ;; 8. 501 endpoints stay 501 (policy decision must hold)
  (step "POST /jenkins/script returns 501"
        #(let [r (p/shell {:out :string :err :string :continue true}
                          "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}"
                          "-X" "POST" (str anvil-url "/jenkins/script"))]
           (= "501" (str/trim (:out r)))))

  (step "POST /jenkins/createItem returns 501"
        #(let [r (p/shell {:out :string :err :string :continue true}
                          "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}"
                          "-X" "POST" (str anvil-url "/jenkins/createItem"))]
           (= "501" (str/trim (:out r)))))

  ;; 9. Cleanup
  (step "DELETE /anvil/admin/jobs/cli-demo"
        #(let [r (p/shell {:out :string :err :string :continue true}
                          "curl" "-fsS" "-X" "DELETE"
                          (str anvil-url "/anvil/admin/jobs/cli-demo"))]
           (zero? (:exit r)))))

(defn report []
  (println)
  (println "===========================================================================")
  (let [results @step-results
        passed (count (filter :ok? results))
        failed (count (remove :ok? results))]
    (println (format "  RESULT: %d passed, %d failed (%d total)"
                     passed failed (count results)))
    (println)
    (doseq [{:keys [name ok? elapsed-ms detail]} results]
      (println (format "    %s %s  (%d ms)%s"
                       (if ok? "✓" "✗") name elapsed-ms
                       (if detail (str "  — " detail) ""))))
    (println "===========================================================================")
    (if (zero? failed) 0 1)))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        {:keys [anvil-url no-start skip-build skip-cli help]} options]
    (when help (println summary) (System/exit 0))
    (when (seq errors) (doseq [e errors] (println "ERROR:" e)) (System/exit 2))

    (let [cli-jar (when-not skip-cli (locate-jenkins-cli options))
          anvil-pb (when-not no-start (start-anvil!))]
      (try
        (when-not (wait-for-anvil anvil-url 60000)
          (println "ERROR: anvil did not come up within 60s at" anvil-url)
          (System/exit 3))

        (run-fixture {:anvil-url anvil-url
                      :cli-jar cli-jar
                      :skip-build skip-build
                      :skip-cli skip-cli})

        (let [code (report)]
          (when anvil-pb
            (println "→ Stopping anvil daemon ...")
            (.destroy ^Process (:proc anvil-pb))
            (Thread/sleep 500))
          (System/exit code))
        (catch Throwable t
          (println "FATAL:" (.getMessage t))
          (when anvil-pb
            (.destroy ^Process (:proc anvil-pb)))
          (System/exit 4))))))

(-main *command-line-args*)
