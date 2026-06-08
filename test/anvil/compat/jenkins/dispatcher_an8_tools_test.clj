(ns anvil.compat.jenkins.dispatcher-an8-tools-test
  "AN8-1 — dispatcher integration for tools{} → pre-baked docker image.

   These tests run a wrapped pipeline through the dispatcher with the
   `:tools-directive` feature flag flipped on, and check the side
   effects + ctx evolution against the operator's
   `:anvil.tools/images` map."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.agent :as agent]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.translator :as t]
            [anvil.tools.images :as images]
            [anvil.features :as features]))

(use-fixtures :each
  (fn [f]
    (let [prev (features/snapshot)]
      (try
        (features/set! :tools-directive true)
        (images/clear-cache!)
        (f)
        (finally
          (doseq [[k v] prev]
            (features/set! k v))
          (images/clear-cache!))))))

(defn- run-with-images [src images-map]
  (with-redefs [images/all (constantly images-map)]
    (let [ir (t/parse src)
          wrapped (agent/wrap-pipeline-with-agent-events ir)
          flat {:stages (mapv #(select-keys % [:name :steps]) (:stages wrapped))}
          dispatcher (ad/make)
          result (d/run-pipeline flat dispatcher {})]
      {:result result :effects @(:effects dispatcher)})))

(deftest tools-resolves-to-mapped-image
  (testing "tools { maven 'X' jdk 'Y' } + operator mapping → :tools/resolved + active-agent docker image"
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 tools {
                   maven 'maven_3_latest'
                   jdk   'jdk_17_latest'
                 }
                 stages { stage('Build') { steps { sh 'mvn -v' } } }
               }"
          {:keys [effects]} (run-with-images
                             src
                             {"maven_3_latest+jdk_17_latest" "maven:3.9-eclipse-temurin-17"})
          resolved (->> effects
                        (filter #(= :tools/resolved (first %)))
                        first)]
      (is (some? resolved)
          "a :tools/resolved effect is emitted when the mapping hits")
      (is (= "maven:3.9-eclipse-temurin-17" (-> resolved second :image)))
      (is (= "maven_3_latest+jdk_17_latest" (-> resolved second :matched-key)))
      (is (= "Build" (-> resolved second :stage))))))

(deftest tools-unmapped-emits-effect-with-candidate-keys
  (testing "tools{} with no operator mapping → :tools/unmapped + candidate keys"
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 tools {
                   maven 'X'
                   jdk   'Y'
                 }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          {:keys [effects]} (run-with-images src {})
          unmapped (->> effects
                        (filter #(= :tools/unmapped (first %)))
                        first)
          resolved (->> effects (filter #(= :tools/resolved (first %))))]
      (is (some? unmapped) ":tools/unmapped effect is emitted")
      (is (empty? resolved) "no :tools/resolved when there is no mapping")
      (is (seq (-> unmapped second :candidate-keys)))
      (is (some #{"X+Y"} (-> unmapped second :candidate-keys))
          "raw declaration-order key MUST be in the diagnostic list"))))

(deftest tools-feature-flag-gates-resolution
  (testing "with :tools-directive flag OFF, tools{} is ignored — no :tools/* effects"
    (features/set! :tools-directive false)
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 tools {
                   maven 'X'
                   jdk   'Y'
                 }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          {:keys [effects]} (run-with-images
                             src
                             {"X+Y" "should-not-be-used:1"})]
      (is (empty? (filter #(#{:tools/resolved :tools/unmapped} (first %)) effects))
          "feature off → no AN8-1 effects whatsoever"))))

(deftest tools-does-not-override-explicit-docker-agent
  (testing "Jenkinsfile-declared agent { docker { image '...' } } wins — operator
            never overrides an explicit author choice"
    (let [src "pipeline {
                 agent { docker { image 'author-chose:1.0' } }
                 tools {
                   maven 'X'
                   jdk   'Y'
                 }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          {:keys [effects]} (run-with-images
                             src
                             {"X+Y" "operator-mapped:1"})
          resolved (->> effects
                        (filter #(= :tools/resolved (first %)))
                        first)]
      (is (nil? resolved)
          "no :tools/resolved when the author already declared an agent docker image"))))

(deftest stage-tools-override-pipeline-tools
  (testing "stage-level tools{} override pipeline-level for that stage"
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 tools {
                   maven 'M_PIPELINE'
                   jdk   'J_PIPELINE'
                 }
                 stages {
                   stage('S1') { steps { sh 'true' } }
                   stage('S2') {
                     tools { jdk 'J_STAGE' }
                     steps { sh 'true' }
                   }
                 }
               }"
          {:keys [effects]} (run-with-images
                             src
                             {"M_PIPELINE+J_PIPELINE" "img-pipeline:1"
                              "J_STAGE"               "img-stage:1"})
          resolved (->> effects
                        (filter #(= :tools/resolved (first %)))
                        (mapv second))]
      (is (= 2 (count resolved)))
      (is (= "img-pipeline:1" (-> resolved first :image))
          "S1 uses pipeline-level tools")
      (is (= "img-stage:1" (-> resolved second :image))
          "S2 uses stage-level tools override"))))
