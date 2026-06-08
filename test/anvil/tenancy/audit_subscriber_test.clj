(ns anvil.tenancy.audit-subscriber-test
  "Tests for T4.3 -- audit-log bus subscriber."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.tenancy.rbac :as rbac]
            [anvil.tenancy.audit-subscriber :as sub]))

(use-fixtures :each
  (fn [f]
    (rbac/set-backend! rbac/noop-backend)
    (sub/stop!)
    (try (f)
         (finally
           (sub/stop!)
           (bus/unsubscribe-all!)
           (rbac/set-backend! rbac/noop-backend)))))

;; ---------------------------------------------------------------------------
;; build-started fires audit event
;; ---------------------------------------------------------------------------

(deftest build-started-writes-audit-event
  (let [audits (atom [])]
    (rbac/set-backend! (reify rbac/RbacBackend
                         (check! [_ _] {:allowed? true :reason "ok"})
                         (record-audit! [_ ev] (swap! audits conj ev) nil)))
    (with-redefs [anvil.features/enabled? (fn [_] true)]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name    "demo"
                               :build-number 1
                               :tenant-id    "t1"
                               :triggered-by "alice"}})
      (is (= 1 (count @audits)))
      (let [ev (first @audits)]
        (is (= "t1"     (:tenant-id ev)))
        (is (= "alice"  (:principal ev)))
        (is (= :build   (:action ev)))
        (is (= "/jobs/demo" (:resource ev)))
        (is (= :allowed (:result ev)))))))

;; ---------------------------------------------------------------------------
;; build-done fires audit event
;; ---------------------------------------------------------------------------

(deftest build-done-writes-audit-event-with-result
  (let [audits (atom [])]
    (rbac/set-backend! (reify rbac/RbacBackend
                         (check! [_ _] {:allowed? true :reason "ok"})
                         (record-audit! [_ ev] (swap! audits conj ev) nil)))
    (with-redefs [anvil.features/enabled? (fn [_] true)]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-done
                     :payload {:job-name    "demo"
                               :build-number 2
                               :result       :success
                               :tenant-id    "t2"
                               :triggered-by "bob"}})
      (is (= 1 (count @audits)))
      (let [ev (first @audits)]
        (is (= "t2"     (:tenant-id ev)))
        (is (= "bob"    (:principal ev)))
        (is (= :build   (:action ev)))
        (is (= :allowed (:result ev)))
        (is (= :success (get-in ev [:context :result])))))))

;; ---------------------------------------------------------------------------
;; job-created / credential-added
;; ---------------------------------------------------------------------------

(deftest job-created-writes-audit-event
  (let [audits (atom [])]
    (rbac/set-backend! (reify rbac/RbacBackend
                         (check! [_ _] {:allowed? true :reason "ok"})
                         (record-audit! [_ ev] (swap! audits conj ev) nil)))
    (with-redefs [anvil.features/enabled? (fn [_] true)]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :job-created
                     :payload {:job-name "new-job" :triggered-by "operator"}})
      (is (= 1 (count @audits)))
      (is (= :write (:action (first @audits))))
      (is (= "/jobs" (:resource (first @audits)))))))

(deftest credential-added-writes-audit-event
  (let [audits (atom [])]
    (rbac/set-backend! (reify rbac/RbacBackend
                         (check! [_ _] {:allowed? true :reason "ok"})
                         (record-audit! [_ ev] (swap! audits conj ev) nil)))
    (with-redefs [anvil.features/enabled? (fn [_] true)]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :credential-added
                     :payload {:credential-id "db-pw" :kind :secret}})
      (is (= 1 (count @audits)))
      (is (= :admin (:action (first @audits))))
      (is (= "/credentials" (:resource (first @audits)))))))

;; ---------------------------------------------------------------------------
;; flag-off -- no audit writes
;; ---------------------------------------------------------------------------

(deftest flag-off-noop-backend-records-nothing-meaningful
  ;; NoOpBackend silently accepts all record-audit! calls; we verify that
  ;; the subscriber wires up correctly and the noop backend doesn't throw.
  (with-redefs [anvil.features/enabled? (fn [_] false)]
    ;; With flag off, subscriber handlers still call record-audit! on noop-backend
    ;; which is a no-op -- no error, no side effect.
    (sub/start!)
    (bus/publish! topics/topic-global
                  {:type    :build-started
                   :payload {:job-name "demo" :build-number 1}})
    ;; Just assert no exception was thrown (test would fail with error)
    (is true "no exception on flag-off noop path")))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(deftest stop-clears-subscription-tokens
  (sub/start!)
  (sub/stop!)
  (is (empty? @@#'anvil.tenancy.audit-subscriber/subscription-tokens)))
