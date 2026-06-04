(ns anvil.compat.jenkins.scope-wrappers-test
  "Tests for declarative scope wrappers: withEnv, withCredentials, timeout,
   retry, parallel. Plus credential masking."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]))

(defn- run-jenkinsfile [src & {:keys [canned]}]
  (let [ir (t/parse src)
        stage-block (for [s (:stages ir)]
                      {:name (:name s)
                       :steps (concat (:steps s)
                                      (get-in s [:post :always] []))})
        cleanup-block (when-let [c (get-in ir [:post :cleanup])]
                        [{:name "<cleanup>" :steps c}])
        flat {:stages (vec (concat stage-block cleanup-block))}
        d (ad/make {:sh-canned (atom (or canned {}))})
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

(deftest retry-records-and-runs-body-test
  (testing "retry records enter/leave; body steps run (single attempt in v1)"
    (let [{:keys [effects]}
          (run-jenkinsfile
           "pipeline { agent any; stages { stage('S') { steps {
              retry(3) {
                sh 'flaky'
              }
            } } } }")]
      (let [types (mapv first effects)]
        (is (= [:retry/enter :sh :retry/leave] types))
        (is (= 3 (-> effects (nth 0) second :count)))))))

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
