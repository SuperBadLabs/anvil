(ns anvil.tenancy.rbac-test
  "Tests for T4.2 -- RBAC protocol, NoOpBackend, and ChengisBackend."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [anvil.tenancy.rbac :as rbac]))

(use-fixtures :each
  (fn [f]
    ;; Reset to noop-backend before each test
    (rbac/set-backend! rbac/noop-backend)
    (try (f) (finally (rbac/set-backend! rbac/noop-backend)))))

;; ---------------------------------------------------------------------------
;; NoOpBackend
;; ---------------------------------------------------------------------------

(deftest noop-backend-always-allows
  (let [result (rbac/check! rbac/noop-backend
                            {:tenant-id "t1"
                             :principal "alice"
                             :action    :read
                             :resource  "/jobs"})]
    (is (true? (:allowed? result)))
    (is (string? (:reason result)))))

(deftest noop-backend-audit-returns-nil
  (is (nil? (rbac/record-audit! rbac/noop-backend
                                {:tenant-id "t1"
                                 :principal "alice"
                                 :action    :build
                                 :resource  "/jobs/demo"
                                 :result    :allowed}))))

;; ---------------------------------------------------------------------------
;; Backend registry
;; ---------------------------------------------------------------------------

(deftest set-backend-changes-active-backend
  (let [custom (reify rbac/RbacBackend
                 (check! [_ _] {:allowed? false :reason "custom deny"})
                 (record-audit! [_ _] nil))]
    (rbac/set-backend! custom)
    (is (identical? custom (rbac/backend)))))

(deftest allowed?-delegates-to-active-backend
  (rbac/set-backend! rbac/noop-backend)
  (is (true? (rbac/allowed? "t1" "alice" :read "/jobs"))))

(deftest allowed?-returns-false-when-backend-denies
  (rbac/set-backend! (reify rbac/RbacBackend
                       (check! [_ _] {:allowed? false :reason "denied"})
                       (record-audit! [_ _] nil)))
  (is (false? (rbac/allowed? "t1" "alice" :build "/jobs/demo"))))

(deftest audit!-delegates-to-active-backend
  (let [recorded (atom nil)]
    (rbac/set-backend! (reify rbac/RbacBackend
                         (check! [_ _] {:allowed? true :reason "ok"})
                         (record-audit! [_ ev] (reset! recorded ev) nil)))
    (rbac/audit! "t1" "alice" :read "/jobs" :allowed :context {:extra "data"})
    (is (= "t1" (:tenant-id @recorded)))
    (is (= "alice" (:principal @recorded)))
    (is (= :read (:action @recorded)))
    (is (= "/jobs" (:resource @recorded)))
    (is (= :allowed (:result @recorded)))))

;; ---------------------------------------------------------------------------
;; ChengisBackend -- mocked HTTP
;; ---------------------------------------------------------------------------

(deftest chengis-backend-check-posts-to-rbac-endpoint
  (let [calls (atom [])
        fake-http (fn [opts]
                    (swap! calls conj opts)
                    (future {:status 200
                             :body   (json/write-str {:allowed true :reason "ok"})}))]
    (with-redefs [org.httpkit.client/request fake-http
                  anvil.tenancy.rbac/chengis-service-url (constantly "http://chengis:8090")
                  anvil.tenancy.rbac/chengis-token       (constantly "tok")
                  anvil.tenancy.rbac/chengis-timeout-ms  (constantly 1000)]
      (let [backend (rbac/->ChengisBackend)
            result  (rbac/check! backend {:tenant-id "t1"
                                          :principal "alice"
                                          :action    :read
                                          :resource  "/jobs"})]
        (is (true? (:allowed? result)))
        (is (= "ok" (:reason result)))
        (let [call (first @calls)
              body (json/read-str (:body call) :key-fn keyword)]
          (is (= "http://chengis:8090/api/v1/rbac/check" (:url call)))
          (is (= :post (:method call)))
          (is (= "Bearer tok" (get-in call [:headers "Authorization"])))
          (is (= "t1" (:tenant_id body)))
          (is (= "alice" (:principal body)))
          (is (= "read" (:action body))))))))

(deftest chengis-backend-check-returns-denied-on-http-error
  (with-redefs [org.httpkit.client/request
                (fn [_] (future {:status 403 :body "forbidden"}))
                anvil.tenancy.rbac/chengis-service-url (constantly "http://chengis:8090")
                anvil.tenancy.rbac/chengis-token       (constantly nil)
                anvil.tenancy.rbac/chengis-timeout-ms  (constantly 1000)]
    (let [backend (rbac/->ChengisBackend)
          result  (rbac/check! backend {:tenant-id "t1"
                                        :principal "bob"
                                        :action    :admin
                                        :resource  "/credentials"})]
      (is (false? (:allowed? result)))
      (is (string? (:reason result))))))

(deftest chengis-backend-check-returns-denied-on-exception
  (with-redefs [org.httpkit.client/request
                (fn [_] (throw (ex-info "connection refused" {})))
                anvil.tenancy.rbac/chengis-service-url (constantly "http://chengis:8090")
                anvil.tenancy.rbac/chengis-token       (constantly nil)
                anvil.tenancy.rbac/chengis-timeout-ms  (constantly 1000)]
    (let [backend (rbac/->ChengisBackend)
          result  (rbac/check! backend {:tenant-id "t1"
                                        :principal "eve"
                                        :action    :write
                                        :resource  "/jobs"})]
      (is (false? (:allowed? result)))
      (is (.contains (:reason result) "connection refused")))))

(deftest chengis-backend-audit-posts-to-audit-endpoint
  (let [calls (atom [])]
    (with-redefs [org.httpkit.client/request
                  (fn [opts]
                    (swap! calls conj opts)
                    (future {:status 201 :body "{}"}))
                  anvil.tenancy.rbac/chengis-service-url (constantly "http://chengis:8090")
                  anvil.tenancy.rbac/chengis-token       (constantly "tok")
                  anvil.tenancy.rbac/chengis-timeout-ms  (constantly 1000)]
      (let [backend (rbac/->ChengisBackend)]
        (rbac/record-audit! backend {:tenant-id "t1"
                                     :principal "alice"
                                     :action    :build
                                     :resource  "/jobs/demo"
                                     :result    :allowed})
        (let [call (first @calls)
              body (json/read-str (:body call) :key-fn keyword)]
          (is (= "http://chengis:8090/api/v1/audit" (:url call)))
          (is (= "t1" (:tenant_id body)))
          (is (= "allowed" (:result body))))))))
