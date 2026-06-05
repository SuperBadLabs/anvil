(ns anvil.web.jenkins-api.real-build-smoke-test
  "AN5-3 — End-to-end real-artifact smoke test.

   The wild-corpus matrix re-run produced 0/15 real builds. Before
   wiring container backends, tool installers, or shared libraries,
   this test establishes the baseline: a minimal declarative
   Jenkinsfile with `sh` + `archiveArtifacts`, run through the full
   anvil stack (parser → matrix expander → flattener → execute-mode
   dispatcher → runner workspace setup → classification), produces a
   real file on disk and classifies as :success.

   Without this baseline passing, no claim about anvil 'producing real
   artifacts' can mean anything. Without this baseline locked down in
   CI, regressions in the execute path show up only in the manual
   wild-corpus run — where they're indistinguishable from the corpus's
   own moving parts. This test is the canary."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
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

;; ---------------------------------------------------------------------------
;; The minimum-viable Jenkinsfile
;;
;; - `agent any` so the agent-honor table picks the controller (no
;;   container shape needed; baseline doesn't require docker)
;; - `sh 'echo … > artifact.txt'` exercises real subprocess execution
;;   via shell-execute in execute mode
;; - `sh 'ls -la artifact.txt'` proves the file landed inside the
;;   workspace cwd
;; - `archiveArtifacts artifacts: 'artifact.txt'` exercises the archive
;;   step adapter which records an :archive effect AND copies the file
;;   into the build's archived-artifacts area
;; ---------------------------------------------------------------------------

(def ^:private smoke-jenkinsfile
  "pipeline {
     agent any
     stages {
       stage('Produce') {
         steps {
           sh 'echo anvil-smoke-build > artifact.txt'
           sh 'ls -la artifact.txt'
         }
       }
       stage('Archive') {
         steps {
           archiveArtifacts artifacts: 'artifact.txt'
         }
       }
     }
   }")

(defn- run-smoke! []
  (jobs/register-job!
   {:name "an5-smoke"
    :jenkinsfile-source smoke-jenkinsfile})
  (runner/run-build! "an5-smoke" {:execute? true}))

;; ---------------------------------------------------------------------------
;; Baseline assertions
;; ---------------------------------------------------------------------------

(deftest smoke-build-classifies-as-success
  (testing "minimal Jenkinsfile through full anvil stack → :success"
    (let [result (run-smoke!)]
      (is (= :success (:result result))
          (str "real artifact build must classify :success, got: "
               (:result result) " rule=" (:rule result)
               " explain=" (:explain result))))))

(deftest smoke-build-produces-real-file-on-disk
  (testing "sh 'echo … > artifact.txt' actually writes the file"
    (let [result (run-smoke!)
          ws (:workspace result)
          f (io/file ws "artifact.txt")]
      (is (.exists f)
          (str "artifact.txt missing in workspace " ws
               " — anvil's execute-mode shell isn't producing real files"))
      (when (.exists f)
        (is (= "anvil-smoke-build\n" (slurp f))
            "file contents must match what sh wrote")))))

(deftest smoke-build-records-archive-effect
  (testing "archiveArtifacts step adapter records the :archive effect"
    (let [result (run-smoke!)
          build (jobs/find-build "an5-smoke" (:build-number result))
          archive-effects (filter #(and (vector? %) (= :archive (first %)))
                                  (:effects build))]
      (is (seq archive-effects)
          "archiveArtifacts must record an :archive effect — without it the
           AN4-1 classifier can't see the work and classifies as :neutral")
      (when (seq archive-effects)
        (let [[_ data] (first archive-effects)]
          (is (or (= "artifact.txt" (:artifacts data))
                  (some #(= "artifact.txt" %) (:files data)))
              ":archive effect must name the archived file"))))))

(deftest smoke-build-effects-contain-real-shell-exits
  (testing "both sh steps recorded :sh effects with :exit 0"
    (let [result (run-smoke!)
          build (jobs/find-build "an5-smoke" (:build-number result))
          sh-effects (filter #(and (vector? %) (= :sh (first %)))
                             (:effects build))]
      (is (<= 2 (count sh-effects))
          "expected ≥2 :sh effects (echo + ls), got fewer")
      (doseq [[_ data] sh-effects]
        (is (= 0 (:exit data))
            (str "real sh exit must be 0, got: " (:exit data)
                 " on cmd: " (:cmd data)))))))

(deftest smoke-build-has-no-synthetic-effects
  (testing "the AN5-1 walk-shape synthesizer does NOT fire on a working build"
    (let [result (run-smoke!)]
      (is (= :success (:result result)))
      (is (not= :unsupported (:result result))
          "if synthetic effects fired here, the synthesizer has a false-positive
           and would mark real builds as :unsupported. This protects against
           that regression."))))

(deftest smoke-build-workspace-persists-after-build
  (testing "the workspace directory remains on disk for the operator to inspect"
    (let [result (run-smoke!)
          ws-file (io/file (:workspace result))]
      (is (.exists ws-file)
          "workspace must survive the build — operators rely on it being
           inspectable through the Artifacts tab and Retry button"))))
