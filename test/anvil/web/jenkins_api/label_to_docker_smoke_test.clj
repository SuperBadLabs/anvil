(ns anvil.web.jenkins-api.label-to-docker-smoke-test
  "AN5-3c — End-to-end: a Jenkinsfile with `agent { label '...' }`
   whose label maps to `:executor :docker` in agents.edn → build runs
   inside the declared container.

   This is the wild-corpus dirty-dozen shape. With AN5-3b alone, label
   agents fell back to host execution. With AN5-3c wiring the registry
   to AN5-3b's bridge, label resolution → ctx active-agent docker →
   AN5-3b honors → container exec.

   The full chain is opt-in under `:docker-integration` because it
   needs a real docker daemon."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [anvil.agents.registry :as reg]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.runner :as runner]))

(use-fixtures :each
  (fn [f]
    (jobs/clear!)
    (queue/clear!)
    (reg/reset-cache!)
    (try (f)
         (finally
           (queue/clear!)
           (jobs/clear!)
           (reg/reset-cache!)))))

(defn- docker-available? []
  (try
    (zero? (:exit (sh/sh "docker" "version" "--format" "ok")))
    (catch Exception _ false)))

(def ^:private label-agent-jenkinsfile
  "pipeline {
     agent { label 'an5-3c-docker-label' }
     stages {
       stage('Inside container via label') {
         steps {
           sh 'uname -s > os-marker.txt'
           sh 'echo from-label-mapped-container > artifact.txt'
         }
       }
       stage('Archive') {
         steps {
           archiveArtifacts artifacts: 'os-marker.txt,artifact.txt'
         }
       }
     }
   }")

(defn- with-docker-label-registry [f]
  (with-redefs [reg/registry
                (constantly
                 {:default {:executor :local
                            :env {}
                            :cwd "/tmp/anvil-workspace"}
                  :labels  {"an5-3c-docker-label"
                            {:executor :docker
                             :image    "alpine:3.20"}}})]
    (f)))

(defn- run! []
  (jobs/register-job!
   {:name "an5-3c-label-docker-smoke"
    :jenkinsfile-source label-agent-jenkinsfile})
  (runner/run-build! "an5-3c-label-docker-smoke" {:execute? true}))

(deftest ^:docker-integration label-mapped-to-docker-runs-in-container
  (when (docker-available?)
    (testing "agent { label 'X' } with X mapped to :executor :docker → runs in container"
      (with-docker-label-registry
        (fn []
          (let [result (run!)
                ws (:workspace result)
                os-file (io/file ws "os-marker.txt")
                artifact (io/file ws "artifact.txt")]

            ;; THE receipt: the build is classified success because the
            ;; label resolved to docker AND the docker actually ran.
            (is (= :success (:result result))
                (str "label→docker build must classify :success, got "
                     (:result result) " rule=" (:rule result)
                     " explain=" (:explain result)))

            ;; Real container output on disk
            (is (.exists os-file)
                (str "os-marker.txt missing in " ws
                     " — label was not resolved into a docker exec"))
            (is (.exists artifact)
                "artifact.txt missing — sh did not run inside the container")

            ;; uname -s = Linux from alpine kernel
            (when (.exists os-file)
              (is (.contains (slurp os-file) "Linux")
                  "uname -s in alpine container must say Linux"))
            (when (.exists artifact)
              (is (= "from-label-mapped-container\n" (slurp artifact))
                  "artifact contents must match what sh wrote inside container"))))))))

(deftest ^:docker-integration label-mapped-to-docker-does-not-degrade
  (when (docker-available?)
    (testing "label-mapped-to-docker path does NOT emit [:agent/degraded]"
      (with-docker-label-registry
        (fn []
          (let [result (run!)
                build (jobs/find-build "an5-3c-label-docker-smoke"
                                       (:build-number result))
                degraded (filter #(and (vector? %) (= :agent/degraded (first %)))
                                 (:effects build))]
            (is (empty? degraded)
                "post-AN5-3c: label mapped to docker must NOT degrade")))))))
