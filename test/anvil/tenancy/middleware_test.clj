(ns anvil.tenancy.middleware-test
  "Tests for T4.2 -- RBAC middleware."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.tenancy.rbac :as rbac]
            [anvil.tenancy.middleware :as mw]))

(use-fixtures :each
  (fn [f]
    (rbac/set-backend! rbac/noop-backend)
    (try (f) (finally (rbac/set-backend! rbac/noop-backend)))))

(defn- ok-handler [req]
  {:status 200 :body "ok" :request req})

;; ---------------------------------------------------------------------------
;; Flag off -- pass-through
;; ---------------------------------------------------------------------------

(deftest wrap-rbac-flag-off-is-passthrough
  (with-redefs [anvil.features/enabled? (fn [_] false)]
    (let [wrapped (mw/wrap-rbac ok-handler nil)
          req     {:uri "/jobs" :headers {}}
          resp    (wrapped req)]
      (is (= 200 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Flag on -- allow path
;; ---------------------------------------------------------------------------

(deftest wrap-rbac-flag-on-allows-when-backend-allows
  (rbac/set-backend! (reify rbac/RbacBackend
                       (check! [_ _] {:allowed? true :reason "ok"})
                       (record-audit! [_ _] nil)))
  (with-redefs [anvil.features/enabled? (fn [_] true)]
    (let [wrapped (mw/wrap-rbac ok-handler nil)
          req     {:uri     "/jobs"
                   :headers {"x-anvil-tenant"    "t1"
                             "x-anvil-principal" "alice"}}
          resp    (wrapped req)]
      (is (= 200 (:status resp)))
      (testing "tenant and principal injected"
        (is (= "t1"    (get-in resp [:request :anvil/tenant-id])))
        (is (= "alice" (get-in resp [:request :anvil/principal])))))))

;; ---------------------------------------------------------------------------
;; Flag on -- deny path
;; ---------------------------------------------------------------------------

(deftest wrap-rbac-flag-on-returns-403-when-backend-denies
  (rbac/set-backend! (reify rbac/RbacBackend
                       (check! [_ _] {:allowed? false :reason "not authorized"})
                       (record-audit! [_ _] nil)))
  (with-redefs [anvil.features/enabled? (fn [_] true)]
    (let [wrapped (mw/wrap-rbac ok-handler nil)
          req     {:uri "/credentials" :headers {}}
          resp    (wrapped req)]
      (is (= 403 (:status resp)))
      (is (.contains (:body resp) "not authorized")))))

;; ---------------------------------------------------------------------------
;; Tenant / principal extraction
;; ---------------------------------------------------------------------------

(deftest wrap-rbac-uses-default-tenant-when-none-in-request
  (let [recorded (atom nil)]
    (rbac/set-backend! (reify rbac/RbacBackend
                         (check! [_ p] (reset! recorded p) {:allowed? true :reason "ok"})
                         (record-audit! [_ _] nil)))
    (with-redefs [anvil.features/enabled? (fn [_] true)]
      ((mw/wrap-rbac ok-handler nil) {:uri "/jobs" :headers {}})
      (is (= "default" (:tenant-id @recorded))))))

(deftest wrap-rbac-uses-anonymous-principal-when-none
  (let [recorded (atom nil)]
    (rbac/set-backend! (reify rbac/RbacBackend
                         (check! [_ p] (reset! recorded p) {:allowed? true :reason "ok"})
                         (record-audit! [_ _] nil)))
    (with-redefs [anvil.features/enabled? (fn [_] true)]
      ((mw/wrap-rbac ok-handler nil) {:uri "/jobs" :headers {}})
      (is (= "anonymous" (:principal @recorded))))))

(deftest wrap-rbac-extracts-bearer-token-as-principal
  (let [recorded (atom nil)]
    (rbac/set-backend! (reify rbac/RbacBackend
                         (check! [_ p] (reset! recorded p) {:allowed? true :reason "ok"})
                         (record-audit! [_ _] nil)))
    (with-redefs [anvil.features/enabled? (fn [_] true)]
      ((mw/wrap-rbac ok-handler nil)
       {:uri "/jobs" :headers {"authorization" "Bearer my-token"}})
      (is (= "my-token" (:principal @recorded))))))

;; ---------------------------------------------------------------------------
;; wrap-rbac-route convenience helper
;; ---------------------------------------------------------------------------

(deftest wrap-rbac-route-allows-when-flag-off
  (with-redefs [anvil.features/enabled? (fn [_] false)]
    (let [wrapped (mw/wrap-rbac-route ok-handler :read "/jobs")
          resp    (wrapped {:uri "/jobs" :headers {}})]
      (is (= 200 (:status resp))))))

(deftest wrap-rbac-route-denies-with-custom-resource
  (rbac/set-backend! (reify rbac/RbacBackend
                       (check! [_ p]
                         (if (= :admin (:action p))
                           {:allowed? false :reason "admin only"}
                           {:allowed? true :reason "ok"}))
                       (record-audit! [_ _] nil)))
  (with-redefs [anvil.features/enabled? (fn [_] true)]
    (let [wrapped (mw/wrap-rbac-route ok-handler :admin "/credentials")
          resp    (wrapped {:uri "/credentials" :headers {}})]
      (is (= 403 (:status resp))))))
