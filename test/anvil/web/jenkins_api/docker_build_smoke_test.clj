(ns anvil.web.jenkins-api.docker-build-smoke-test
  "AN5-3b — End-to-end smoke test for `agent { docker { image '...' } }`
   builds. Runs the full anvil stack — parser → IR → matrix expander →
   flattener → execute-mode dispatcher → backend-wiring bridge →
   chengis-core DockerBackend → real container — and asserts a real
   file lands on disk that was *only writable from inside the
   container*.

   Without this test, a docker-honoring claim is unverifiable: anvil
   might be running `sh` on the host and pretending. With it, the
   uname-from-container output proves the build actually executed
   inside the declared image."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.runner :as runner]))

(use-fixtures :each
  (fn [f]
    (jobs/clear!)
    (queue/clear!)
    (try (f)
         (finally
           (queue/clear!)
           (jobs/clear!)))))

(defn- docker-available? []
  (try
    (zero? (:exit (sh/sh "docker" "version" "--format" "ok")))
    (catch Exception _ false)))

;; ---------------------------------------------------------------------------
;; The dirty-dozen-relevant Jenkinsfile shape
;;
;; Mirrors what apache-camel / apache-cxf / apache-camel-quarkus look
;; like: declarative pipeline with `agent { docker { image 'X' } }`
;; and a sh that needs the container's environment to succeed.
;; Uses alpine:3.20 (small, fast pull) instead of maven:3.9 so the
;; test stays fast — the dispatch path is identical.
;; ---------------------------------------------------------------------------

(def ^:private docker-agent-jenkinsfile
  "pipeline {
     agent { docker { image 'alpine:3.20' } }
     stages {
       stage('Inside container') {
         steps {
           sh 'uname -s > os-marker.txt'
           sh 'echo from-container-build > artifact.txt'
         }
       }
       stage('Archive') {
         steps {
           archiveArtifacts artifacts: 'os-marker.txt,artifact.txt'
         }
       }
     }
   }")

(defn- run! []
  (jobs/register-job!
   {:name "an5-3b-docker-smoke"
    :jenkinsfile-source docker-agent-jenkinsfile})
  (runner/run-build! "an5-3b-docker-smoke" {:execute? true}))

;; ---------------------------------------------------------------------------
;; The receipt
;; ---------------------------------------------------------------------------

(deftest ^:docker-integration docker-agent-actually-runs-inside-the-container
  (when (docker-available?)
    (testing "agent { docker { image 'alpine:3.20' } } → sh runs inside container, produces real artifacts"
      (let [result (run!)
            ws (:workspace result)
            os-file (io/file ws "os-marker.txt")
            artifact (io/file ws "artifact.txt")]

        ;; 1. Build classifies as :success
        (is (= :success (:result result))
            (str "docker-agent build must classify :success, got "
                 (:result result) " rule=" (:rule result)
                 " explain=" (:explain result)))

        ;; 2. Real files on disk in workspace (host can see container's writes
        ;;    because backend-wiring mounts the workspace into the container)
        (is (.exists os-file)
            (str "os-marker.txt missing in workspace " ws
                 " — backend either didn't run sh inside container or "
                 "didn't mount the workspace"))
        (is (.exists artifact)
            "artifact.txt missing — sh ran but didn't write to mounted workspace")

        ;; 3. CRITICAL: os-marker.txt should contain "Linux" because we're
        ;;    inside alpine, not whatever the host runs. If the bridge fell
        ;;    back to LocalShell, on macOS this would read "Darwin" — the
        ;;    test would catch that bug. On Linux hosts the marker is less
        ;;    diagnostic (host is also Linux) but the file's existence and
        ;;    classification still prove the path works end-to-end.
        (when (.exists os-file)
          (is (.contains (slurp os-file) "Linux")
              "container's uname must report Linux (alpine kernel is Linux)"))

        (when (.exists artifact)
          (is (= "from-container-build\n" (slurp artifact))
              "artifact.txt contents must match what sh wrote"))))))

(deftest ^:docker-integration docker-agent-records-real-sh-effects
  (when (docker-available?)
    (testing ":sh effects show real :exit 0 from in-container subprocess"
      (let [result (run!)
            build (jobs/find-build "an5-3b-docker-smoke" (:build-number result))
            sh-effects (filter #(and (vector? %) (= :sh (first %)))
                               (:effects build))]
        (is (<= 2 (count sh-effects))
            "expected ≥2 :sh effects (uname + echo)")
        (doseq [[_ data] sh-effects]
          (is (= 0 (:exit data))
              (str "real container sh exit must be 0, got: " (:exit data))))))))

(deftest ^:docker-integration docker-agent-does-not-degrade
  (when (docker-available?)
    (testing "docker-honoring path does NOT emit [:agent/degraded] — the executor IS honoring"
      (let [result (run!)
            build (jobs/find-build "an5-3b-docker-smoke" (:build-number result))
            degraded-effects (filter #(and (vector? %) (= :agent/degraded (first %)))
                                     (:effects build))]
        ;; AN4-2 emits :agent/degraded when docker can't be honored. After
        ;; AN5-3b, docker IS honored, so no degradation effect should fire.
        (is (empty? degraded-effects)
            "post-AN5-3b: docker agent must NOT be marked as degraded")))))
