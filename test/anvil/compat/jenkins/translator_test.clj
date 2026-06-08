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

;; ---------------------------------------------------------------------------
;; v0.4 AN6-2 — nested-stages flattening (epsilon + cxf shapes)
;; ---------------------------------------------------------------------------

(def ^:private epsilon-shape
  "Mirrors the eclipse-epsilon Jenkinsfile shape that fell through to
   :unsupported translator.body-skipped in v0.3.3.  A wrapper stage
   ('Main') groups N sibling stages via `stages { stage { … } stage { … } }`."
  "pipeline {
     agent any;
     stages {
       stage('Main') {
         stages {
           stage('Build') { steps { sh 'mvn install' } };
           stage('Test')  { steps { sh 'mvn test' } }
         }
       }
     }
   }")

(deftest an6-2-epsilon-nested-stages-flatten-with-prefix
  (testing "stage('Main') { stages { stage('Build'), stage('Test') } } flattens to 2 sibling stages with 'Main / X' names"
    (let [ir (t/parse epsilon-shape)
          stages (:stages ir)
          names (mapv :name stages)]
      (is (= 2 (count stages))
          "wrapper stage materializes as its two children")
      (is (= ["Main / Build" "Main / Test"] names)
          "wrapper name prefixes each child")
      (is (= "mvn install"
             (-> stages first :steps first :script))
          "child :steps survive the flatten unchanged")
      (is (= "mvn test"
             (-> stages second :steps first :script))))))

(def ^:private cxf-shape
  "Mirrors the apache-cxf Jenkinsfile's matrix→stages→stage→stages
   chain.  matrix-stage runs `agent { label }` + `tools { jdk … }`
   per cell, then nested-stages groups the actual build steps."
  "pipeline {
     agent none;
     stages {
       stage('Build') {
         matrix {
           agent { label 'ubuntu' };
           axes { axis { name 'JDK'; values '17', '21' } };
           stages {
             stage('JDK build') {
               agent { label 'ubuntu' };
               stages {
                 stage('Compile') { steps { sh 'mvn -B install' } }
               }
             }
           }
         }
       }
     }
   }")

(deftest an6-2-cxf-matrix-with-nested-stages-fully-expands
  (testing "matrix → stages → stage → nested-stages expands across both dimensions"
    (let [ir (t/parse cxf-shape)
          stages (:stages ir)]
      (is (= 2 (count stages))
          "2 axis values × 1 inner stage = 2 cells (the nested-stages
           flattens inside each cell's content)")
      (let [names (mapv :name stages)]
        ;; Each cell carries the matrix prefix `Build [JDK=…]` from
        ;; expand-matrix-stage, then the inner stage steps come from
        ;; the nested-stages flatten.
        (is (every? #(re-find #"^Build " %) names)
            (str "every cell prefixed with matrix-parent name; got " names)))
      (is (every? #(= "mvn -B install"
                      (-> % :steps first :script))
                  stages)
          "the deeply-nested sh step survives expansion in every cell"))))

(deftest an6-2-static-stages-still-translate-unchanged
  (testing "a stage with both steps AND no nested children is still a leaf stage (regression)"
    (let [ir (t/parse "pipeline { agent any; stages { stage('Solo') { steps { sh 'one' } } } }")
          stages (:stages ir)]
      (is (= 1 (count stages)))
      (is (= "Solo" (-> stages first :name)))
      (is (= "one" (-> stages first :steps first :script))))))

(deftest an6-2-wrapper-agent-propagates-to-children
  (testing "an outer stage's `agent { label 'foo' }` becomes each flattened child's :agent"
    (let [src "pipeline {
                 agent any;
                 stages {
                   stage('Main') {
                     agent { label 'maven-21' };
                     stages {
                       stage('A') { steps { sh 'a' } };
                       stage('B') { steps { sh 'b' } }
                     }
                   }
                 }
               }"
          ir (t/parse src)
          stages (:stages ir)]
      (is (= ["Main / A" "Main / B"] (mapv :name stages)))
      (is (every? #(= "maven-21" (-> % :agent :label)) stages)
          "every flattened child inherits the wrapper's agent label"))))

;; ---------------------------------------------------------------------------
;; #243 — container() arg-shape coverage.  Surfaced by the v0.4 T2.6
;; fixture dogfood: `container(image: 'X')` was yielding :image nil
;; because translate-container did `(get … "image")` (string key) on a
;; map-arg-kv result that uses keyword keys.  These tests lock all
;; three forms parser shape → translator :image lift.
;; ---------------------------------------------------------------------------

(defn- container-step
  "Pull the first stage's first step out of an IR (always a
   :jenkins/container in these tests)."
  [src]
  (-> (t/parse src) :stages first :steps first))

