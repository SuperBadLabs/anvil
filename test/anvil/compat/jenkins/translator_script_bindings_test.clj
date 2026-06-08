(ns anvil.compat.jenkins.translator-script-bindings-test
  "v0.6.2 — top-level Groovy `def NAME = expr` script bindings.

   Real Jenkinsfiles declare file-scope vars above the `pipeline {}`
   block and reference them inside (label AGENT_LABEL, jdk JDK_NAME,
   sh \"${MAVEN_PARAMS} ...\"). This test fixture nails down the
   resolution behavior for each statically-resolvable RHS shape."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [anvil.compat.jenkins.translator :as t]))

(defn- find-option [ir k]
  (some #(get % k) (:options ir)))

;; ---------------------------------------------------------------------------
;; agent { label NAME } — bare-var resolution via top-level def
;; ---------------------------------------------------------------------------

(deftest top-level-def-string-literal
  (testing "def X = 'value' + agent { label X } → :label resolves to 'value'"
    (let [src "def AGENT_LABEL = 'ubuntu'
               pipeline {
                 agent { label AGENT_LABEL }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir (t/parse src)]
      (is (= "ubuntu" (get-in ir [:agent :label])))
      (is (= {:script-binding "AGENT_LABEL"}
             (get-in ir [:agent :resolved-from])))
      (is (= {"AGENT_LABEL" "ubuntu"} (find-option ir :script-bindings))))))

(deftest top-level-def-env-fallback
  (testing "def X = env.X ?: 'default' → label resolves to 'default' (env unknown at parse)"
    (let [src "def AGENT_LABEL = env.AGENT_LABEL ?: 'ubuntu'
               pipeline {
                 agent { label AGENT_LABEL }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir (t/parse src)]
      (is (= "ubuntu" (get-in ir [:agent :label])))
      (is (= {"AGENT_LABEL" "ubuntu"} (find-option ir :script-bindings))))))

;; ---------------------------------------------------------------------------
;; tools { jdk NAME } — bare-var resolution via top-level def
;; ---------------------------------------------------------------------------

(deftest top-level-def-tools-version
  (testing "def JDK = 'jdk_17_latest' + tools { jdk JDK } → :version resolves"
    (let [src "def JDK_NAME = 'jdk_17_latest'
               pipeline {
                 agent { label 'ubuntu' }
                 tools { jdk JDK_NAME }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir (t/parse src)
          tools (:tools ir)]
      (is (= 1 (count tools)))
      (is (= :jdk (-> tools first :type)))
      (is (= "jdk_17_latest" (-> tools first :version))))))

(deftest top-level-def-tools-env-fallback
  (testing "def JDK = env.JDK ?: 'jdk_17_latest' + tools { jdk JDK } resolves to fallback"
    (let [src "def JDK_NAME = env.JDK_NAME ?: 'jdk_17_latest'
               pipeline {
                 agent { label 'ubuntu' }
                 tools { jdk JDK_NAME }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir (t/parse src)]
      (is (= "jdk_17_latest" (-> ir :tools first :version))))))

;; ---------------------------------------------------------------------------
;; sh "${VAR}" — GString interpolation against script bindings
;; ---------------------------------------------------------------------------

(deftest top-level-def-sh-gstring-interpolation
  (testing "sh \"./mvnw ${MAVEN_PARAMS} clean install\" substitutes the def value"
    (let [src "def MAVEN_PARAMS = '-B -e -ntp'
               pipeline {
                 agent { label 'ubuntu' }
                 stages {
                   stage('Build') {
                     steps {
                       sh \"./mvnw ${MAVEN_PARAMS} clean install\"
                     }
                   }
                 }
               }"
          ir (t/parse src)
          sh-step (-> ir :stages first :steps first)]
      (is (= :jenkins/sh (:type sh-step)))
      (is (= "./mvnw -B -e -ntp clean install" (:script sh-step))))))

(deftest top-level-def-sh-leaves-bash-vars-alone
  (testing "sh GString refs whose NAME isn't in script-bindings pass through verbatim"
    (let [src "def MAVEN_PARAMS = '-B'
               pipeline {
                 agent { label 'ubuntu' }
                 stages {
                   stage('S') {
                     steps { sh \"${MAVEN_PARAMS} ${HOME}\" }
                   }
                 }
               }"
          ir (t/parse src)
          sh-step (-> ir :stages first :steps first)]
      (is (= "-B $HOME" (:script sh-step))
          "MAVEN_PARAMS substituted; HOME (a bash var) left untouched"))))

(deftest top-level-def-sh-single-quoted-untouched
  (testing "sh 'literal $VAR' (single-quoted Groovy string → :const) is never substituted"
    (let [src "def X = 'should-not-appear'
               pipeline {
                 agent { label 'ubuntu' }
                 stages {
                   stage('S') {
                     steps { sh 'echo $X stays literal' }
                   }
                 }
               }"
          ir (t/parse src)]
      (is (= "echo $X stays literal" (-> ir :stages first :steps first :script))))))

;; ---------------------------------------------------------------------------
;; Unresolvable defs — recorded for operator visibility, behavior unchanged
;; ---------------------------------------------------------------------------

(deftest top-level-def-unresolvable
  (testing "def X = someMethod() leaves label as <dynamic> + records :script-bindings-unresolvable"
    (let [src "def AGENT_LABEL = someUncallableMethod()
               pipeline {
                 agent { label AGENT_LABEL }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir (t/parse src)]
      (is (= "<dynamic>" (get-in ir [:agent :label]))
          "unresolvable def → legacy <dynamic> behavior")
      (is (contains? (set (find-option ir :script-bindings-unresolvable))
                     "AGENT_LABEL")))))

(deftest top-level-def-mixed-resolvable-and-unresolvable
  (testing "mixed defs: resolvable ones land in :script-bindings, others in :script-bindings-unresolvable"
    (let [src "def OK = 'value-ok'
               def BAD = methodCall()
               pipeline {
                 agent { label OK }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir (t/parse src)]
      (is (= "value-ok" (get-in ir [:agent :label])))
      (is (= {"OK" "value-ok"} (find-option ir :script-bindings)))
      (is (= ["BAD"] (find-option ir :script-bindings-unresolvable))))))

;; ---------------------------------------------------------------------------
;; Wild-corpus regression — apache-camel-quarkus's real Jenkinsfile
;; ---------------------------------------------------------------------------

(deftest camel-quarkus-jenkinsfile-resolves-cleanly
  (testing "the camel-quarkus Jenkinsfile (def AGENT_LABEL/JDK_NAME/MAVEN_PARAMS) parses without <dynamic>"
    (let [src "def AGENT_LABEL = env.AGENT_LABEL ?: 'ubuntu'
               def JDK_NAME = env.JDK_NAME ?: 'jdk_17_latest'
               def MAVEN_PARAMS = '-B -e -ntp'

               pipeline {
                 agent { label AGENT_LABEL }
                 tools { jdk JDK_NAME }
                 stages {
                   stage('Deploy') {
                     environment { MAVEN_OPTS = \"-Xmx4600m\" }
                     steps {
                       sh \"./mvnw ${MAVEN_PARAMS} -Ddeploy -Dquickly clean deploy\"
                     }
                   }
                 }
               }"
          ir (t/parse src)]
      (is (= "ubuntu" (get-in ir [:agent :label]))
          "AGENT_LABEL resolves via env-fallback Elvis")
      (is (= [{:type :jdk :version "jdk_17_latest"}] (:tools ir))
          "JDK_NAME resolves to the literal fallback")
      (is (= "./mvnw -B -e -ntp -Ddeploy -Dquickly clean deploy"
             (-> ir :stages first :steps first :script))
          "MAVEN_PARAMS substituted in the sh GString"))))

;; ---------------------------------------------------------------------------
;; Pre-v0.6.2 invariants — no def, no script-bindings option
;; ---------------------------------------------------------------------------

(deftest no-top-level-defs-no-script-bindings-option
  (testing "Jenkinsfiles without any top-level def emit no :script-bindings option entry"
    (let [src "pipeline {
                 agent { label 'ubuntu' }
                 stages { stage('S') { steps { sh 'true' } } }
               }"
          ir (t/parse src)]
      (is (nil? (find-option ir :script-bindings)))
      (is (nil? (find-option ir :script-bindings-unresolvable))))))
