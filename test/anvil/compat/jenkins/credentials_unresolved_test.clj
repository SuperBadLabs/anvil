(ns anvil.compat.jenkins.credentials-unresolved-test
  "AN4-4: withCredentials([...]) emits :credential-unresolved effects
   for every credentialId the store can't resolve. Composed with the
   AN4-3 classifier extension, those effects drive the build to
   :failure with rule :credential-unresolved — replacing v0.3's silent
   bind-to-\"\".

   The headline wild-corpus regression this PR closes:
     - eclipse-jkube: gpg --import \"${GPG_KEY}\" reading empty
     - camel-quarkus / streampipes: HTTP 401 against
       repository.apache.org/snapshots because the bound user/password
       were empty strings"
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.classification :as classify]
            [anvil.compat.jenkins.dispatcher :as ad]))

(defn- effs [dispatcher]
  @(:effects dispatcher))

(defn- credential-unresolved-effects [dispatcher]
  (filter #(= :credential-unresolved (first %)) (effs dispatcher)))

;; ---------------------------------------------------------------------------
;; Unresolved credentialId emits :credential-unresolved
;; ---------------------------------------------------------------------------

(deftest with-credentials-unresolved-id-emits-effect
  (testing "withCredentials([string(credentialsId:'MISSING', variable:'T')])"
    (let [d (ad/make)
          step {:type :jenkins/with-credentials
                :credentials [{:raw-args "credentialsId: 'MISSING', variable: 'TOK'"}]
                :body []}
          _r (d/dispatch d step {})
          ueffs (credential-unresolved-effects d)]
      (is (= 1 (count ueffs)))
      (is (= "MISSING" (-> ueffs first second :credential-id))))))

(deftest with-credentials-multiple-unresolved-each-emit-effect
  (let [d (ad/make)
        step {:type :jenkins/with-credentials
              :credentials [{:raw-args "credentialsId: 'A', variable: 'X'"}
                            {:raw-args "credentialsId: 'B', variable: 'Y'"}]
              :body []}
        _r (d/dispatch d step {})
        ueffs (credential-unresolved-effects d)]
    (is (= 2 (count ueffs)))
    (is (= #{"A" "B"} (set (map #(-> % second :credential-id) ueffs))))))

;; ---------------------------------------------------------------------------
;; End-to-end classification
;; ---------------------------------------------------------------------------

(deftest unresolved-credential-classifies-build-as-failure
  (testing "anvil v0.3 silently bound APACHE to \"\" and reported SUCCESS.
            With AN4-1 + AN4-3 + AN4-4 the same build now fails honestly."
    (let [d (ad/make)
          ;; A pipeline with a sh that exit-0'd PLUS an unresolved credential.
          ;; v0.3 would have called this :success; we call it :failure.
          step {:type :jenkins/with-credentials
                :credentials [{:raw-args "credentialsId: 'APACHE', usernameVariable: 'U', passwordVariable: 'P'"}]
                :body []}
          _r (d/dispatch d step {})
          _ (swap! (:effects d) conj
                   [:sh {:cmd "mvn deploy" :cwd "/w" :exit 0
                         :streamed? false :stdout-bytes 0 :stderr-bytes 0}])
          c (classify/classify-build {:status :ok} @(:effects d) {})]
      (is (= :failure (:result c))
          "unresolved credential must drive build to :failure")
      (is (= :credential-unresolved (:rule c)))
      (is (re-find #"APACHE" (:explain c))))))

;; ---------------------------------------------------------------------------
;; Resolved credential — no effect emitted
;; ---------------------------------------------------------------------------

(deftest with-credentials-resolved-from-store-emits-no-unresolved-effect
  ;; The store lookup goes through `anvil.storage.credentials/lookup`
  ;; which is dynamically resolved. When the namespace isn't loaded
  ;; (test mode), every credentialId is treated as unresolved — which
  ;; means we get one :credential-unresolved effect per declared id.
  ;; We exercise the resolved branch by redef'ing the resolver.
  (let [resolved (atom {"GH" {:value "ghp_token" :type :string}})
        d (ad/make)
        step {:type :jenkins/with-credentials
              :credentials [{:raw-args "credentialsId: 'GH', variable: 'GH_TOKEN'"}]
              :body []}]
    (with-redefs [anvil.compat.jenkins.dispatcher/resolve-credential-from-store
                  (fn [id] (get @resolved id))]
      (d/dispatch d step {})
      (is (empty? (credential-unresolved-effects d))
          "store-resolved credential must NOT emit :credential-unresolved")
      ;; AND it should have emitted the secret into the env (downstream
      ;; sh would see GH_TOKEN=ghp_token)
      (is (some #(= :with-credentials/enter (first %)) (effs d))))))

(deftest credentials-without-any-id-is-noop
  ;; A credentials block with no credentialsId is malformed but legal
  ;; — must NOT emit :credential-unresolved (nothing to resolve).
  (let [d (ad/make)
        step {:type :jenkins/with-credentials
              :credentials [{:raw-args "variable: 'T'"}]
              :body []}]
    (d/dispatch d step {})
    (is (empty? (credential-unresolved-effects d)))))

;; ---------------------------------------------------------------------------
;; Wild-corpus shape: gpg-key flow
;; ---------------------------------------------------------------------------

(deftest wild-corpus-eclipse-jkube-shape-classifies-as-failure
  (testing "eclipse-jkube: gpg --import \"${GPG_KEY}\" with unresolved
            GPG_KEY credential. Pre-AN4-4 this was :success."
    (let [d (ad/make)
          ;; The withCredentials wrapper around a gpg import shell step.
          ;; Body is empty here since we don't actually run sh; the
          ;; unresolved credential is the failure signal on its own.
          step {:type :jenkins/with-credentials
                :credentials [{:raw-args "credentialsId: 'gpg-key', variable: 'GPG_KEY'"}]
                :body []}]
      (d/dispatch d step {})
      (let [c (classify/classify-build {:status :ok} @(:effects d) {})]
        (is (= :failure (:result c)))
        (is (= :credential-unresolved (:rule c)))))))
