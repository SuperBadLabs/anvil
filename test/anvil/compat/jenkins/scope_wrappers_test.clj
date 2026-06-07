(ns anvil.compat.jenkins.scope-wrappers-test
  "Tests for declarative scope wrappers: withEnv, withCredentials, timeout,
   retry, parallel. Plus credential masking."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]))

(defn- run-jenkinsfile [src & {:keys [canned fail-attempts]}]
  (let [ir (t/parse src)
        stage-block (for [s (:stages ir)]
                      {:name (:name s)
                       :steps (concat (:steps s)
                                      (get-in s [:post :always] []))})
        cleanup-block (when-let [c (get-in ir [:post :cleanup])]
                        [{:name "<cleanup>" :steps c}])
        flat {:stages (vec (concat stage-block cleanup-block))}
        d (ad/make (cond-> {:sh-canned (atom (or canned {}))}
                     fail-attempts (assoc :sh-fail-attempts (atom fail-attempts))))
        result (d/run-pipeline flat d {:cwd "/workspace"})]
    {:result result :effects @(:effects d) :ir ir :dispatcher d}))

;; ---------------------------------------------------------------------------
;; withEnv
;; ---------------------------------------------------------------------------

(deftest with-env-pushes-and-pops-test
  (testing "withEnv records enter/leave around body; nested sh sees the new env"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              sh 'before'
              withEnv(['FOO=bar','BAZ=qux']) {
                sh 'inside'
              }
              sh 'after'
            } } } }")]
      (let [types (mapv first effects)]
        (is (= [:sh :with-env/enter :sh :with-env/leave :sh] types))
        (is (= ["FOO" "BAZ"] (-> effects (nth 1) second)))))))

;; ---------------------------------------------------------------------------
;; withCredentials + masking
;; ---------------------------------------------------------------------------

(deftest with-credentials-records-test
  (testing "withCredentials emits enter, a :credential-unresolved for
            the unresolved id (AN4-4 — no live store in test fixture),
            the body's sh, then leave"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              withCredentials([usernamePassword(credentialsId: 'my-cred',
                                                usernameVariable: 'U',
                                                passwordVariable: 'P')]) {
                sh 'do-stuff'
              }
            } } } }")]
      (let [types (mapv first effects)]
        (is (= [:with-credentials/enter :credential-unresolved
                :sh :with-credentials/leave] types)
            "the :credential-unresolved between enter and body is the
             AN4-4 contract — no silent bind-to-empty-string")))))

(deftest credential-masking-redacts-from-logs-test
  (testing "secret strings are replaced with **** in subsequent effects"
    (let [d (ad/make {:secrets (atom #{"super-secret-token"})})]
      (d/dispatch d {:type :jenkins/sh
                     :script "curl -H 'Authorization: super-secret-token' api.example.com"}
                  {})
      (d/dispatch d {:type :jenkins/echo
                     :message "leaking: super-secret-token"}
                  {})
      (let [evs @(:effects d)]
        (is (= "curl -H 'Authorization: ****' api.example.com"
               (-> evs (nth 0) second :cmd)))
        (is (= "leaking: ****" (second (nth evs 1))))))))

;; ---------------------------------------------------------------------------
;; timeout / retry
;; ---------------------------------------------------------------------------

(deftest timeout-records-and-runs-body-test
  (testing "timeout records enter/leave; body steps execute in between"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              timeout(time: 5, unit: 'MINUTES') {
                sh 'work'
              }
            } } } }")]
      (let [types (mapv first effects)]
        (is (= [:timeout/enter :sh :timeout/leave] types))
        (is (= 5    (-> effects (nth 0) second :time)))
        (is (= "MINUTES" (-> effects (nth 0) second :unit)))))))

(deftest retry-records-enter-attempt-leave-on-first-success
  (testing "v0.4 T1.5 — retry runs the body; default h-sh returns :ok, loop exits after attempt 1"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              retry(3) {
                sh 'flaky'
              }
            } } } }")]
      (let [types (mapv first effects)]
        (is (= [:retry/enter :sh :retry/attempt :retry/leave] types)
            "h-retry is now a real loop; one attempt logged before leave")
        (is (= 3 (-> effects (nth 0) second :count))
            "configured count is recorded on enter")
        (is (= 1   (-> effects (nth 2) second :index))
            "first attempt indexed 1")
        (is (= :ok (-> effects (nth 2) second :status))
            "first attempt succeeded — sh test-double returns :ok")
        (is (= 1   (-> effects (nth 3) second :attempts))
            "single attempt, no retry needed")
        (is (= :ok (-> effects (nth 3) second :outcome))
            "outcome is :ok at the leave")))))

