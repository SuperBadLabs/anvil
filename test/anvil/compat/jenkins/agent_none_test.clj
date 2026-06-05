(ns anvil.compat.jenkins.agent-none-test
  "AN5-5 — Stage-level `agent none` must NOT silently skip the body
   when the stage has a `steps { … }` block.

   The wild-corpus hunt showed apache-camel's `stage('BuildAndTest')`
   classified as `:unsupported` via the AN5-1 synthesizer. Investigation
   revealed apache-camel actually uses `matrix { … }` inside the stage
   (not plain `steps { … }`) — that's a different gap.

   THIS test exercises the simpler `agent none + steps { sh ... }` shape
   to verify the dispatcher honors it correctly. If green, gap #2 from
   the 'what is the gap' triage is ALREADY closed (and the synthesizer
   is firing for apache-camel for the matrix-translator gap, not for an
   agent-none gap). If red, the dispatcher needs the fix the user asked
   for: fall back to dispatcher-default executor when stage agent is :none."
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
         (finally (queue/clear!) (jobs/clear!)))))

(def ^:private agent-none-jenkinsfile
  "pipeline {
     agent none
     stages {
       stage('Build') {
         agent none
         steps {
           sh 'echo stage-agent-none > artifact.txt'
           sh 'cat artifact.txt'
         }
       }
     }
   }")

(defn- run! []
  (jobs/register-job!
   {:name "an5-5-agent-none-test"
    :jenkinsfile-source agent-none-jenkinsfile})
  (runner/run-build! "an5-5-agent-none-test" {:execute? true}))

(deftest agent-none-stage-body-actually-runs
  (testing "stage with agent none + steps { sh ... } → sh runs, file lands on disk"
    (let [result (run!)
          ws (:workspace result)
          f (io/file ws "artifact.txt")]

      (is (= :success (:result result))
          (str "stage with agent none + steps must classify :success, got "
               (:result result) " rule=" (:rule result)
               " explain=" (:explain result)))

      (is (.exists f)
          (str "artifact.txt missing in workspace " ws
               " — stage body was silently skipped"))

      (when (.exists f)
        (is (= "stage-agent-none\n" (slurp f)))))))

(deftest agent-none-records-sh-effects
  (testing "agent none + steps { sh ... } records real :sh effects (not synthetic)"
    (let [result (run!)
          build (jobs/find-build "an5-5-agent-none-test" (:build-number result))
          sh-effects (filter #(and (vector? %) (= :sh (first %)))
                             (:effects build))
          synthetic-effects (filter #(and (vector? %) (= :unknown (first %))
                                          (-> % second :anvil/synthetic?))
                                    (:effects build))]
      (is (<= 2 (count sh-effects))
          "expected ≥2 :sh effects (echo + cat)")
      (is (empty? synthetic-effects)
          "AN5-1 synthesizer should NOT fire on a build that did real work"))))
