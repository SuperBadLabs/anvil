(ns anvil.cost.recorder-test
  "Unit tests for anvil.cost.recorder (v0.5 T2.1).

   Tests fall into four groups:
     1. estimate-cache-savings pure logic (no DB, no bus)
     2. on-build-done integration (flag off → no write, flag on → writes)
     3. start!/stop! lifecycle (subscription token management)
     4. cost-tallied event fired on the job topic (T2.2)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as jobs-persist]
            [anvil.storage.costs :as cost-store]
            [anvil.features :as features]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            ;; Force-load so the private fn is accessible for reflection-free
            ;; testing via the namespace's public surface.
            [anvil.cost.recorder :as recorder]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def ^:private tmp-db-path
  (str (System/getProperty "java.io.tmpdir") "/anvil-recorder-test.db"))

(defn- with-fresh-db [f]
  (db/close!)
  (when (.exists (io/file tmp-db-path)) (.delete (io/file tmp-db-path)))
  (db/init! tmp-db-path)
  ;; seed a build so the FK on anvil_build_costs doesn't fire
  (jobs-persist/upsert-job! {:name "acme" :jenkinsfile-source "pipeline {}"})
  (jobs-persist/insert-build-start! {:job-name "acme" :number 1 :parameters {}})
  (try (f)
       (finally
         (db/close!)
         (.delete (io/file tmp-db-path)))))

(defn- with-flag-on [f]
  (features/set! :cost-reporting true)
  (try (f) (finally (features/set! :cost-reporting false))))

(use-fixtures :each with-fresh-db)

;; ---------------------------------------------------------------------------
;; 1. estimate-cache-savings — pure
;; ---------------------------------------------------------------------------

