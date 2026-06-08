(ns anvil.tenancy.rbac
  "v0.5 T4.2 -- Chengis RBAC protocol and adapter.

   Defines the permission-check interface that anvil's middleware uses.
   Actual implementations:

     - NoOpBackend (always-allow): used when :multi-tenant is off.
     - ChengisBackend: delegates to a chengis 0.1 RBAC service over HTTP
       when :multi-tenant is on.

   ## Design (per AV5-5)

   Chengis 0.1 ships from its own repo. Anvil does NOT bundle chengis;
   instead, it talks to a running chengis service via HTTP. When no
   chengis service is configured, the adapter is a no-op and anvil
   behaves as single-tenant exactly as today.

   ## Protocol

   The `RbacBackend` protocol has two methods:

     (check! backend {:keys [tenant-id principal action resource]})
       Returns {:allowed? true/false :reason STRING}.
       Implementations must not throw -- return {:allowed? false :reason \"error ...\"} on failure.

     (record-audit! backend {:keys [tenant-id principal action resource result context]})
       Fire-and-forget audit log write. Returns nil. Should not throw.

   ## Config (anvil.edn)

       {:anvil.tenancy/service-url \"http://chengis:8090\"
        :anvil.tenancy/token-env   \"CHENGIS_TOKEN\"
        :anvil.tenancy/timeout-ms  2000}

   When service-url is absent and the flag is on, the adapter falls back
   to the local-auth single-tenant path with a WARN logged once at startup."
  (:require [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [anvil.config :as config]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol RbacBackend
  "Permission-check + audit-log interface for anvil's tenancy middleware."
  (check! [this params]
    "Check whether `principal` is allowed to perform `action` on `resource`
     in `tenant-id`.

     Params map:
       :tenant-id  -- tenant identifier (string)
       :principal  -- user or service identity (string)
       :action     -- verb keyword, e.g. :read :write :build :admin
       :resource   -- resource identifier, e.g. '/jobs/demo'

     Returns {:allowed? BOOL :reason STRING}.
     Never throws -- implementations catch internally and return denied on error.")

  (record-audit! [this event]
    "Write an audit event to the backend's audit log.

     Event map:
       :tenant-id  -- tenant identifier
       :principal  -- actor
       :action     -- verb keyword
       :resource   -- resource identifier
       :result     -- :allowed | :denied | :error
       :context    -- (optional) extra map for caller context

     Returns nil. Fire-and-forget; should not throw."))

;; ---------------------------------------------------------------------------
;; No-op backend (single-tenant / flag-off)
;; ---------------------------------------------------------------------------

(deftype NoOpBackend []
  RbacBackend
  (check! [_ _]
    {:allowed? true :reason "no-op (single-tenant)"})
  (record-audit! [_ _]
    nil))

(def noop-backend
  "Singleton no-op backend. Used when :multi-tenant flag is off.
   All check! calls return {:allowed? true}; record-audit! is a no-op."
  (->NoOpBackend))

;; ---------------------------------------------------------------------------
;; Chengis HTTP backend
;; ---------------------------------------------------------------------------

(defn- chengis-service-url []
  (or (System/getenv "ANVIL_CHENGIS_URL")
      (:anvil.tenancy/service-url (config/load-edn "anvil" {}))))

(defn- chengis-token []
  (let [cfg (config/load-edn "anvil" {})
        env-key (:anvil.tenancy/token-env cfg "CHENGIS_TOKEN")]
    (System/getenv env-key)))

(defn- chengis-timeout-ms []
  (or (:anvil.tenancy/timeout-ms (config/load-edn "anvil" {}))
      2000))

(deftype ChengisBackend []
  RbacBackend
  (check! [_ {:keys [tenant-id principal action resource]}]
    (try
      (let [url (str (chengis-service-url) "/api/v1/rbac/check")
            tok (chengis-token)
            body {:tenant_id tenant-id
                  :principal principal
                  :action    (name action)
                  :resource  resource}
            resp @(http/request
                   (cond-> {:method  :post
                            :url     url
                            :timeout (chengis-timeout-ms)
                            :headers {"Content-Type" "application/json"
                                      "User-Agent"   "anvil-ci"}
                            :body    (json/write-str body)}
                     tok (assoc-in [:headers "Authorization"]
                                   (str "Bearer " tok))))]
        (if (and (:status resp) (< (:status resp) 300))
          (let [parsed (try (json/read-str (:body resp) :key-fn keyword)
                            (catch Throwable _ nil))]
            {:allowed? (boolean (:allowed parsed))
             :reason   (or (:reason parsed) "chengis ok")})
          (do
            (log/warn "anvil.rbac: chengis check returned" (:status resp)
                      "for" principal action resource)
            {:allowed? false
             :reason   (str "chengis HTTP " (:status resp))})))
      (catch Throwable t
        (log/warn t "anvil.rbac: chengis check failed; denying" principal action resource)
        {:allowed? false :reason (str "chengis error: " (.getMessage t))})))

  (record-audit! [_ {:keys [tenant-id principal action resource result context]}]
    (try
      (let [url (str (chengis-service-url) "/api/v1/audit")
            tok (chengis-token)
            body (cond-> {:tenant_id tenant-id
                          :principal principal
                          :action    (name action)
                          :resource  resource
                          :result    (name result)
                          :timestamp (str (java.time.Instant/now))}
                   context (assoc :context context))]
        @(http/request
          (cond-> {:method  :post
                   :url     url
                   :timeout (chengis-timeout-ms)
                   :headers {"Content-Type" "application/json"
                             "User-Agent"   "anvil-ci"}
                   :body    (json/write-str body)}
            tok (assoc-in [:headers "Authorization"]
                          (str "Bearer " tok)))))
      (catch Throwable t
        (log/warn t "anvil.rbac: audit write failed for" principal action resource)))
    nil))

;; ---------------------------------------------------------------------------
;; Backend registry
;; ---------------------------------------------------------------------------

(defonce ^:private active-backend (atom noop-backend))

(defn set-backend!
  "Install `backend` as the active RBAC backend. Called by tenancy/init!
   at daemon startup based on the :multi-tenant feature flag."
  [backend]
  (reset! active-backend backend)
  nil)

(defn backend
  "Return the currently active RBAC backend."
  []
  @active-backend)

;; ---------------------------------------------------------------------------
;; High-level helpers (call the active backend)
;; ---------------------------------------------------------------------------

(defn allowed?
  "True if the current request's principal is allowed to perform `action`
   on `resource` in `tenant-id`."
  [tenant-id principal action resource]
  (:allowed? (check! @active-backend
                     {:tenant-id tenant-id
                      :principal principal
                      :action    action
                      :resource  resource})))

(defn audit!
  "Write an audit event via the active backend. Fire-and-forget."
  [tenant-id principal action resource result & {:keys [context]}]
  (record-audit! @active-backend
                 {:tenant-id tenant-id
                  :principal principal
                  :action    action
                  :resource  resource
                  :result    result
                  :context   context})
  nil)
