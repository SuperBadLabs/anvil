(ns anvil.compat.jenkins.dispatcher-test
  "AnvilJenkinsDispatcher unit tests + end-to-end with the chengis-core
   reference orchestrator."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as ad]))

(defn- effects [dispatcher]
  @(:effects dispatcher))

(deftest leaf-step-handlers-test
  (testing "each leaf step type lands its expected effect entry"
    (let [d (ad/make)
          ctx {:cwd "/workspace"}]
      (d/dispatch d {:type :jenkins/sh :script "make"} ctx)
      (d/dispatch d {:type :jenkins/echo :message "hello"} ctx)
      (d/dispatch d {:type :jenkins/junit :results "**/TEST-*.xml"} ctx)
      (d/dispatch d {:type :jenkins/delete-dir} ctx)
      (d/dispatch d {:type :jenkins/checkout :spec "main"} ctx)
      (let [evs (effects d)]
        (is (= 5 (count evs)))
        (is (= :sh (-> evs (nth 0) first)))
        (is (= "make" (-> evs (nth 0) second :cmd)))
        (is (= "/workspace" (-> evs (nth 0) second :cwd)))
        (is (= [:echo "hello"] (nth evs 1)))
        (is (= :junit (-> evs (nth 2) first)))
        (is (= "**/TEST-*.xml" (-> evs (nth 2) second :results)))
        (is (= :delete-dir (-> evs (nth 3) first)))
        (is (= :checkout (-> evs (nth 4) first)))
        (is (= "main" (-> evs (nth 4) second :spec)))))))

(deftest sh-returnstdout-uses-canned-test
  (testing "return-stdout? returns the canned stdout"
    (let [d (ad/make {:sh-canned (atom {"version" "1.2.3"})})
          result (d/dispatch d {:type :jenkins/sh
                                :script "version"
                                :return-stdout? true}
                             {})]
      (is (= "1.2.3" (:stdout result))))))

(deftest error-step-fails-test
  (testing "the :jenkins/error step returns :failed status"
    (let [d (ad/make)
          result (d/dispatch d {:type :jenkins/error :message "boom"} {})]
      (is (= :failed (:status result)))
      (is (= :jenkins-error (:error result))))))

(deftest dir-scope-wraps-body-test
  (testing "declarative dir IR pushes/pops cwd and dispatches body steps"
    (let [d (ad/make)
          step {:type :jenkins/dir
                :path "subdir"
                :body [{:type :jenkins/sh :script "inside-1"}
                       {:type :jenkins/sh :script "inside-2"}]}
          ctx {:cwd "/workspace"}
          result (d/dispatch d step ctx)]
      (is (= :ok (:status result)))
      (is (= "/workspace" (-> result :ctx :cwd)) "cwd restored")
      (let [evs (effects d)]
        ;; Expected: dir/enter, sh "inside-1", sh "inside-2", dir/leave
        (is (= [:dir/enter :sh :sh :dir/leave]
               (mapv first evs)))
        (is (= "/workspace/subdir" (-> evs (nth 0) second)))
        (is (= "/workspace/subdir" (-> evs (nth 1) second :cwd)))))))

(deftest unknown-step-records-name-test
  (testing "an unrecognized step type is captured as :unknown with name + args"
    (let [d (ad/make)
          result (d/dispatch d {:type :jenkins/unknown
                                :name "recordIssues"
                                :args [{:tool "java"}]}
                             {})]
      (is (= :ok (:status result)))
      (let [ev (-> (effects d) last)]
        (is (= :unknown (first ev)))
        (is (= "recordIssues" (-> ev second :name)))))))

;; ---------------------------------------------------------------------------
;; End-to-end with the chengis-core reference orchestrator
;; ---------------------------------------------------------------------------

(deftest end-to-end-jenkins-pipeline-test
  (testing "a Jenkins-shaped pipeline runs end-to-end via chengis.engine.dispatcher"
    (let [d (ad/make {:sh-canned (atom {"git rev-parse HEAD" "abc123"})})
          pipeline {:stages [{:name "Build"
                              :steps [{:type :jenkins/sh :script "make"}
                                      {:type :jenkins/sh :script "make test"}]}
                             {:name "Publish"
                              :steps [{:type :jenkins/junit :results "**/*.xml"}
                                      {:type :jenkins/archive-artifacts
                                       :artifacts "target/*.jar"}]}]}
          result (d/run-pipeline pipeline d {})]
      (is (= :ok (:status result)))
      (is (= 2 (count (:stages result))))
      (let [evs (effects d)]
        (is (= [:sh :sh :junit :archive] (mapv first evs)))))))

(deftest script-block-via-runtime-test
  (testing "a :jenkins/script step routes to the runtime; its internal effects interleave"
    (let [d (ad/make)
          pipeline {:stages [{:name "S"
                              :steps [{:type :jenkins/sh :script "before-script"}
                                      {:type :jenkins/script
                                       :body-source "echo 'inside script'
                                                     sh 'inner-sh'
                                                     deleteDir()"}
                                      {:type :jenkins/sh :script "after-script"}]}]}
          result (d/run-pipeline pipeline d {})]
      (is (= :ok (:status result)))
      (let [evs (effects d)
            types (mapv first evs)]
        ;; Expected order: sh(before), echo, sh(inner), delete-dir, sh(after)
        (is (= [:sh :echo :sh :delete-dir :sh] types))
        (is (= "before-script" (-> evs (nth 0) second :cmd)))
        (is (= "inside script"  (nth (nth evs 1) 1)))
        (is (= "inner-sh"       (-> evs (nth 2) second :cmd)))
        (is (= "after-script"   (-> evs (nth 4) second :cmd)))))))
