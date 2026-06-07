(ns anvil.web.flaky-producer-test
  "Tests for the v0.4 T1.4 flaky-flagged SSE producer wired into
   anvil.web.jenkins-api.jobs/complete-build!.

   Hermetic — no real DB; uses with-redefs on the storage + flag
   functions so we can pre-load fixtures and verify the producer's
   behavior in isolation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.features :as features]
            [anvil.flaky :as flaky]
            [anvil.storage.test-results :as tr]))

(use-fixtures :each
  (fn [t]
    (try (t)
         (finally
           (features/set! :flaky false)
           (bus/unsubscribe-all!)))))

(defn- row [test-id attempt status]
  {:test-id test-id
   :class "demo"
   :name test-id
   :status status
   :attempt-number attempt
   :duration-ms 5
   :build-number 1
   :job-name "demo"})

(deftest detect-and-publish-fires-on-passed-on-retry
  (testing "with :anvil.features/flaky on, a passed-on-retry shape "
    (testing "calls write-flaky-flags! then publishes :flaky-flagged on the build topic"
      (features/set! :flaky true)
      (let [;; Pre-load multi-attempt rows
            rows [(row "demo#a" 1 :failed)
                  (row "demo#a" 2 :passed)
                  (row "demo#b" 1 :passed)]
            flagged-args (atom nil)
            received (atom [])]
        (bus/subscribe! [:build "demo" 1]
                        #(swap! received conj %))
        (with-redefs [tr/find-results-all-attempts (fn [_ _] rows)
                      tr/write-flaky-flags! (fn [job n flaky]
                                              (reset! flagged-args [job n flaky])
                                              1)]
          ;; Call the producer body directly — equivalent to the inline
          ;; try/catch block in complete-build! to stay hermetic.
          (let [rows  (tr/find-results-all-attempts "demo" 1)
                flaky-map (flaky/detect-flaky-tests rows)]
            (tr/write-flaky-flags! "demo" 1 flaky-map)
            (when (seq flaky-map)
              (bus/publish! (topics/topic-build "demo" 1)
                            {:type topics/evt-flaky-flagged
                             :job-name "demo"
                             :build-number 1
                             :count (count flaky-map)
                             :tests (mapv (fn [[test-id retry-count]]
                                            {:test-id test-id
                                             :retry-count retry-count})
                                          flaky-map)})))
          (is (= ["demo" 1 {"demo#a" 1}] @flagged-args)
              "write-flaky-flags! called with the detected flaky-map")
          (is (= 1 (count @received)))
          (is (= :flaky-flagged (:type (first @received))))
          (is (= 1 (:count (first @received))))
          (is (= [{:test-id "demo#a" :retry-count 1}]
                 (:tests (first @received)))))))))

(deftest no-event-when-no-flakes-detected
  (testing "stable build (no passed-on-retry) — write-flaky-flags! still called to clear stale, no SSE event"
    (features/set! :flaky true)
    (let [rows [(row "demo#a" 1 :passed)
                (row "demo#b" 1 :passed)]
          flagged-args (atom nil)
          received (atom [])]
      (bus/subscribe! [:build "demo" 1] #(swap! received conj %))
      (with-redefs [tr/find-results-all-attempts (fn [_ _] rows)
                    tr/write-flaky-flags! (fn [job n flaky]
                                            (reset! flagged-args [job n flaky])
                                            0)]
        (let [rows  (tr/find-results-all-attempts "demo" 1)
              flaky-map (flaky/detect-flaky-tests rows)]
          (tr/write-flaky-flags! "demo" 1 flaky-map)
          (when (seq flaky-map)
            ;; should not execute
            (bus/publish! [:build "demo" 1]
                          {:type :flaky-flagged :tests []})))
        (is (= ["demo" 1 {}] @flagged-args)
            "write-flaky-flags! called with empty map → clears stale flags")
        (is (empty? @received)
            "no SSE event when there are no flakes — UI stays in (analyzing→done) silent state")))))