(deftest container-positional-string-arg-extracts-image
  (testing "container('img') { … } — positional string lifts to :image"
    (let [step (container-step
                "pipeline { agent any; stages { stage('S') { steps {
                   container('node:20') { sh 'npm test' }
                 } } } }")]
      (is (= :jenkins/container (:type step)))
      (is (= "node:20" (:image step))
          "positional string arg sets :image — TX5/T2.1 baseline"))))

(deftest container-map-form-arg-extracts-image
  (testing "container(image: 'img') { … } — map-form named arg lifts to :image"
    (let [step (container-step
                "pipeline { agent any; stages { stage('S') { steps {
                   container(image: 'eclipse-temurin:21') { sh 'java -version' }
                 } } } }")]
      (is (= :jenkins/container (:type step)))
      (is (= "eclipse-temurin:21" (:image step))
          "#243 — map-form arg now resolves to the image string instead of nil")))
  (testing "extra map keys are ignored, :image still resolves"
    (let [step (container-step
                "pipeline { agent any; stages { stage('S') { steps {
                   container(image: 'maven:3.9', shell: '/bin/bash') { sh 'mvn -v' }
                 } } } }")]
      (is (= "maven:3.9" (:image step))
          "shell:/bin/bash etc. don't block :image lookup"))))

(deftest container-both-arg-forms-produce-identical-ir
  (testing "positional and map-form yield byte-identical IR for the container step"
    (let [pos (container-step
               "pipeline { agent any; stages { stage('S') { steps {
                  container('python:3.12') { sh 'pytest' }
                } } } }")
          mp  (container-step
               "pipeline { agent any; stages { stage('S') { steps {
                  container(image: 'python:3.12') { sh 'pytest' }
                } } } }")]
      (is (= pos mp)
          "the two surface forms parse to the same IR — operators choose
           whichever Jenkinsfile style they prefer without observable
           behavior difference downstream"))))

(deftest container-non-resolvable-arg-still-emits-step-with-nil-image
  (testing "container(env.X) { … } — non-string/non-map arg → :image nil + body preserved"
    (let [step (container-step
                "pipeline { agent any; stages { stage('S') { steps {
                   container(env.CONTAINER) { sh 'work' }
                 } } } }")]
      (is (= :jenkins/container (:type step)))
      (is (nil? (:image step))
          "honest gap — dispatcher emits :container/missing-image at runtime
           so the build doesn't silently drop the body"))))

;; ---------------------------------------------------------------------------
;; AN7-2 — ${X} GString interpolation in declarative-pipeline string contexts
;; ---------------------------------------------------------------------------

(deftest an7-2-gstring-agent-label-resolved-from-choice-param
  (testing "apache-camel style: agent { label \"${PLATFORM}\" } with choice parameter"
    ;; apache-camel's real Jenkinsfile: agent { label "${PLATFORM}" }
    ;; with `parameters { choice(name:'PLATFORM', choices:['linux','windows']) }`
    (let [ir (t/parse
              "pipeline {
                 parameters {
                   choice(name: 'PLATFORM', choices: ['linux', 'windows'], description: '')
                 }
                 agent { label \"${PLATFORM}\" }
                 stages {
                   stage('Build') {
                     steps { sh 'make' }
                   }
                 }
               }")]
      (is (= "linux" (get-in ir [:agent :label]))
          "choice defaultValue (first choice) used when no explicit defaultValue")
      ;; Groovy's GStringExpression.getText() normalizes "${PLATFORM}" → "$PLATFORM"
      ;; (no curly braces in the cdata text). The interpolated-from field carries
      ;; whichever form the cdata delivered — tests must match that form.
      (is (some? (get-in ir [:agent :interpolated-from]))
          "interpolated-from carries the original GString template text"))))

(deftest an7-2-gstring-agent-label-resolved-from-string-param-default
  (testing "agent { label \"${PLATFORM}\" } with string parameter and defaultValue"
    (let [ir (t/parse
              "pipeline {
                 parameters {
                   string(name: 'PLATFORM', defaultValue: 'linux', description: 'Build platform')
                 }
                 agent { label \"${PLATFORM}\" }
                 stages {
                   stage('Build') {
                     steps { sh 'make' }
                   }
                 }
               }")]
      (is (= "linux" (get-in ir [:agent :label]))
          "string param defaultValue substituted into agent label")
      (is (some? (get-in ir [:agent :interpolated-from]))))))

(deftest an7-2-gstring-agent-label-multi-var-resolved
  (testing "multi-variable GString: agent { label \"${OS}-${ARCH}\" } both resolved"
    ;; Two parameters, both with defaults → concat substitution
    (let [ir (t/parse
              "pipeline {
                 parameters {
                   string(name: 'OS', defaultValue: 'linux', description: '')
                   string(name: 'ARCH', defaultValue: 'amd64', description: '')
                 }
                 agent { label \"${OS}-${ARCH}\" }
                 stages {
                   stage('Build') {
                     steps { sh 'build' }
                   }
                 }
               }")]
      (is (= "linux-amd64" (get-in ir [:agent :label]))
          "both variables substituted into label string")
      (is (some? (get-in ir [:agent :interpolated-from]))))))

(deftest an7-2-gstring-agent-label-unresolvable-honest-fallback
  (testing "agent { label \"${PLATFORM}\" } — no parameter block → honest unresolved"
    ;; No parameters block at all → PLATFORM cannot be resolved
    ;; Per AV5-6: emit :unresolved-interpolation, NOT fake static label
    (let [ir (t/parse
              "pipeline {
                 agent { label \"${PLATFORM}\" }
                 stages {
                   stage('Build') {
                     steps { sh 'make' }
                   }
                 }
               }")]
      (is (= :unresolved-interpolation (get-in ir [:agent :degrade-reason]))
          "no parameters → :unresolved-interpolation degrade-reason per AV5-6")
      (is (some? (get-in ir [:agent :gstring-template]))
          "original template preserved in IR for the dispatcher effect")
      (is (some #{"PLATFORM"} (get-in ir [:agent :unresolved-vars]))
          "unresolved variable name surfaced in IR"))))

(deftest an7-2-gstring-sh-bodies-untouched
  (testing "R5 constraint: sh '...' bodies with ${X} are NOT interpolated"
    ;; This is the critical anti-regression: ${X} inside sh '' must NOT be
    ;; touched — it's bash syntax, not Groovy. The translator must leave
    ;; sh script bodies exactly as-is.
    (let [ir (t/parse
              "pipeline {
                 parameters {
                   string(name: 'VERSION', defaultValue: '1.0', description: '')
                 }
                 agent any
                 stages {
                   stage('Build') {
                     steps {
                       sh 'echo build ${VERSION}'
                       sh \"echo build ${VERSION}\"
                     }
                   }
                 }
               }")
          steps (get-in ir [:stages 0 :steps])]
      (is (= 2 (count steps)))
      ;; Single-quoted sh: ${VERSION} is a bash variable — must be verbatim
      (is (= "echo build ${VERSION}" (get-in steps [0 :script]))
          "sh single-quote body left verbatim")
      ;; Double-quoted sh: The GString interpolation in the translator applies
      ;; only to the cdata path for agent/environment contexts. The sh step
      ;; translator (translate-sh) keeps the GString text AS-IS per R5.
      ;; For double-quoted sh, the cdata :gstring node's :text is used directly
      ;; (see translate-sh line ~152: `(ir/step-sh (:text a))`).
      ;; The sh handler must NOT call interpolate-gstring.
      (is (string? (get-in steps [1 :script]))
          "sh double-quote step produces a string script (no interpolation leakage)")
      ;; The key assertion: the VERSION variable is NOT substituted to "1.0"
      ;; in the sh body — that would be a bash context leak
      (is (not= "echo build 1.0" (get-in steps [1 :script]))
          "R5: ${VERSION} NOT substituted in sh body — would leak Groovy interpolation into bash"))))

(deftest an7-2-gstring-partial-unresolvable
  (testing "agent { label \"${OS}-${UNKNOWN}\" } — one var resolvable, one not → unresolved"
    ;; Only OS has a parameter; UNKNOWN doesn't → whole interpolation fails honestly
    (let [ir (t/parse
              "pipeline {
                 parameters {
                   string(name: 'OS', defaultValue: 'linux', description: '')
                 }
                 agent { label \"${OS}-${UNKNOWN}\" }
                 stages {
                   stage('Build') {
                     steps { sh 'make' }
                   }
                 }
               }")]
      (is (= :unresolved-interpolation (get-in ir [:agent :degrade-reason]))
          "partial resolution → whole interpolation fails, not silently degraded")
      (is (some #{"UNKNOWN"} (get-in ir [:agent :unresolved-vars]))
          "only the unresolvable variable is listed")
      (is (not (some #{"OS"} (get-in ir [:agent :unresolved-vars])))
          "the resolvable variable is NOT in the unresolved list"))))

