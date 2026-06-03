(ns anvil.events.topics-test
  "Tests for the v0.3 event-topic registry (T0.4).

   Goals:
   - Constants resolve to the keywords producers/subscribers expect
     on the wire (typo guard).
   - The five v0.3-reserved types are subscribable today without a
     producer (forward-compat for T1.5/T2.2/T3.3/T5.3/T6.7).
   - `all-event-types` is the union of existing + reserved; nothing
     drifts."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as t]))

(deftest topic-constructors-shape
  (testing "topic-job returns [:job <name>] tuple"
    (is (= [:job "anvil-self"] (t/topic-job "anvil-self"))))
  (testing "topic-build returns [:build <job> <n>] tuple"
    (is (= [:build "chengis-self" 42] (t/topic-build "chengis-self" 42))))
  (testing "topic-queue and topic-global are plain keywords"
    (is (= :queue t/topic-queue))
    (is (= :global t/topic-global))))

(deftest existing-event-types-on-wire
  (testing "constants match what TX2/TU2 producers currently inline"
    (is (= :build-started     t/evt-build-started))
    (is (= :build-done        t/evt-build-done))
    (is (= :queue-enqueued    t/evt-queue-enqueued))
    (is (= :queue-dispatched  t/evt-queue-dispatched))
    (is (= :console-line      t/evt-console-line))
    (is (= :console-end       t/evt-console-end))))

(deftest v0-3-reserved-event-types
  (testing "T1.5/T2.2/T3.3/T5.3/T6.7 reservations"
    (is (= :test-completed t/evt-test-completed))
    (is (= :problem-found  t/evt-problem-found))
    (is (= :checks-updated t/evt-checks-updated))
    (is (= :schedule-fired t/evt-schedule-fired))
    (is (= :secret-rotated t/evt-secret-rotated))))

(deftest reserved-set-and-all-types-stay-in-sync
  (testing "reserved-v0-3 is exactly the 5 new T-1..7 names"
    (is (= #{:test-completed :problem-found :checks-updated
             :schedule-fired :secret-rotated}
           t/reserved-v0-3)))
  (testing "all-event-types is the union of existing + reserved (no gaps)"
    (is (= 11 (count t/all-event-types))
        "6 existing + 5 reserved = 11")
    (is (every? t/all-event-types t/reserved-v0-3))))

(deftest reserved-topics-are-subscribable-today
  (testing "T1-T7 widgets can register listeners now even though the producer is months away"
    (try
      (let [topic (t/topic-build "anvil-self" 1)
            received (atom [])
            token (bus/subscribe! topic #(swap! received conj %))]
        (try
          ;; Simulate the T1.5 producer (which won't exist for weeks).
          (bus/publish! topic {:type t/evt-test-completed
                               :build-id 1
                               :total 100 :passed 100
                               :failed 0 :skipped 0
                               :duration-ms 405000})
          (is (= 1 (count @received)))
          (is (= :test-completed (:type (first @received))))
          (finally (bus/unsubscribe! token))))
      (finally
        (bus/unsubscribe-all!)))))
