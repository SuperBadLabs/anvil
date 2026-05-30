(ns anvil.cli.chengisfile-renderer-test
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.translator :as t]
            [anvil.cli.chengisfile-renderer :as r]))

(defn- render-source [src]
  (-> src t/parse r/render))

(deftest sh-becomes-run-test
  (testing "a :jenkins/sh step renders to a Chengisfile-native {:run ...}"
    (let [out (render-source
               "pipeline { agent any; stages { stage('Build') {
                  steps {
                    sh 'make compile'
                    sh 'make test'
                  }
                } } }")
          steps (-> out :stages first :steps)]
      (is (= 2 (count steps)))
      (is (= {:run "make compile"} (dissoc (first steps) :jenkins-line)))
      (is (= {:run "make test"}    (dissoc (second steps) :jenkins-line))))))

(deftest junit-renders-as-publish-test
  (testing "junit step renders to :publish-junit"
    (let [out (render-source
               "pipeline { agent any; stages { stage('S') {
                  steps {
                    junit testResults: '**/TEST-*.xml', allowEmptyResults: true
                  }
                } } }")
          step (-> out :stages first :steps first)]
      (is (= :publish-junit (:type step)))
      (is (= "**/TEST-*.xml" (:results step)))
      (is (true? (:allow-empty? step))))))

(deftest scope-wrappers-preserve-structure-test
  (testing "withEnv body steps are recursively rendered"
    (let [out (render-source
               "pipeline { agent any; stages { stage('S') {
                  steps {
                    withEnv(['FOO=bar']) {
                      sh 'echo $FOO'
                    }
                  }
                } } }")
          step (-> out :stages first :steps first)]
      (is (= :with-env (:type step)))
      (is (= ["FOO=bar"] (:env-strings step)))
      (is (= 1 (count (:body step))))
      (is (= "echo $FOO" (-> step :body first :run))))))

(deftest with-credentials-attaches-fixme-test
  (testing "withCredentials gets a :FIXME annotation for the importer report"
    (let [out (render-source
               "pipeline { agent any; stages { stage('S') {
                  steps {
                    withCredentials([usernamePassword(credentialsId: 'x',
                                                     usernameVariable: 'U',
                                                     passwordVariable: 'P')]) {
                      sh 'do-stuff'
                    }
                  }
                } } }")
          step (-> out :stages first :steps first)]
      (is (= :with-credentials (:type step)))
      (is (some? (:FIXME step))))))

(deftest unknown-step-gets-fixme-test
  (testing "an unrecognized step renders as :jenkins-unsupported with FIXME"
    (let [out (render-source
               "pipeline { agent any; stages { stage('S') {
                  steps {
                    totallyMadeUpStep param: 'foo'
                  }
                } } }")
          step (-> out :stages first :steps first)]
      (is (= :jenkins-unsupported (:type step)))
      (is (= "totallyMadeUpStep" (:name step)))
      (is (some? (:FIXME step))))))

(deftest plugin-step-default-renders-test
  (testing "known plugin step types render via :default with original-type preserved"
    (let [out (render-source
               "pipeline { agent any; stages { stage('S') {
                  steps {
                    recordIssues tool: 'java'
                  }
                } } }")
          step (-> out :stages first :steps first)]
      (is (= :jenkins-plugin-step (:type step)))
      (is (= :jenkins/record-issues (:original-type step)))
      (is (some? (:FIXME step))))))

(deftest fixme-report-counts-correctly-test
  (testing "fixme-report tallies FIXMEs and unsupported names across the file"
    (let [out (render-source
               "pipeline { agent any; stages { stage('S') {
                  steps {
                    sh 'real'
                    totallyMadeUpStep one: 1
                    totallyMadeUpStep two: 2
                    recordIssues tool: 'x'
                  }
                } } }")
          report (r/fixme-report out)]
      (is (= 3 (count (:fixmes report))))
      (is (= 2 (get (:unsupported report) "totallyMadeUpStep"))))))

(deftest post-actions-flow-through-test
  (testing "post block makes it into the rendered output"
    (let [out (render-source
               "pipeline { agent any; stages { stage('S') {
                  steps { sh 'x' }
                  post {
                    always { junit '**/test/*.xml' }
                  }
                } } }")
          stage (-> out :stages first)]
      (is (some? (-> stage :post)))
      (is (= :publish-junit (-> stage :post :always first :type))))))