(deftest retry-loops-when-body-fails-then-succeeds
  (testing "v0.4 T1.5 — body fails once then passes; retry loop captures both attempts + the recovery"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              retry(3) {
                sh 'flaky'
              }
            } } } }"
           :fail-attempts {"flaky" 1})]
      (let [types  (mapv first effects)
            shes   (filter #(= :sh (first %)) effects)
            attempts (filter #(= :retry/attempt (first %)) effects)
            leave  (last effects)]
        (is (= [:retry/enter :sh :retry/attempt
                :sh :retry/attempt :retry/leave]
               types)
            "second :sh + :retry/attempt cycle proves the loop ran twice")
        (is (= 2 (count shes))
            "body was re-invoked once after the failure")
        (is (= [1 2] (mapv #(:attempt (second %)) shes))
            "each :sh effect carries its 1-based attempt index from ctx")
        (is (= [:failed :ok] (mapv #(:status (second %)) attempts))
            "first attempt :failed, second attempt :ok")
        (is (= :retry/leave (first leave)))
        (is (= 2 (-> leave second :attempts)))
        (is (= :ok (-> leave second :outcome)))))))

(deftest retry-exhausts-all-attempts-when-body-never-succeeds
  (testing "v0.4 T1.5 — body fails forever; retry runs N attempts then propagates failure"
    (let [{:keys [effects result]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              retry(2) {
                sh 'dead'
              }
            } } } }"
           :fail-attempts {"dead" 999})]
      (let [shes     (filter #(= :sh (first %)) effects)
            attempts (filter #(= :retry/attempt (first %)) effects)
            leave    (->> effects (filter #(= :retry/leave (first %))) first)]
        (is (= 2 (count shes))
            "exactly N body invocations")
        (is (= [1 2] (mapv #(:attempt (second %)) shes)))
        (is (every? #(= :failed (:status (second %))) attempts)
            "every attempt failed")
        (is (= 2 (-> leave second :attempts)))
        (is (= :failed (-> leave second :outcome)))
        (is (= :failed (:status result))
            "exhausted retry propagates failure up to the orchestrator
             — fixes the historic v1-no-op behavior where retry could
             mask a build that actually never recovered")))))

;; ---------------------------------------------------------------------------
;; parallel
;; ---------------------------------------------------------------------------

(deftest parallel-runs-branches-sequentially-in-v1-test
  (testing "parallel records start/end + each branch start/end (sequential v1)"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              parallel(
                quick: { sh 'q-1' },
                slow:  { sh 's-1'; sh 's-2' }
              )
            } } } }")]
      (let [types (mapv first effects)]
        (is (= :parallel/start (first types)))
        (is (= :parallel/end (last types)))
        ;; Each branch has start, sh(s), end.
        (is (= 2 (count (filter #(= :parallel/branch-start %) types))))
        (is (= 2 (count (filter #(= :parallel/branch-end %) types))))
        ;; All sh calls were dispatched (1 in quick, 2 in slow).
        (is (= 3 (count (filter #(= :sh %) types))))))))

;; ---------------------------------------------------------------------------
;; Plugin step adapters (a representative sample)
;; ---------------------------------------------------------------------------

(deftest plugin-step-adapters-emit-leaf-effects-test
  (testing "common plugin steps emit a leaf side-effect entry; the pipeline still completes :ok"
    (let [{:keys [result effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              recordIssues tool: 'java'
              slackSend channel: '#builds', message: 'hi'
              milestone 1
              publishCoverage adapters: 'cobertura'
              lock resource: 'gpu-0'
            } } } }")]
      (is (= :ok (:status result)))
      (let [types (mapv first effects)]
        (is (every? #{:record-issues :slack-send :milestone :publish-coverage :lock} types))
        (is (= 5 (count types)))))))
