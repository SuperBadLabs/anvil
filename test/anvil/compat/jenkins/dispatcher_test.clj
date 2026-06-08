(ns anvil.compat.jenkins.dispatcher-test
  "AnvilJenkinsDispatcher unit tests + end-to-end with the chengis-core
   reference orchestrator."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.compat.jenkins.scm :as scm]))

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

;; ---------------------------------------------------------------------------
;; AN8-4 — implicit checkout step handling
;; ---------------------------------------------------------------------------

(deftest h-checkout-implicit-with-scm-calls-provision
  (testing "implicit checkout step invokes scm/provision! against ctx :scm config"
    (let [provision-calls (atom [])
          fake-result {:result :cloned :sha "0123456789abcdef0000000000000000deadbeef"}]
      (with-redefs [scm/provision! (fn [ws cfg]
                                     (swap! provision-calls conj {:ws ws :cfg cfg})
                                     fake-result)]
        (let [d (ad/make)
              scm-cfg {:type :git :url "https://example.com/r.git" :branch "main"}
              ctx {:workspace "/tmp/some-ws"
                   :scm scm-cfg}
              result (d/dispatch d
                                 {:type :jenkins/checkout :implicit? true}
                                 ctx)]
          (is (= :ok (:status result)))
          (is (= 1 (count @provision-calls)))
          (is (= scm-cfg (:cfg (first @provision-calls))))
          (let [evs @(:effects d)
                [tag payload] (first evs)]
            (is (= :checkout tag))
            (is (true? (:implicit? payload)))
            (is (= :cloned (:result payload)))
            (is (= "https://example.com/r.git" (:url payload)))))))))

(deftest h-checkout-implicit-without-scm-records-skipped
  (testing "implicit checkout with no :scm in ctx returns ok and records :skipped"
    (let [d (ad/make)
          result (d/dispatch d
                             {:type :jenkins/checkout :implicit? true}
                             {:workspace "/tmp/ws"})]
      (is (= :ok (:status result)))
      (let [[tag payload] (first @(:effects d))]
        (is (= :checkout tag))
        (is (true? (:implicit? payload)))
        (is (= :skipped (:result payload)))
        (is (= :no-scm-configured (:reason payload)))))))

(deftest h-checkout-implicit-failed-provision-bubbles-on-empty-workspace
  (testing "scm/provision! returning :failed yields :failed dispatcher result when workspace is empty"
    (with-redefs [scm/provision! (constantly {:result :failed
                                              :error "fatal: repo not found"})]
      (let [d (ad/make)
            scm-cfg {:type :git :url "https://nope/repo.git" :branch "main"}
            ;; /tmp/ws doesn't exist or is empty — fail-fast preserves the
            ;; AN8-4 receipt's "honest cause vs downstream red herring"
            ;; promise for the truly-empty workspace case.
            result (d/dispatch d
                               {:type :jenkins/checkout :implicit? true}
                               {:workspace "/tmp/ws"
                                :scm scm-cfg})]
        (is (= :failed (:status result)))
        (is (= :scm-checkout-failed (:error result)))
        (is (string? (:message result)))))))

;; ---------------------------------------------------------------------------
;; v0.6.1 — AN8-4 non-fatal-on-populated-workspace heuristic
;;
;; The v0.6.0 dirty-dozen rerun regressed activemq from :success → :failure
;; because the implicit clone of https://github.com/apache/activemq failed
;; (large repo, fresh-clone timeout). The shim's `mvn install` would have
;; succeeded against the stale workspace from prior builds — that's exactly
;; how it produced :success on v0.5.0. Heuristic: when implicit checkout
;; fails AND workspace has files, downgrade to :degraded and continue.
;; ---------------------------------------------------------------------------

(deftest h-checkout-implicit-failed-provision-downgrades-when-workspace-populated
  (testing "failed clone + populated workspace → :degraded effect + ok status (v0.6.1 heuristic)"
    (let [tmp-ws (io/file (System/getProperty "java.io.tmpdir")
                          (str "anvil-h-checkout-populated-" (System/nanoTime)))]
      (try
        (.mkdirs tmp-ws)
        ;; Stale workspace contents — e.g. from a prior successful build.
        (spit (io/file tmp-ws "pom.xml") "<project/>")
        (with-redefs [scm/provision! (constantly {:result :failed
                                                  :error "git clone failed"})]
          (let [d (ad/make)
                scm-cfg {:type :git :url "https://nope/repo.git" :branch "main"}
                result (d/dispatch d
                                   {:type :jenkins/checkout :implicit? true}
                                   {:workspace (.getAbsolutePath tmp-ws)
                                    :scm scm-cfg})]
            (is (= :ok (:status result))
                "downstream steps should run against the stale workspace")
            (let [evs @(:effects d)
                  payloads (mapv second evs)
                  degraded (some #(when (= :degraded (:result %)) %) payloads)]
              (is (some? degraded)
                  "a :degraded effect must be logged for operator visibility")
              (is (= :workspace-populated (:reason degraded)))
              (is (string? (:explain degraded))
                  ":explain string for the build log")
              (is (string? (:error degraded))
                  "original :error preserved on the degraded effect"))))
        (finally
          (when (.exists tmp-ws)
            (doseq [f (.listFiles tmp-ws)] (.delete f))
            (.delete tmp-ws)))))))

(deftest h-checkout-implicit-failed-provision-degraded-effect-stays-fatal-on-empty-workspace
  (testing "failed clone + empty workspace → :failed (v0.6.0 fail-fast preserved)"
    (let [tmp-ws (io/file (System/getProperty "java.io.tmpdir")
                          (str "anvil-h-checkout-empty-" (System/nanoTime)))]
      (try
        (.mkdirs tmp-ws)
        ;; Workspace is empty (.mkdirs creates the dir, nothing inside).
        (with-redefs [scm/provision! (constantly {:result :failed
                                                  :error "git clone failed"})]
          (let [d (ad/make)
                scm-cfg {:type :git :url "https://nope/repo.git" :branch "main"}
                result (d/dispatch d
                                   {:type :jenkins/checkout :implicit? true}
                                   {:workspace (.getAbsolutePath tmp-ws)
                                    :scm scm-cfg})]
            (is (= :failed (:status result))
                "empty workspace + failed clone → fail-fast (AN8-4 v0.6.0 behavior)")
            (is (= :scm-checkout-failed (:error result)))))
        (finally
          (when (.exists tmp-ws) (.delete tmp-ws)))))))

(deftest h-checkout-implicit-failed-provision-treats-dotfile-only-workspace-as-empty
  (testing "workspace with only hidden files (e.g. a bare .gitignore from prior failed clone) is treated as empty"
    (let [tmp-ws (io/file (System/getProperty "java.io.tmpdir")
                          (str "anvil-h-checkout-dotonly-" (System/nanoTime)))]
      (try
        (.mkdirs tmp-ws)
        (spit (io/file tmp-ws ".gitignore") "# only a dotfile")
        (with-redefs [scm/provision! (constantly {:result :failed
                                                  :error "git clone failed"})]
          (let [d (ad/make)
                scm-cfg {:type :git :url "https://nope/repo.git" :branch "main"}
                result (d/dispatch d
                                   {:type :jenkins/checkout :implicit? true}
                                   {:workspace (.getAbsolutePath tmp-ws)
                                    :scm scm-cfg})]
            (is (= :failed (:status result))
                "dotfile-only workspace doesn't contain a real prior build's source — still fail-fast")))
        (finally
          (when (.exists tmp-ws)
            (doseq [f (.listFiles tmp-ws)] (.delete f))
            (.delete tmp-ws)))))))

(deftest h-checkout-explicit-unchanged-by-an8-4
  (testing "explicit checkout step (no :implicit? flag) still just records effect — pre-AN8-4 behavior preserved"
    (let [provisioned? (atom false)]
      (with-redefs [scm/provision! (fn [& _] (reset! provisioned? true)
                                     {:result :cloned})]
        (let [d (ad/make)
              result (d/dispatch d
                                 {:type :jenkins/checkout :spec "main"}
                                 {:workspace "/tmp/ws"
                                  :scm {:type :git :url "u" :branch "b"}})]
          (is (= :ok (:status result)))
          (is (false? @provisioned?) "explicit checkout must NOT trigger provision (runner already ran it upfront)"))))))
