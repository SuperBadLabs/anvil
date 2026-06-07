(ns anvil.compat.jenkins.translator-test
  "Focused unit tests for the Jenkins translator. Coverage against the
   real corpus lives in `corpus-regression-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.ir :as ir]))

(def ^:private minimal-declarative
  "pipeline {
     agent any
     stages {
       stage('Build') {
         steps {
           sh 'make compile'
         }
       }
       stage('Test') {
         steps {
           sh 'make test'
         }
       }
     }
   }")

(deftest parse-minimal-test
  (testing "a minimal declarative pipeline produces a valid IR"
    (let [ir-pipeline (t/parse minimal-declarative)]
      (is (ir/pipeline? ir-pipeline))
      (is (= 2 (count (:stages ir-pipeline))))
      (is (= ["Build" "Test"] (mapv :name (:stages ir-pipeline))))
      (let [first-stage (first (:stages ir-pipeline))]
        (is (= 1 (count (:steps first-stage))))
        (is (ir/step? (first (:steps first-stage))))
        (is (= :jenkins/sh (-> first-stage :steps first :type)))
        (is (= "make compile" (-> first-stage :steps first :script)))))))

(deftest agent-shapes-test
  (testing "agent any / agent { label '...' } / agent { docker { image '...' } }"
    (let [a-any (t/parse "pipeline { agent any; stages { stage('x') { steps { sh 'y' } } } }")
          a-label (t/parse "pipeline { agent { label 'foo' }; stages { stage('x') { steps { sh 'y' } } } }")
          a-docker (t/parse "pipeline { agent { docker { image 'node:18' } }; stages { stage('x') { steps { sh 'y' } } } }")]
      (is (= :any (get-in a-any [:agent :type])))
      (is (= "foo" (get-in a-label [:agent :label])))
      (is (= "node:18" (get-in a-docker [:agent :docker :image]))))))

(deftest post-actions-test
  (testing "post { always { … } success { … } failure { … } cleanup { … } } maps to keyed actions"
    (let [src "pipeline {
                 agent any
                 stages { stage('x') { steps { sh 'y' } } }
                 post {
                   always { junit '**/TEST-*.xml' }
                   success { echo 'OK' }
                   failure { echo 'BAD' }
                   cleanup { deleteDir() }
                 }
               }"
          ir (t/parse src)]
      (is (= :jenkins/junit (-> ir :post :always first :type)))
      (is (= :jenkins/echo (-> ir :post :on-success first :type)))
      (is (= :jenkins/echo (-> ir :post :on-failure first :type)))
      (is (= :jenkins/delete-dir (-> ir :post :cleanup first :type))))))

(deftest script-block-source-extraction-test
  (testing "a script {} block captures its raw Groovy body source for runtime use"
    (let [src "pipeline {
                 agent any
                 stages {
                   stage('Run') {
                     steps {
                       script {
                         def v = sh(script: 'cat VERSION', returnStdout: true).trim()
                         echo v
                       }
                     }
                   }
                 }
               }"
          ir (t/parse src)
          script-step (-> ir :stages first :steps first)]
      (is (ir/script-step? script-step))
      (is (str/includes? (:body-source script-step) "def v ="))
      (is (str/includes? (:body-source script-step) "echo v")))))

(deftest unknown-step-fallback-test
  (testing "unrecognized steps fall through to :jenkins/unknown with the name preserved"
    (let [ir (t/parse "pipeline { agent any; stages { stage('x') { steps { totallyMadeUpStep param: 'foo' } } } }")
          step (-> ir :stages first :steps first)]
      (is (= :jenkins/unknown (:type step)))
      (is (= "totallyMadeUpStep" (:name step))))))

(deftest environment-block-test
  (testing "environment block produces a string→string map"
    (let [src "pipeline {
                 agent any
                 environment {
                   FOO = 'bar'
                   BAZ = 'qux'
                 }
                 stages { stage('x') { steps { sh 'y' } } }
               }"
          ir (t/parse src)]
      (is (= {"FOO" "bar" "BAZ" "qux"} (:environment ir))))))

(deftest library-detection-test
  (testing "@Library annotations are detected and recorded"
    (let [src "@Library('my-lib@main') _

               pipeline {
                 agent any
                 stages { stage('x') { steps { sh 'y' } } }
               }"
          ir (t/parse src)]
      (is (= [{:name "my-lib" :version "main"}] (:libraries ir))))))

(deftest pipeline-summary-test
  (testing "the summary helper reports stage/step counts + coverage"
    (let [ir (t/parse minimal-declarative)
          summary (ir/summarize ir)]
      (is (= 2 (:stage-count summary)))
      (is (= ["Build" "Test"] (:stage-names summary)))
      (is (= 2 (:total-steps summary)))
      (is (= 2 (:known-steps summary)))
      (is (= 0 (:unknown-steps summary)))
      (is (= 100.0 (:coverage summary))))))

(deftest empty-or-invalid-input-test
  (testing "input without a pipeline {} block and no scripted stages returns an empty-but-tagged IR"
    (let [ir (t/parse "// just a comment, no pipeline")]
      (is (ir/pipeline? ir))
      (is (= [] (:stages ir)))
      (is (= :no-pipeline-block (some-> ir :options first :parse-error))))))

;; ---------------------------------------------------------------------------
;; Scripted Pipeline extraction
;; ---------------------------------------------------------------------------

(deftest scripted-freestanding-stage-test
  (testing "a freestanding `stage('name') { sh '…' }` outside of pipeline{} is collected"
    (let [ir (t/parse "stage('Build') { sh 'make' }\nstage('Test') { sh 'make test' }")]
      (is (ir/pipeline? ir))
      (is (= ["Build" "Test"] (mapv :name (:stages ir))))
      (is (= :jenkins/sh (-> ir :stages first :steps first :type)))
      (is (= "make" (-> ir :stages first :steps first :script))))))

(deftest scripted-nested-in-node-test
  (testing "scripted stages nested inside node('label') {} are collected with the node wrapper preserved"
    (let [src "node('linux') {\n  stage('Checkout') { sh 'git checkout' }\n  stage('Build') { sh 'make' }\n}"
          ir (t/parse src)
          names (mapv :name (:stages ir))]
      (is (= ["Checkout" "Build"] names))
      (is (= :jenkins/sh (-> ir :stages first :steps first :type))))))

(deftest scripted-nested-in-parallel-and-axes-test
  (testing "stages buried inside `parallel { … }` and inside `builds[k] = { … }` assignments are discoverable"
    (let [src "def builds = [:]\n
               ['linux','windows'].each { p ->\n
                 builds[p] = { node(p) { stage(\"${p} - Build\") { sh 'make' } } }\n
               }\n
               parallel builds"
          ir (t/parse src)
          names (mapv :name (:stages ir))]
      (is (some #(re-find #"Build" %) names))
      ;; the gstring literal template is preserved in the IR; Groovy's
      ;; getText() may normalize `${p}` to `$p` when the placeholder is a
      ;; bare variable, so we check for either form.
      (is (some #(re-find #"\$\{?p\}?" %) names)))))

(deftest scripted-stage-with-retry-node-withcredentials-test
  (testing "the wrapper chain retry → node → withCredentials → sh is recursively translated"
    (let [src "stage('Record') {\n
                 retry(count: 2) {\n
                   node('maven-21') {\n
                     withCredentials([string(credentialsId: 'tok', variable: 'T')]) {\n
                       sh 'launchable record'\n
                     }\n
                   }\n
                 }\n
               }"
          ir (t/parse src)
          stage (first (:stages ir))
          retry (first (:steps stage))
          node-step (first (:body retry))
          creds-step (first (:body node-step))
          sh-step (first (:body creds-step))]
      (is (= "Record" (:name stage)))
      (is (= :jenkins/retry (:type retry)))
      (is (= :jenkins/node (:type node-step)))
      (is (= "maven-21" (:label node-step)))
      (is (= :jenkins/with-credentials (:type creds-step)))
      (is (= :jenkins/sh (:type sh-step)))
      (is (= "launchable record" (:script sh-step))))))

;; ---------------------------------------------------------------------------
;; Wild-corpus follow-up tests
;; ---------------------------------------------------------------------------

(deftest echo-binary-expression-routes-through-script
  (testing "echo \"X \" + env.Y emits a :jenkins/script step, not an :echo
            with an AST .toString() dump"
    (let [src (str "pipeline {\n"
                   "  agent any\n"
                   "  stages {\n"
                   "    stage('S') {\n"
                   "      steps {\n"
                   "        echo \"Building branch \" + env.BRANCH_NAME\n"
                   "      }\n"
                   "    }\n"
                   "  }\n"
                   "}")
          ir (t/parse src "Jenkinsfile")
          steps (-> ir :stages first :steps)]
      (is (= 1 (count steps)))
      (let [s (first steps)]
        (is (= :jenkins/script (:type s))
            (str "expected :jenkins/script — got " (pr-str s)))
        (is (re-find #"env\.BRANCH_NAME" (:body-source s))
            "the source region carries the original expression")
        (is (not (re-find #"BinaryExpression" (:body-source s)))
            "no AST .toString() dump in the body source")))))

(deftest preamble-handles-triple-quoted-heredocs
  (testing "yaml \"\"\"...\"\"\" inside pipeline {} doesn't trip the brace
            balancer (mojarra has a kubernetes podTemplate yaml block;
            without triple-quote awareness the `{` chars inside the
            yaml terminated the outer pipeline {} early and the
            preamble accidentally carried `post {...}` declarative
            blocks which then failed script-block compilation)"
    (let [src (str "boolean isReleaseBuild() { return false }\n"
                   "pipeline {\n"
                   "  agent {\n"
                   "    kubernetes {\n"
                   "      yaml \"\"\"\n"
                   "apiVersion: v1\n"
                   "kind: Pod\n"
                   "spec: { containers: [{ name: jnlp, image: foo }] }\n"
                   "\"\"\"\n"
                   "    }\n"
                   "  }\n"
                   "  stages {\n"
                   "    stage('S') {\n"
                   "      steps {\n"
                   "        script { if (isReleaseBuild()) { echo 'go' } }\n"
                   "      }\n"
                   "    }\n"
                   "  }\n"
                   "  post {\n"
                   "    success { echo 'OK' }\n"
                   "  }\n"
                   "}\n")
          ir (t/parse src "Jenkinsfile")
          script-step (->> (:stages ir) first :steps
                           (filter #(= :jenkins/script (:type %)))
                           first)]
      (is (some? script-step))
      (is (re-find #"boolean isReleaseBuild" (:preamble script-step))
          "preamble carries top-level fn")
      (is (not (re-find #"(?m)^\s*post\s*\{" (:preamble script-step)))
          "preamble does NOT carry the post {} block — that's still inside pipeline {}")
      (is (not (re-find #"(?m)^\s*stage\s*\(" (:preamble script-step)))
          "preamble does NOT carry stage() — that's inside pipeline {}"))))

(deftest script-block-carries-jenkinsfile-preamble
  (testing "top-level def fn() outside pipeline{} threads to script-block IR
            so calls inside script blocks resolve at runtime"
    (let [src (str "boolean isDeployedBranch() { return true }\n"
                   "pipeline {\n"
                   "  agent any\n"
                   "  stages {\n"
                   "    stage('S') {\n"
                   "      steps {\n"
                   "        script { if (isDeployedBranch()) { echo 'go' } }\n"
                   "      }\n"
                   "    }\n"
                   "  }\n"
                   "}\n"
                   "def mavenBuild(jdk, args) { echo 'mvn' }")
          ir (t/parse src "Jenkinsfile")
          script-step (->> (:stages ir) first :steps
                           (filter #(= :jenkins/script (:type %)))
                           first)]
      (is (some? script-step) "should have a script step")
      (is (string? (:preamble script-step))
          ":preamble should be attached")
      (is (re-find #"boolean isDeployedBranch" (:preamble script-step))
          "preamble carries the top-level boolean fn")
      (is (re-find #"def mavenBuild" (:preamble script-step))
          "preamble carries the top-level def fn")
      (is (not (re-find #"pipeline\s*\{" (:preamble script-step)))
          "preamble strips the pipeline {} block"))))

(deftest jenkins-self-jenkinsfile-test
  (testing "the actual jenkinsci/jenkins Jenkinsfile yields >=4 scripted stages"
    (let [path "/home/srikanth/projects/jenkins/Jenkinsfile"]
      (when (.exists (java.io.File. path))
        (let [source (slurp path)
              ir (t/parse source path)
              names (mapv :name (:stages ir))]
          (is (>= (count (:stages ir)) 4)
              (str "expected >=4 stages, got " (count (:stages ir))
                   " — names: " (pr-str names)))
          (is (some #(= "Record build" %) names))
          (is (some #(re-find #"Checkout" %) names))
          (is (some #(re-find #"Build / Test" %) names))
          (is (some #(re-find #"Publish" %) names))
          ;; The scripted-pipeline tag should be set on options
          (is (true? (some :scripted-pipeline? (:options ir)))))))))

;; ---------------------------------------------------------------------------
;; v0.4 AN6-1 — parameter-driven nested label
;; ---------------------------------------------------------------------------

(def ^:private activemq-shape
  "Mirrors the apache-activemq Jenkinsfile shape that fell through to
   LocalShell in v0.3.3 (see docs/jenkins-compat/an5-7-activemq-receipt.md).
   The :default-value form of the choice param drives the static label
   resolution under AN6-1."
  ;; Note: explicit `;` between top-level blocks because the Groovy
  ;; parser anvil uses (`anvil.compat.jenkins.groovy`) doesn't always
  ;; treat a newline as a statement terminator when a single-line
  ;; brace-block precedes another.  Matches the existing
  ;; agent-shapes-test convention.
  "pipeline {
     agent { label { label params.nodeLabel } };
     parameters { choice(name: 'nodeLabel', choices: ['ubuntu', 's390x', 'arm', 'Windows']) };
     stages { stage('S') { steps { sh 'mvn -B package' } } }
   }")

(deftest an6-1-resolves-param-driven-label-to-first-choice
  (testing "agent { label { label params.X } } + choice(name X, choices [a,b]) → :label 'a' :inferred-from"
    (let [ir (t/parse activemq-shape)
          agent (:agent ir)]
      (is (= "ubuntu" (:label agent))
          "first choice picked when no defaultValue given")
      (is (= {:param-name "nodeLabel" :source :first-choice}
             (:inferred-from agent))))))

(def ^:private activemq-with-default-shape
  "Same shape but with defaultValue set explicitly to test the
   defaultValue-preferred branch."
  "pipeline {
     agent { label { label params.nodeLabel } };
     parameters { choice { name 'nodeLabel'; choices ['s390x', 'arm']; defaultValue 'arm' } };
     stages { stage('S') { steps { sh 'true' } } }
   }")

(deftest an6-1-prefers-defaultValue-over-first-choice
  (testing "choice with defaultValue → resolved to that, not the first choices entry"
    (let [ir (t/parse activemq-with-default-shape)
          agent (:agent ir)]
      (is (= "arm" (:label agent)))
      (is (= :default-value (-> agent :inferred-from :source))))))

(def ^:private nested-label-without-params-shape
  "agent { label { label params.X } } with NO parameters block — the
   degradation fallback path. Before AN6-1 this also produced
   :label '<dynamic>' but with the generic agents.edn-miss reason.
   Now the :degrade-reason is :param-driven-label, distinct enough
   for the classifier."
  "pipeline {
     agent { label { label params.someLabel } };
     stages { stage('S') { steps { sh 'true' } } }
   }")

(deftest an6-1-degrades-honestly-without-parameters-block
  (testing "nested-label without parameters → :label '<dynamic>' :degrade-reason :param-driven-label"
    (let [ir (t/parse nested-label-without-params-shape)
          agent (:agent ir)]
      (is (= "<dynamic>" (:label agent)))
      (is (= :param-driven-label (:degrade-reason agent)))
      (is (nil? (:inferred-from agent))
          "no inference made — operator-visible degrade reason only"))))

(deftest an6-1-static-label-unaffected
  (testing "agent { label 'ubuntu' } — classic static form still works unchanged"
    (let [ir (t/parse "pipeline { agent { label 'ubuntu' }; stages { stage('S') { steps { sh 'true' } } } }")
          agent (:agent ir)]
      (is (= "ubuntu" (:label agent)))
      (is (nil? (:inferred-from agent)))
      (is (nil? (:degrade-reason agent))))))

(deftest an6-1-parameters-block-now-structured
  (testing "the :parameters IR field is no longer a raw placeholder"
    (let [ir (t/parse activemq-shape)
          params (:parameters ir)]
      (is (vector? params))
      (is (= 1 (count params)))
      (is (= :choice (-> params first :kind)))
      (is (= "nodeLabel" (-> params first :name)))
      (is (= ["ubuntu" "s390x" "arm" "Windows"] (-> params first :choices))))))