;; Access the private fn via Var
(def ^:private estimate-cache-savings
  #'anvil.cost.recorder/estimate-cache-savings)

(deftest estimate-cache-savings-no-effects
  (is (zero? (estimate-cache-savings [] 1000))
      "empty effects → 0 savings"))

(deftest estimate-cache-savings-zero-cost
  (let [effects [[:sh {:cmd "mvn test" :cache-hit? true}]
                 [:sh {:cmd "mvn test"}]]]
    (is (zero? (estimate-cache-savings effects 0))
        "zero total-cents → 0 savings regardless of cache hits")))

(deftest estimate-cache-savings-no-sh-steps
  (let [effects [[:archive {:path "/tmp/artifact.jar"}]]]
    (is (zero? (estimate-cache-savings effects 500))
        "no sh effects → 0 savings")))

(deftest estimate-cache-savings-no-cache-hits
  (let [effects [[:sh {:cmd "mvn test" :exit 0}]
                 [:sh {:cmd "echo hi"}]]]
    (is (zero? (estimate-cache-savings effects 500))
        "no cache-hit? true → 0 savings")))

(deftest estimate-cache-savings-all-hits
  ;; 2 steps, both cache-hit → proportional savings = 500 * 2/2 = 500
  (let [effects [[:sh {:cmd "mvn test" :cache-hit? true}]
                 [:sh {:cmd "echo hi" :cache-hit? true}]]]
    (is (= 500 (estimate-cache-savings effects 500))
        "all hits → 100% savings")))

(deftest estimate-cache-savings-partial-hits
  ;; 4 steps, 1 cache-hit → 500 * 1/4 = 125
  (let [effects [[:sh {:cmd "mvn compile" :cache-hit? true}]
                 [:sh {:cmd "mvn test"}]
                 [:sh {:cmd "mvn package"}]
                 [:sh {:cmd "echo done"}]]]
    (is (= 125 (estimate-cache-savings effects 500))
        "1/4 hits → 25% savings")))

;; ---------------------------------------------------------------------------
;; 2. on-build-done integration
;; ---------------------------------------------------------------------------

(deftest flag-off-does-not-write
  (testing "when :cost-reporting is off, no cost row is written"
    (features/set! :cost-reporting false)
    ;; Simulate the :build-done event fired on global topic
    (bus/publish! topics/topic-global
                  {:type    topics/evt-build-done
                   :payload {:job-name     "acme"
                             :build-number 1
                             :duration-ms  60000
                             :host         "test-host"
                             :effects      []}})
    ;; Give the subscriber a tick to run (it's synchronous in the test bus)
    (is (nil? (cost-store/for-build "acme" 1))
        "no row when flag off")))

(deftest flag-on-writes-cost-row
  (testing "when :cost-reporting is on, a cost row appears after :build-done"
    (with-flag-on
      (fn []
        (recorder/start!)
        (try
          (bus/publish! topics/topic-global
                        {:type    topics/evt-build-done
                         :payload {:job-name     "acme"
                                   :build-number 1
                                   :duration-ms  60000
                                   :host         "test-host"
                                   :effects      []}})
          ;; The recorder fires synchronously on the bus publish.
          (let [row (cost-store/for-build "acme" 1)]
            (is (some? row) "row written")
            (is (= "acme" (:job-name row)))
            (is (= 1 (:build-number row)))
            (is (= "test-host" (:host row)))
            (is (= 60000 (:duration-ms row)))
            (is (integer? (:total-cents row))
                "total-cents is an integer"))
          (finally
            (recorder/stop!)))))))

(deftest flag-on-writes-cache-savings
  (testing "cache-hit? effects contribute to cache-saved-cents"
    (with-flag-on
      (fn []
        (recorder/start!)
        (try
          (bus/publish! topics/topic-global
                        {:type    topics/evt-build-done
                         :payload {:job-name     "acme"
                                   :build-number 1
                                   :duration-ms  120000
                                   :host         "test-host"
                                   :effects      [[:sh {:cmd "mvn compile" :cache-hit? true}]
                                                  [:sh {:cmd "mvn test"}]]}})
          (let [row (cost-store/for-build "acme" 1)]
            (is (some? row))
            ;; 1 hit out of 2 steps → saved = total / 2
            (is (= (quot (:total-cents row) 2) (:cache-saved-cents row))
                "50% savings for 1/2 cache-hit steps"))
          (finally
            (recorder/stop!)))))))

;; ---------------------------------------------------------------------------
;; 3. Lifecycle: start!/stop!
;; ---------------------------------------------------------------------------

(deftest start-stop-idempotent
  (testing "stop! after stop! is safe"
    (recorder/start!)
    (recorder/stop!)
    (recorder/stop!)   ; second stop! must not throw
    (is true)))

(deftest stop-clears-tokens
  (testing "after stop!, subscription tokens atom is empty"
    (recorder/start!)
    (recorder/stop!)
    ;; @#'var gives the atom; @atom gives the value.
    (let [tokens @@#'anvil.cost.recorder/subscription-tokens]
      (is (empty? tokens)))))

;; ---------------------------------------------------------------------------
;; 4. T2.2 — :cost-tallied published on job topic
;; ---------------------------------------------------------------------------

(deftest cost-tallied-event-published
  (testing ":cost-tallied is published on the job topic after recording"
    (with-flag-on
      (fn []
        (recorder/start!)
        (try
          (let [received (atom nil)]
            (let [tok (bus/subscribe! (topics/topic-job "acme")
                                      (fn [ev]
                                        (when (= topics/evt-cost-tallied (:type ev))
                                          (reset! received ev))))]
              (try
                (bus/publish! topics/topic-global
                              {:type    topics/evt-build-done
                               :payload {:job-name     "acme"
                                         :build-number 1
                                         :duration-ms  30000
                                         :host         "test-host"
                                         :effects      []}})
                (is (some? @received) "cost-tallied event received")
                (when-let [ev @received]
                  (is (= "acme" (:job-name ev)))
                  (is (= 1 (:build-number ev)))
                  (is (integer? (:total-cents ev))))
                (finally (bus/unsubscribe! tok)))))
          (finally
            (recorder/stop!)))))))
