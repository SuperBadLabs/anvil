(ns anvil.compat.jenkins.scm-lifecycle-test
  "Unit tests for AN8-4 declarative-pipeline implicit `checkout scm`
   injection.

   The integration with `scm/provision!` is exercised by
   `scm-test` (already in the suite); these tests cover the IR walker
   in isolation — pure data in, pure data out — plus the dispatcher
   handler's branching on `:implicit?` + `:scm` ctx."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.ir :as ir]
            [anvil.compat.jenkins.scm-lifecycle :as scml]
            [anvil.compat.jenkins.translator :as t]))

;; ---------------------------------------------------------------------------
;; declarative? predicate
;; ---------------------------------------------------------------------------

(deftest declarative?-recognizes-translated-declarative
  (testing "translator output for a minimal declarative pipeline is detected"
    (let [pipe-ir (t/parse "pipeline {
                              agent any
                              stages {
                                stage('Build') { steps { sh 'make' } }
                              }
                            }")]
      (is (scml/declarative? pipe-ir)))))

(deftest declarative?-rejects-scripted-tagged-ir
  (testing "scripted-eval IR (the :scripted-pipeline? marker on :options) is not declarative"
    (let [scripted-ir (ir/pipeline {:stages [{:name "x" :steps [{:type :jenkins/sh :script "ls"}]}]
                                    :options [{:scripted-pipeline? true}]})]
      (is (not (scml/declarative? scripted-ir))))))

(deftest declarative?-rejects-empty-stages
  (testing "parse-error IR with no stages is not declarative"
    (let [err-ir (ir/pipeline {:stages []
                               :options [{:parse-error :no-pipeline-block}]})]
      (is (not (scml/declarative? err-ir))))))

(deftest declarative?-rejects-non-pipeline
  (is (not (scml/declarative? nil)))
  (is (not (scml/declarative? {})))
  (is (not (scml/declarative? {:source :jenkins}))))

;; ---------------------------------------------------------------------------
;; needs-implicit-checkout? predicate
;; ---------------------------------------------------------------------------

(deftest needs-implicit-checkout?-true-for-bare-declarative
  (let [pipe-ir (t/parse "pipeline {
                            agent any
                            stages {
                              stage('Build') { steps { sh 'make' } }
                            }
                          }")]
    (is (scml/needs-implicit-checkout? pipe-ir))))

(deftest needs-implicit-checkout?-false-when-explicit-checkout-present
  (let [pipe-ir (t/parse "pipeline {
                            agent any
                            stages {
                              stage('Build') {
                                steps {
                                  checkout scm
                                  sh 'make'
                                }
                              }
                            }
                          }")]
    (is (not (scml/needs-implicit-checkout? pipe-ir))
        (str "stage 1 has explicit checkout: " (pr-str (-> pipe-ir :stages first))))))

(deftest needs-implicit-checkout?-false-for-scripted
  (let [scripted-ir (ir/pipeline {:stages [{:name "x" :steps [{:type :jenkins/sh :script "ls"}]}]
                                  :options [{:scripted-pipeline? true}]})]
    (is (not (scml/needs-implicit-checkout? scripted-ir)))))

;; ---------------------------------------------------------------------------
;; inject-implicit-checkout — IR rewrite
;; ---------------------------------------------------------------------------

(deftest inject-no-op-when-flag-off
  (let [pipe-ir (t/parse "pipeline { agent any
                                     stages { stage('B') { steps { sh 'x' } } } }")
        out (scml/inject-implicit-checkout pipe-ir {:scm {:type :git :url "u" :branch "b"}
                                                     :flag-enabled? false})]
    (is (= pipe-ir out) "flag off → identity transform")))

(deftest inject-no-op-when-scripted
  (let [scripted-ir (ir/pipeline {:stages [{:name "x" :steps [{:type :jenkins/sh :script "ls"}]}]
                                  :options [{:scripted-pipeline? true}]})
        out (scml/inject-implicit-checkout scripted-ir {:scm {:type :git :url "u" :branch "b"}
                                                         :flag-enabled? true})]
    (is (= scripted-ir out) "scripted → identity transform")))

(deftest inject-no-op-when-explicit-checkout
  (let [pipe-ir (t/parse "pipeline { agent any
                                     stages { stage('B') { steps { checkout scm; sh 'x' } } } }")
        out (scml/inject-implicit-checkout pipe-ir {:scm {:type :git :url "u" :branch "b"}
                                                     :flag-enabled? true})]
    (is (= pipe-ir out)
        (str "explicit checkout present → no synthetic step injected: "
             (pr-str (-> out :stages first :steps))))))

(deftest inject-prepends-synthetic-step
  (let [pipe-ir (t/parse "pipeline { agent any
                                     stages { stage('B') { steps { sh 'x' } } } }")
        scm-cfg {:type :git :url "https://example.com/repo.git" :branch "main"}
        out (scml/inject-implicit-checkout pipe-ir {:scm scm-cfg
                                                     :flag-enabled? true})
        stage1-steps (-> out :stages first :steps)
        first-step (first stage1-steps)]
    (testing "synthetic checkout is the first step of stage 1"
      (is (= :jenkins/checkout (:type first-step)))
      (is (true? (:implicit? first-step)))
      (is (= :scm-lifecycle (:anvil/source first-step)))
      (is (= scm-cfg (:scm first-step))))
    (testing "the original sh step still runs second"
      (is (= 2 (count stage1-steps)))
      (is (= :jenkins/sh (:type (second stage1-steps))))
      (is (= "x" (:script (second stage1-steps)))))
    (testing "other stages are untouched"
      (is (= (rest (:stages pipe-ir)) (rest (:stages out)))))))

(deftest inject-is-idempotent
  (testing "running the injector twice doesn't double-inject — the synthetic step trips `explicit-checkout?`"
    (let [pipe-ir (t/parse "pipeline { agent any
                                       stages { stage('B') { steps { sh 'x' } } } }")
          opts {:scm {:type :git :url "u" :branch "b"} :flag-enabled? true}
          once (scml/inject-implicit-checkout pipe-ir opts)
          twice (scml/inject-implicit-checkout once opts)]
      (is (= once twice))
      (is (= 2 (count (-> twice :stages first :steps)))
          "still exactly two steps — synthetic checkout + original sh"))))

(deftest inject-without-scm-still-injects-honest-skip
  (testing "no :scm on the job — the IR still gets the synthetic step (handler will record :skipped)"
    (let [pipe-ir (t/parse "pipeline { agent any
                                       stages { stage('B') { steps { sh 'x' } } } }")
          out (scml/inject-implicit-checkout pipe-ir {:scm nil :flag-enabled? true})
          first-step (-> out :stages first :steps first)]
      (is (= :jenkins/checkout (:type first-step)))
      (is (true? (:implicit? first-step)))
      (is (nil? (:scm first-step))))))

;; ---------------------------------------------------------------------------
;; Zookeeper real Jenkinsfile (wild-corpus) — the AN7-5c motivating case
;; ---------------------------------------------------------------------------

(def ^:private zookeeper-real-jenkinsfile
  "Mirror of /tmp/anvil-broad/apache-zookeeper/Jenkinsfile — the matrix-
   declarative shape that AN7-5c documented failing on `git clean -fxd`
   exit 128 in 863 ms because the workspace was never checked out."
  "pipeline {
       agent { label 'Hadoop' }
       options { disableConcurrentBuilds() }
       stages {
           stage('Prepare') {
               matrix {
                   agent any
                   axes {
                       axis { name 'JAVA_VERSION'; values 'jdk_1.8_latest', 'jdk_11_latest' }
                   }
                   stages {
                       stage('BuildAndTest') {
                           steps {
                               sh 'git clean -fxd'
                               sh 'mvn verify'
                           }
                       }
                   }
               }
           }
       }
   }")

(deftest zookeeper-real-jenkinsfile-gets-implicit-checkout
  (testing "AN8-4 fixes the AN7-5c zookeeper failure — stage 1 of the real Jenkinsfile gets an implicit checkout when the flag is on"
    (let [pipe-ir (t/parse zookeeper-real-jenkinsfile)]
      (is (scml/declarative? pipe-ir)
          (str "zookeeper Jenkinsfile parses as declarative: "
               (pr-str (-> pipe-ir :options))))
      (is (scml/needs-implicit-checkout? pipe-ir)
          (str "zookeeper has no explicit `checkout scm` — must need implicit injection: "
               (pr-str (-> pipe-ir :stages first :steps))))
      (let [out (scml/inject-implicit-checkout
                 pipe-ir
                 {:scm {:type :git
                        :url "https://github.com/apache/zookeeper.git"
                        :branch "master"}
                  :flag-enabled? true})
            first-step (-> out :stages first :steps first)]
        (is (= :jenkins/checkout (:type first-step)))
        (is (true? (:implicit? first-step)))
        (is (= "https://github.com/apache/zookeeper.git"
               (-> first-step :scm :url)))))))
