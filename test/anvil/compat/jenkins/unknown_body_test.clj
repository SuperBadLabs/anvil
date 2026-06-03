(ns anvil.compat.jenkins.unknown-body-test
  "Tier-3 worthiness fix — translator attaches `:body` to unknown calls
   whose last arg is a closure. This is what unlocks the dispatcher's
   h-unknown body-execution path so nested KNOWN steps inside unknown
   wrappers (`withChecks { realtimeJUnit { infra.runMaven } }`) still
   run instead of being silently dropped."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.jenkins.translator :as t]))

(defn- find-step
  "Walk an IR pipeline shape and yield every step whose :type matches `kw`,
   recursively descending into :stages → :steps and :body."
  [ir kw]
  (cond
    (map? ir)
    (concat (when (= kw (:type ir)) [ir])
            (find-step (:stages ir) kw)
            (find-step (:steps ir) kw)
            (find-step (:body ir) kw))
    (sequential? ir)
    (mapcat #(find-step % kw) ir)
    :else nil))

(deftest unknown-block-step-attaches-body
  (let [source "pipeline { agent any; stages { stage('s') { steps {
                  realtimeJUnit(testResults: 'x.xml') {
                    sh 'echo inner'
                  }
                } } } }"
        ir (t/parse source "test.Jenkinsfile")
        unknowns (find-step ir :jenkins/unknown)]
    (testing "the realtimeJUnit call comes through as :jenkins/unknown"
      (is (= 1 (count unknowns)))
      (is (= "realtimeJUnit" (-> unknowns first :name))))
    (testing "its :body carries the translated inner step"
      (let [body (-> unknowns first :body)]
        (is (seq body) "unknown block step keeps its closure body")
        (is (= :jenkins/sh (-> body first :type)))
        (is (re-find #"echo inner" (-> body first :script)))))))

(deftest unknown-leaf-step-has-no-body
  (testing "an unknown call WITHOUT a closure (leaf) does not get :body"
    (let [source "pipeline { agent any; stages { stage('s') { steps {
                    weirdLeafCall(foo: 'bar')
                  } } } }"
          ir (t/parse source "leaf.Jenkinsfile")
          unknowns (find-step ir :jenkins/unknown)]
      (is (= 1 (count unknowns)))
      (is (nil? (-> unknowns first :body))))))
