(ns anvil.tenancy.audit-subscriber
  "v0.5 T4.3 -- Audit-log bus subscriber.

   Subscribes to :build-started, :build-done, :job-created, and
   :credential-added on the global bus and writes audit events to
   the active RBAC backend's audit log.

   When :multi-tenant is off, all calls to record-audit! are no-ops
   (the NoOpBackend silently drops them). When on, they flow to the
   chengis audit service.

   Wire-up in anvil.core (gated by :multi-tenant flag):
       (when (features/enabled? :multi-tenant)
         ((requiring-resolve 'anvil.tenancy.audit-subscriber/start!)))"
  (:require [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.features :as features]
            [anvil.tenancy.rbac :as rbac]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Tenant/principal extraction from bus events
;; ---------------------------------------------------------------------------

(defn- tenant-from-event [event]
  ;; Producers that run in a multi-tenant context should set :tenant-id
  ;; in the payload. Without it, 'default' is used (single-tenant compat).
  (or (get-in event [:payload :tenant-id])
      "default"))

(defn- principal-from-event [event]
  ;; Producers should set :triggered-by in the payload for attribution.
  ;; Without it, 'system' is used (machine-initiated events).
  (or (get-in event [:payload :triggered-by])
      "system"))

;; ---------------------------------------------------------------------------
;; Event handlers
;; ---------------------------------------------------------------------------

(defn- on-build-started [event]
  (try
    (let [tenant    (tenant-from-event event)
          principal (principal-from-event event)
          {:keys [job-name build-number]} (:payload event)]
      (rbac/audit! tenant principal :build
                   (str "/jobs/" job-name)
                   :allowed
                   :context {:build-number build-number}))
    (catch Throwable t
      (log/warn t "anvil.audit-subscriber/on-build-started"))))

(defn- on-build-done [event]
  (try
    (let [tenant    (tenant-from-event event)
          principal (principal-from-event event)
          {:keys [job-name build-number result]} (:payload event)]
      (rbac/audit! tenant principal :build
                   (str "/jobs/" job-name)
                   :allowed
                   :context {:build-number build-number :result result}))
    (catch Throwable t
      (log/warn t "anvil.audit-subscriber/on-build-done"))))

(defn- on-job-created [event]
  (try
    (let [tenant    (tenant-from-event event)
          principal (principal-from-event event)
          {:keys [job-name]} (:payload event)]
      (rbac/audit! tenant principal :write
                   "/jobs"
                   :allowed
                   :context {:job-name job-name}))
    (catch Throwable t
      (log/warn t "anvil.audit-subscriber/on-job-created"))))

(defn- on-credential-added [event]
  (try
    (let [tenant    (tenant-from-event event)
          principal (principal-from-event event)
          {:keys [credential-id kind]} (:payload event)]
      (rbac/audit! tenant principal :admin
                   "/credentials"
                   :allowed
                   :context {:credential-id credential-id :kind kind}))
    (catch Throwable t
      (log/warn t "anvil.audit-subscriber/on-credential-added"))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defonce ^:private subscription-tokens (atom []))

(defn start!
  "Subscribe to global bus events for audit logging.
   Idempotent via stop!/reset first."
  []
  (doseq [t @subscription-tokens] (bus/unsubscribe! t))
  (reset! subscription-tokens
          [(bus/subscribe! topics/topic-global
                           (fn [ev]
                             (case (:type ev)
                               :build-started    (on-build-started ev)
                               :build-done       (on-build-done ev)
                               :job-created      (on-job-created ev)
                               :credential-added (on-credential-added ev)
                               nil)))])
  (log/info "anvil.audit-subscriber: started"))

(defn stop!
  "Unsubscribe all audit-subscriber bus tokens."
  []
  (doseq [t @subscription-tokens] (bus/unsubscribe! t))
  (reset! subscription-tokens [])
  (log/info "anvil.audit-subscriber: stopped"))
