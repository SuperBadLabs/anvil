(ns anvil.secrets.dispatcher-wiring-test
  "T2.4 + T2.5 — end-to-end wiring of the SecretBackend protocol into
   `h-with-credentials`. When the active backend resolves a value, the
   dispatcher must publish a `:secret-resolved` SSE event on the per-
   build topic. The event MUST NOT contain the secret value."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.secrets :as s]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]))

(use-fixtures :each
  (fn [f]
    (try (f)
         (finally
           (s/reset-for-tests!)
           (bus/unsubscribe-all!)))))

(defrecord StubBackend [store]
  s/SecretBackend
  (resolve! [_ id] (get @store id))
  (list-ids [_] (vec (keys @store))))

(defn- stub-backend [m]
  (with-meta (->StubBackend (atom m))
    {:anvil.secrets/kind :stub}))

(deftest h-with-credentials-publishes-secret-resolved
  (testing "Resolved credential via SecretBackend → :secret-resolved
            event flows on the per-build topic. Payload includes
            credential-id + backend kind + latency — NEVER the value."
    (s/register-backend! (stub-backend {"GH" {:value "ghp_secret_xyz"
                                              :type :string}}))
    (let [captured (atom [])
          d (ad/make)
          step {:type :jenkins/with-credentials
                :credentials [{:raw-args "credentialsId: 'GH', variable: 'GH_TOKEN'"}]
                :body []}
          ctx {:job-name "my-job" :build-number 42}]
      (bus/subscribe! (topics/topic-build "my-job" 42)
                      (fn [evt] (swap! captured conj evt)))
      (d/dispatch d step ctx)
      ;; Exactly one :secret-resolved event
      (let [resolved-events (filter #(= :secret-resolved (:type %)) @captured)]
        (is (= 1 (count resolved-events)))
        (let [evt (first resolved-events)]
          (is (= "GH" (:credential-id evt)))
          (is (= :stub (:backend evt)))
          (is (number? (:latency-ms evt)))
          ;; The crown jewel — the value MUST NOT appear anywhere in
          ;; the payload.
          (is (not (contains? evt :value)))
          (is (not (some #(= "ghp_secret_xyz" %) (vals evt)))
              "secret value literal must not appear in any event field"))))))

(deftest h-with-credentials-no-event-for-unresolved
  (testing "Backend returns nil (no value) → NO :secret-resolved event.
            The dispatcher's existing :credential-unresolved effect
            remains the signal for that case."
    (s/register-backend! (stub-backend {}))  ;; empty store
    (let [captured (atom [])
          d (ad/make)
          step {:type :jenkins/with-credentials
                :credentials [{:raw-args "credentialsId: 'MISSING', variable: 'X'"}]
                :body []}
          ctx {:job-name "j" :build-number 1}]
      (bus/subscribe! (topics/topic-build "j" 1)
                      (fn [evt] (swap! captured conj evt)))
      (d/dispatch d step ctx)
      (is (empty? (filter #(= :secret-resolved (:type %)) @captured))
          "no :secret-resolved when backend can't resolve"))))

(deftest h-with-credentials-routes-through-active-backend
  (testing "v0.5 used to hard-code anvil.storage.credentials/lookup.
            v0.6: the dispatcher routes through (active-backend), so an
            in-test stub backend serves the lookup."
    (s/register-backend! (stub-backend {"K" {:value "secret-from-stub"
                                             :type :string}}))
    (let [d (ad/make)
          step {:type :jenkins/with-credentials
                :credentials [{:raw-args "credentialsId: 'K', variable: 'V'"}]
                :body []}]
      (d/dispatch d step {:job-name "j" :build-number 1})
      (let [effs @(:effects d)]
        (is (empty? (filter #(= :credential-unresolved (first %)) effs))
            "stub backend resolved K → no :credential-unresolved")))))

(deftest h-with-credentials-event-payload-shape
  (testing "Payload shape locked to {:type :secret-resolved
            :job-name :build-number :credential-id :backend :latency-ms}.
            No extras that could accidentally leak (e.g. :masked, :type)."
    (s/register-backend! (stub-backend {"X" {:value "v" :type :string}}))
    (let [captured (atom [])
          d (ad/make)
          step {:type :jenkins/with-credentials
                :credentials [{:raw-args "credentialsId: 'X', variable: 'X'"}]
                :body []}]
      (bus/subscribe! (topics/topic-build "j" 7)
                      (fn [e] (swap! captured conj e)))
      (d/dispatch d step {:job-name "j" :build-number 7})
      (let [evt (first (filter #(= :secret-resolved (:type %)) @captured))]
        (is (some? evt))
        ;; Allowed keys ONLY — :topic is added by the bus itself.
        (is (every? #{:type :job-name :build-number :credential-id
                      :backend :latency-ms :topic}
                    (keys evt))
            (str "unexpected keys in :secret-resolved payload: "
                 (pr-str (keys evt))))))))
