(ns anvil.events.bus-test
  "Contract tests for the topic-keyed pub/sub bus (TU1.1).

   These nail down semantics every future TU consumer (TU1.3 SSE
   endpoint, TU2 console tail, TU3 build pages) relies on.

   Use the per-test cleanup fixture: each test starts with a clean
   registry so subscriptions don't leak between tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.events.bus :as bus]))

(use-fixtures :each (fn [t] (bus/unsubscribe-all!) (t) (bus/unsubscribe-all!)))

(deftest subscribe-publish-roundtrip
  (let [received (atom [])
        token (bus/subscribe! :t1 #(swap! received conj %))]
    (is (some? token) "subscribe! returns a non-nil token")
    (bus/publish! :t1 {:type :hello :payload "x"})
    (is (= 1 (count @received)))
    (is (= :hello (-> @received first :type)))
    (testing "bus enriches event with topic if missing"
      (is (= :t1 (-> @received first :topic))))))

(deftest publish-preserves-explicit-topic
  (let [received (atom [])]
    (bus/subscribe! :wire #(swap! received conj %))
    ;; Producer set :topic to something different from the publish topic.
    ;; The bus must not stomp the producer's choice.
    (bus/publish! :wire {:type :evt :topic :producer-set})
    (is (= :producer-set (-> @received first :topic)))))

(deftest global-subscriber-sees-everything
  (let [globals (atom [])
        narrows (atom [])]
    (bus/subscribe! :global #(swap! globals conj %))
    (bus/subscribe! :job/foo #(swap! narrows conj %))
    (bus/publish! :job/foo {:type :build-started})
    (bus/publish! :job/bar {:type :build-done})
    (bus/publish! :queue   {:type :queue-tick})
    (is (= 3 (count @globals))
        ":global catches all three publishes regardless of topic")
    (is (= 1 (count @narrows))
        ":job/foo subscriber only got its own topic")))

(deftest publish-to-global-fans-only-to-global-subs
  ;; If a producer publishes ON :global, then :global subscribers fire
  ;; once each. Narrow subscribers (on :job/foo, :queue, etc) do NOT
  ;; receive — :global is fan-IN for subscribers, not fan-OUT for
  ;; publishers.
  (let [g (atom 0)
        j (atom 0)]
    (bus/subscribe! :global  (fn [_] (swap! g inc)))
    (bus/subscribe! :job/foo (fn [_] (swap! j inc)))
    (bus/publish! :global {:type :tick})
    (is (= 1 @g) ":global subscriber fired")
    (is (= 0 @j) ":job/foo did NOT fire on :global publish")))

(deftest multiple-subscribers-on-same-topic-all-fire
  (let [a (atom 0) b (atom 0) c (atom 0)]
    (dotimes [_ 3] (bus/subscribe! :t (fn [_] (swap! a inc))))
    (bus/subscribe! :t (fn [_] (swap! b inc)))
    (bus/subscribe! :t (fn [_] (swap! c inc)))
    (bus/publish! :t {:type :x})
    (is (= 3 @a) "all three duplicate-fn subs fired (set is by token, not by fn)")
    (is (= 1 @b))
    (is (= 1 @c))))

(deftest unsubscribe-removes-listener
  (let [received (atom 0)
        token (bus/subscribe! :u (fn [_] (swap! received inc)))]
    (bus/publish! :u {:type :a})
    (is (= 1 @received))
    (bus/unsubscribe! token)
    (bus/publish! :u {:type :b})
    (is (= 1 @received) "listener fired only before unsubscribe")
    (testing "double-unsubscribe is a no-op (idempotent)"
      (bus/unsubscribe! token))))

(deftest unsubscribe-all-resets-registry
  (dotimes [_ 5] (bus/subscribe! :z (fn [_])))
  (is (= 5 (bus/subscriber-count)))
  (bus/unsubscribe-all!)
  (is (= 0 (bus/subscriber-count)))
  (is (= {} (bus/subscriber-counts))))

(deftest thrown-subscriber-does-not-kill-others
  (let [ok-fired (atom 0)]
    (bus/subscribe! :t (fn [_] (throw (RuntimeException. "boom"))))
    (bus/subscribe! :t (fn [_] (swap! ok-fired inc)))
    ;; Publish must NOT throw — the bad sub is caught + logged.
    (bus/publish! :t {:type :x})
    (is (= 1 @ok-fired)
        "well-behaved subscriber still fired after the bad one threw")
    (testing "thrown subscriber is auto-removed after the publish"
      ;; Intentional. A subscriber that crashed once is overwhelmingly
      ;; likely to crash again on the next publish, and the bus has
      ;; no way to ask the subscriber for retry semantics. Pull it
      ;; once, ask questions never. The well-behaved sub survives.
      (is (= 1 (bus/subscriber-count)))
      (bus/publish! :t {:type :y})
      (is (= 2 @ok-fired) "survivor still fires on later publishes"))))

(deftest subscriber-returning-bus-unsubscribe-removes-itself
  ;; The SSE handler uses this to drop itself when the underlying
  ;; channel is no longer writable. Verified end-to-end in
  ;; anvil.web.events-sse-test/disconnect-releases-bus-subscription.
  (let [fires (atom 0)]
    (bus/subscribe! :u (fn [_]
                         (swap! fires inc)
                         (when (= 2 @fires)
                           :anvil.events.bus/unsubscribe)))
    (bus/publish! :u {:type :x})
    (is (= 1 (bus/subscriber-count)))
    (bus/publish! :u {:type :x})
    (is (= 0 (bus/subscriber-count)) "second publish returned ::unsubscribe → dropped")
    (bus/publish! :u {:type :x})
    (is (= 2 @fires) "the third publish doesn't reach the removed subscriber")))

(deftest publish-with-no-subscribers-is-fine
  (testing "publish to topic with no subscribers does not throw"
    (bus/publish! :nobody {:type :ignored})))

(deftest subscriber-counts-reports-per-topic
  (bus/subscribe! :a (fn [_]))
  (bus/subscribe! :a (fn [_]))
  (bus/subscribe! :b (fn [_]))
  (let [counts (bus/subscriber-counts)]
    (is (= 2 (get counts :a)))
    (is (= 1 (get counts :b)))
    (is (= 3 (bus/subscriber-count)))))
