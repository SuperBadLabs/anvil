(ns anvil.compat.jenkins.translator-an8-test
  "AN8-1 + AN8-2 — translator + IR-helper unit tests.

   AN8-1 covers `tools { maven 'X' jdk 'Y' }` parsing at pipeline +
   stage levels. AN8-2 covers `parameters {…}` default extraction
   into `ir/default-parameters` for the runner to merge under the
   trigger payload."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.ir :as ir]))

;; ---------------------------------------------------------------------------
;; AN8-1 — tools{} directive
;; ---------------------------------------------------------------------------

(deftest an8-1-pipeline-tools-block
  (testing "tools { maven 'X' jdk 'Y' } parses into structured IR at pipeline level"
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 tools {
                   maven 'maven_3_latest'
                   jdk   'jdk_17_latest'
                 }
                 stages { stage('S') { steps { sh 'mvn -v' } } }
               }"
          ir-data (t/parse src)
          tools (:tools ir-data)]
      (is (vector? tools))
      (is (= 2 (count tools)))
      (is (= [:maven :jdk] (mapv :type tools))
          "tools preserve declaration order — operator maps may key on it")
      (is (= ["maven_3_latest" "jdk_17_latest"] (mapv :version tools)))
      (is (not (contains? (first tools) :raw))
          "no more raw placeholder per AN8-1"))))

(deftest an8-1-stage-level-tools-override
  (testing "stage-level tools{} parses into the stage IR for per-stage docker images"
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 tools {
                   maven 'maven_3_latest'
                   jdk   'jdk_17_latest'
                 }
                 stages {
                   stage('Build') {
                     tools { jdk 'jdk_11_latest' }
                     steps { sh 'mvn -B install' }
                   }
                 }
               }"
          ir-data (t/parse src)
          stage (first (:stages ir-data))]
      (is (= [{:type :jdk :version "jdk_11_latest"}] (:tools stage))
          "stage tools override pipeline-level for that stage")
      (is (= 2 (count (:tools ir-data)))
          "pipeline-level tools unchanged"))))

(deftest an8-1-tools-with-gstring-version
  (testing "tools { jdk \"${JAVA_VERSION}\" } preserves the template text"
    (let [src "pipeline {
                 agent any
                 tools { jdk \"${JAVA_VERSION}\" }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          tools (:tools (t/parse src))]
      (is (= 1 (count tools)))
      (is (= :jdk (-> tools first :type)))
      (is (string? (-> tools first :version))
          "translator carries the surface text forward so the dispatcher
           can include it in the :tools/unmapped effect"))))

(deftest an8-1-tools-with-bare-identifier
  (testing "tools { jdk JDK_NAME } parses the env-style identifier"
    (let [src "pipeline {
                 agent any
                 tools { jdk JDK_NAME }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          tools (:tools (t/parse src))]
      (is (= 1 (count tools)))
      (is (= :jdk (-> tools first :type))))))

(deftest an8-1-no-tools-block
  (testing "pipelines without tools{} still parse — :tools is nil/absent"
    (let [src "pipeline {
                 agent any
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir-data (t/parse src)]
      (is (nil? (:tools ir-data))))))

(deftest an8-1-empty-tools-block
  (testing "an empty `tools {}` block yields nil (no false positives in the dispatcher)"
    (let [src "pipeline {
                 agent any
                 tools {}
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir-data (t/parse src)]
      (is (nil? (:tools ir-data))
          "empty tools block ≡ no tools block — no synthetic :tools key"))))

;; ---------------------------------------------------------------------------
;; AN8-2 — parameters defaults extraction
;; ---------------------------------------------------------------------------

(deftest an8-2-choice-default-is-first-choice
  (testing "choice without :defaultValue → first choice"
    (let [src "pipeline {
                 agent any
                 parameters {
                   choice(name: 'NODE', choices: ['ubuntu', 's390x', 'arm'])
                 }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          defaults (ir/default-parameters (t/parse src))]
      (is (= {"NODE" "ubuntu"} defaults)
          "first choice is the implicit default — matches Jenkins"))))

(deftest an8-2-choice-default-explicit-wins
  (testing "choice with :defaultValue → that value (not first choice)"
    (let [src "pipeline {
                 agent any
                 parameters {
                   choice(name: 'JDK', choices: ['8', '11', '17'], defaultValue: '17')
                 }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          defaults (ir/default-parameters (t/parse src))]
      (is (= {"JDK" "17"} defaults)))))

(deftest an8-2-multiple-choices
  (testing "every named choice contributes its default"
    (let [src "pipeline {
                 agent any
                 parameters {
                   choice(name: 'PLATFORM', choices: ['linux', 'mac', 'windows'])
                   choice(name: 'JDK',      choices: ['8', '11', '17'])
                 }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          defaults (ir/default-parameters (t/parse src))]
      (is (= {"PLATFORM" "linux" "JDK" "8"} defaults)))))

(deftest an8-2-no-parameters-block
  (testing "pipeline without parameters{} → empty map"
    (let [src "pipeline {
                 agent any
                 stages { stage('S') { steps { sh 'true' } } }
               }"]
      (is (= {} (ir/default-parameters (t/parse src)))))))

(deftest an8-2-parameter-without-default-or-choices-is-omitted
  (testing "no default-value AND no choices → param is omitted from defaults"
    ;; Real Jenkins would return null for params.X on a triggerless build;
    ;; we omit the key entirely so the runtime sees the same shape.
    (let [src "pipeline {
                 agent any
                 parameters {
                   string(name: 'EMPTY')
                 }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          defaults (ir/default-parameters (t/parse src))]
      (is (not (contains? defaults "EMPTY"))
          "no default and no choices → no entry"))))

(deftest an8-2-pure-scripted-pipeline
  (testing "nil pipeline-ir yields {} — pure-scripted Jenkinsfiles are safe"
    (is (= {} (ir/default-parameters nil))
        "ir/default-parameters tolerates nil — runner uses this when parse fails")))
