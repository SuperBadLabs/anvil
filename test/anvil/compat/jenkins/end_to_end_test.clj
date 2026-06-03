(ns anvil.compat.jenkins.end-to-end-test
  "End-to-end: real corpus Jenkinsfile → translator → IR → dispatcher
   → side-effects history. Mirrors spike #4's anvil-integration test,
   now riding the production stack (TX3 + TX4) instead of the spike.

   Same corpus file as the spike (zookeeper/Jenkinsfile-PreCommit) so we
   can assert byte-equivalent semantics: the productized layers behave
   identically to the verified-good spike."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.ir :as ir]))

(def ^:private zookeeper-precommit
  (slurp "test/resources/jenkins-corpus/apache__zookeeper__master__Jenkinsfile-PreCommit.Jenkinsfile"))

(defn- flatten-pipeline-for-orchestrator
  "Squash stages.post and pipeline.post into the stage list so the reference
   orchestrator (which walks :stages → :steps only) sees everything.

   This bridges the gap: the executor in chengis.engine.executor handles
   post hooks natively; the reference orchestrator in chengis.engine.dispatcher
   does not. For TX4's end-to-end test that uses the reference orchestrator,
   we flatten."
  [pipeline-ir]
  (concat
   (for [stage (:stages pipeline-ir)]
     {:name (:name stage)
      :steps (concat (:steps stage)
                     (get-in stage [:post :always] []))})
   (when-let [cleanup (get-in pipeline-ir [:post :cleanup])]
     [{:name "<post.cleanup>" :steps cleanup}])
   (when-let [always (get-in pipeline-ir [:post :always])]
     [{:name "<post.always>" :steps always}])))

(deftest zookeeper-precommit-end-to-end-test
  (testing "the production stack runs the same corpus file as spike #4 did"
    (let [ir (t/parse zookeeper-precommit)
          flat {:stages (vec (flatten-pipeline-for-orchestrator ir))}
          d (ad/make)
          result (d/run-pipeline flat d {:cwd "/workspace"})
          effects @(:effects d)
          types (mapv first effects)]
      (is (= :ok (:status result)))
      ;; Same chronological side-effect signature as spike #4:
      ;;   sh "git clean -fxd"               ← BuildAndTest, step 1
      ;;   sh "mvn verify ..."               ← BuildAndTest, step 2
      ;;   junit "**/TEST-*.xml"             ← BuildAndTest post.always
      ;;   sh "<multi-line cleanup>"         ← cleanup script {}'s named-args sh
      ;;   delete-dir                        ← cleanup script {}'s deleteDir()
      (is (= [:sh :sh :junit :sh :delete-dir] types)
          (str "got: " types))
      (is (= "git clean -fxd" (-> effects (nth 0) second :cmd)))
      (is (str/includes? (-> effects (nth 1) second :cmd) "mvn verify"))
      (is (= "**/target/surefire-reports/TEST-*.xml"
             (-> effects (nth 2) second :results)))
      (is (str/includes? (-> effects (nth 3) second :cmd) "chmod -R u+rxw")))))

(deftest end-to-end-script-block-interleaves-test
  (testing "side effects from inside the script {} block appear in source order
            interleaved with declarative side effects, not batched"
    (let [src "pipeline {
                 agent any
                 stages {
                   stage('Mixed') {
                     steps {
                       sh 'before-script'
                       script {
                         echo 'step-1-in-script'
                         sh 'step-2-in-script'
                         echo 'step-3-in-script'
                       }
                       sh 'after-script'
                     }
                   }
                 }
               }"
          ir (t/parse src)
          flat {:stages (vec (flatten-pipeline-for-orchestrator ir))}
          d (ad/make)
          _ (d/run-pipeline flat d {:cwd "/workspace"})
          types (mapv first @(:effects d))]
      (is (= [:sh :echo :sh :echo :sh] types)))))

(deftest unknown-step-fallback-end-to-end-test
  (testing "an unrecognized step IR node still runs as :jenkins/unknown without failing the pipeline"
    (let [src "pipeline {
                 agent any
                 stages {
                   stage('S') {
                     steps {
                       sh 'real-step'
                       totallyMadeUpStep tool: 'java'
                     }
                   }
                 }
               }"
          ir (t/parse src)
          flat {:stages (vec (flatten-pipeline-for-orchestrator ir))}
          d (ad/make)
          result (d/run-pipeline flat d {})]
      (is (= :ok (:status result))
          "unknown step succeeds (caller decides what to do; we don't crash)")
      (let [evs @(:effects d)]
        (is (= [:sh :unknown] (mapv first evs)))
        (is (= "totallyMadeUpStep" (-> evs second second :name)))))))
