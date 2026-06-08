(ns anvil.secrets-test
  "Tests for the SecretBackend protocol + registry + emit helper.
   Covers the v0.6 T2.1 surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.secrets :as s]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]))

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (s/reset-for-tests!)
        (bus/unsubscribe-all!)))))

;; ---------------------------------------------------------------------------
;; A fake backend for protocol-level tests
;; ---------------------------------------------------------------------------

(defrecord FakeBackend [store]
  s/SecretBackend
  (resolve! [_ id] (get @store id))
  (list-ids [_] (vec (keys @store))))

(defn- fake-backend [m]
  (with-meta (->FakeBackend (atom m))
    {:anvil.secrets/kind :fake}))

;; ---------------------------------------------------------------------------
;; Protocol shape
;; ---------------------------------------------------------------------------

(deftest backend-kind-reads-metadata
  (is (= :fake (s/backend-kind (fake-backend {}))))
  (is (= :unknown (s/backend-kind (->FakeBackend (atom {}))))
      "backend without metadata defaults to :unknown"))

(deftest resolve-returns-nil-for-unknown-id
  (let [b (fake-backend {})]
    (is (nil? (s/resolve! b "missing")))))

(deftest resolve-returns-value-and-type-for-known-id
  (let [b (fake-backend {"a" {:value "v" :type :string}})]
    (is (= {:value "v" :type :string} (s/resolve! b "a")))))

(deftest list-ids-never-touches-values
  ;; The list-ids contract is "no values". We can't directly assert
  ;; on a fake, but we lock in the shape (a vec of strings).
  (let [b (fake-backend {"a" {:value "secret-a"}
                         "b" {:value "secret-b"}})]
    (is (= #{"a" "b"} (set (s/list-ids b))))
    (is (every? string? (s/list-ids b))
        "list-ids must return strings (the ids), not records")))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest active-backend-falls-back-to-local-disk
  ;; With nothing installed and the registry just cleared, active-backend
  ;; lazily installs the local-disk backend (it may return nil from
  ;; resolve! in the test env — that's fine; we're testing the registry,
  ;; not the SQLite store).
  (s/reset-for-tests!)
  (let [active (s/active-backend)]
    (is (= :local (s/backend-kind active))
        "default backend is local-disk-tagged")))

(deftest register-backend-replaces-active
  (let [fb (fake-backend {"x" {:value "secret-x" :type :string}})]
    (s/register-backend! fb)
    (is (= :fake (s/backend-kind (s/active-backend))))
    (is (= "secret-x" (:value (s/resolve! (s/active-backend) "x"))))))

;; ---------------------------------------------------------------------------
;; resolve-with-emit! — publishes :secret-resolved on success
;; ---------------------------------------------------------------------------

(deftest resolve-with-emit-publishes-event-on-success
  (let [fb (fake-backend {"GH" {:value "ghp_xxx" :type :string}})
        captured (atom [])]
    (s/register-backend! fb)
    (bus/subscribe! (topics/topic-build "j1" 7)
                    (fn [evt] (swap! captured conj evt)))
    (let [result (s/resolve-with-emit! {:job-name "j1" :build-number 7} "GH")]
      (is (= "ghp_xxx" (:value result)) "value flows back to the caller")
      (is (= 1 (count @captured))
          "exactly one :secret-resolved event published")
      (let [evt (first @captured)]
        (is (= :secret-resolved (:type evt)))
        (is (= "GH" (:credential-id evt)))
        (is (= :fake (:backend evt)))
        (is (number? (:latency-ms evt)))
        (is (not (contains? evt :value))
            "INVARIANT: event payload MUST NOT carry the secret value")))))

(deftest resolve-with-emit-no-event-when-backend-returns-nil
  (let [fb (fake-backend {})  ;; empty
        captured (atom [])]
    (s/register-backend! fb)
    (bus/subscribe! (topics/topic-build "j2" 9)
                    (fn [evt] (swap! captured conj evt)))
    (let [result (s/resolve-with-emit! {:job-name "j2" :build-number 9} "missing")]
      (is (nil? result))
      (is (empty? @captured)
          "no event for unresolved id — :credential-unresolved is the
           dispatcher's signal, not :secret-resolved"))))

(deftest resolve-with-emit-no-event-without-build-ctx
  ;; Non-build call sites (admin UI preview) should still resolve
  ;; values; we just don't have a per-build topic to publish on.
  (let [fb (fake-backend {"k" {:value "v"}})
        captured (atom [])]
    (s/register-backend! fb)
    (bus/subscribe! :global (fn [evt] (swap! captured conj evt)))
    (let [result (s/resolve-with-emit! {} "k")]
      (is (= "v" (:value result)) "value still resolves")
      (is (empty? @captured)
          "no publish when ctx lacks :job-name + :build-number"))))

(deftest event-payload-never-contains-value-key
  ;; Defence-in-depth invariant — the assert-no-value-leak! check.
  ;; We exercise it by inspecting every published event.
  (let [fb (fake-backend {"alpha" {:value "supersecret123" :type :string}})
        captured (atom [])]
    (s/register-backend! fb)
    (bus/subscribe! (topics/topic-build "j" 1)
                    (fn [evt] (swap! captured conj evt)))
    (s/resolve-with-emit! {:job-name "j" :build-number 1} "alpha")
    (doseq [evt @captured]
      (is (not (contains? evt :value))
          (str "leak: event " evt " contained :value"))
      (doseq [v (vals evt)]
        (is (not= "supersecret123" v)
            (str "leak: event " evt " contained the secret literal"))))))
