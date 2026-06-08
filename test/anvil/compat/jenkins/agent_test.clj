(ns anvil.compat.jenkins.agent-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.agent :as agent]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.translator :as t]))

(deftest agent-spec-resolution-test
  (testing "stage-level agent overrides pipeline-level agent"
    (is (= {:label "stage-only"}
           (agent/agent-spec {:label "pipeline-default"} {:label "stage-only"})))
    (is (= {:label "pipeline-default"}
           (agent/agent-spec {:label "pipeline-default"} nil)))
    (is (nil? (agent/agent-spec nil nil)))))

(deftest agent-summary-test
  (testing "every supported agent shape renders to a readable summary"
    (is (= "any"               (agent/agent-summary {:type :any})))
    (is (= "none"              (agent/agent-summary {:type :none})))
    (is (= "label:linux"       (agent/agent-summary {:label "linux"})))
    (is (= "docker:node:18"    (agent/agent-summary {:docker {:image "node:18"}})))
    (is (= "node-label:big"    (agent/agent-summary {:type :node-label :label "big"})))
    ;; v0.6 T1: k8s is no longer "(UNSUPPORTED)" — the summary names
    ;; the image (or :raw-form when image isn't extractable).
    (is (= "kubernetes:busybox:1.36"
           (agent/agent-summary {:kubernetes {:image "busybox:1.36"
                                              :raw-form :yaml}})))
    (is (= "kubernetes:yaml"
           (agent/agent-summary {:kubernetes {:raw-form :yaml}})))
    (is (= "<missing>"         (agent/agent-summary nil)))))

(deftest kubernetes-no-longer-rejected-test
  (testing "v0.6 T1 retires the blanket kubernetes-rejection — the
            importer accepts the IR; runtime degrades honestly when
            the :k8s-agent flag is off."
    (is (false? (agent/rejected? {:kubernetes {:image "busybox:1.36"}})))
    (is (nil? (agent/rejection-reason {:kubernetes {:image "busybox:1.36"}})))))

(deftest wrap-pipeline-with-agent-events-test
  (testing "wrap-pipeline-with-agent-events brackets each stage's steps with
            agent enter/leave synthetic steps so the dispatcher can record
            which agent runs which stage"
    (let [pipeline {:agent {:label "top-default"}
                    :stages [{:name "Build"
                              :steps [{:type :jenkins/sh :script "make"}]}
                             {:name "Test"
                              :agent {:docker {:image "node:18"}}
                              :steps [{:type :jenkins/sh :script "npm test"}]}]}
          wrapped (agent/wrap-pipeline-with-agent-events pipeline)
          build-stage (-> wrapped :stages first)
          test-stage  (-> wrapped :stages second)]
      ;; Build: top-default agent
      (is (= [:jenkins/agent-stage-enter :jenkins/sh :jenkins/agent-stage-leave]
             (mapv :type (:steps build-stage))))
      (is (= {:label "top-default"}
             (-> build-stage :steps first :agent))
          "stage 'Build' inherits the top-level agent")

      ;; Test: stage-level docker override
      (is (= {:docker {:image "node:18"}}
             (-> test-stage :steps first :agent))
          "stage 'Test' uses its own docker agent override"))))

(deftest end-to-end-agent-events-flow-through-dispatcher-test
  (testing "wrapped pipeline runs end-to-end; agent enter/leave land in the side effects"
    (let [ir (t/parse "pipeline {
                         agent { label 'small' }
                         stages {
                           stage('Build') {
                             steps { sh 'compile' }
                           }
                           stage('Bigjob') {
                             agent { docker { image 'big-image:1' } }
                             steps { sh 'crunch' }
                           }
                         }
                       }")
          wrapped (agent/wrap-pipeline-with-agent-events ir)
          flat {:stages (mapv #(select-keys % [:name :steps]) (:stages wrapped))}
          dispatcher (ad/make)
          result (d/run-pipeline flat dispatcher {})]
      (is (= :ok (:status result)))
      (let [evs @(:effects dispatcher)
            agent-events (filter #(#{:agent/stage-enter :agent/stage-leave} (first %)) evs)
            enters (filter #(= :agent/stage-enter (first %)) evs)]
        (is (= 4 (count agent-events)) "two enter + two leave events")
        (is (= 2 (count enters)))
        (let [build-enter (first enters)
              big-enter   (second enters)]
          (is (= "Build"  (-> build-enter second :stage)))
          (is (= "label:small" (-> build-enter second :summary)))
          (is (= "Bigjob" (-> big-enter second :stage)))
          (is (= "docker:big-image:1" (-> big-enter second :summary))))))))

(deftest k8s-stage-no-longer-rejected-test
  (testing "v0.6 T1: a stage with agent { kubernetes ... } no longer
            records :rejected? in its enter event — the IR is preserved
            and the runtime honors-or-degrades based on the :k8s-agent
            feature flag."
    (let [pipeline {:stages [{:name "K8sStage"
                              :agent {:kubernetes {:image "busybox:1.36"
                                                   :raw-form :yaml}}
                              :steps [{:type :jenkins/sh :script "uname"}]}]}
          wrapped (agent/wrap-pipeline-with-agent-events pipeline)
          flat {:stages (mapv #(select-keys % [:name :steps]) (:stages wrapped))}
          dispatcher (ad/make)
          _ (d/run-pipeline flat dispatcher {})
          evs @(:effects dispatcher)
          enter (first (filter #(= :agent/stage-enter (first %)) evs))]
      (is (not (-> enter second :rejected?))
          "k8s import no longer records :rejected? — runtime decides honestly"))))
