(ns anvil.tenancy.middleware
  "v0.5 T4.2 -- Ring middleware for RBAC enforcement.

   When :multi-tenant is off, wrap-rbac is identity -- the request passes
   through unchanged and the single-tenant path holds.

   When :multi-tenant is on, wrap-rbac:
     1. Extracts the tenant-id from the request (X-Anvil-Tenant header or
        :anvil-tenant-id in the request map set by upstream auth).
     2. Extracts the principal from the Bearer token / X-Anvil-Principal
        header (or 'anonymous' if absent -- localhost-dev friendly).
     3. Checks the active RbacBackend with the route's :anvil/action and
        :anvil/resource metadata (or falls back to :read / request URI).
     4. On :allowed? true: adds :anvil/tenant-id and :anvil/principal to
        the request and delegates to the inner handler.
     5. On :allowed? false: returns HTTP 403 with the reason.

   ## Route metadata

   To declare the required permission on a reitit route:

       [\"/jobs/:job-name/trigger\"
        {:post  {:handler  my-handler
                 :anvil/action   :build
                 :anvil/resource \"/jobs\"}}]

   Without the metadata, defaults are: action = :read, resource = request URI.

   ## Tenant extraction order

     1. :anvil/tenant-id set by an upstream middleware
     2. X-Anvil-Tenant header
     3. 'default' (single-tenant compat -- never used when flag is on in
        a real multi-tenant deployment, but prevents nil from breaking things)

   ## Principal extraction order

     1. :anvil/principal set by upstream auth middleware
     2. Authorization: Bearer <token> -> validate via anvil.tenancy.session
     3. X-Anvil-Principal header (for service-to-service requests)
     4. 'anonymous'"
  (:require [anvil.features :as features]
            [anvil.tenancy.rbac :as rbac]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Request identity extraction
;; ---------------------------------------------------------------------------

(defn- extract-tenant [req]
  (or (:anvil/tenant-id req)
      (get-in req [:headers "x-anvil-tenant"])
      "default"))

(defn- extract-principal [req]
  (or (:anvil/principal req)
      (when-let [auth (get-in req [:headers "authorization"])]
        (when (.startsWith auth "Bearer ")
          ;; Token-to-principal resolution would normally call
          ;; anvil.tenancy.session/principal-for-token here.
          ;; For v0.5 we use the token as the principal directly --
          ;; a real SSO integration in T4.4 will replace this.
          (subs auth 7)))
      (get-in req [:headers "x-anvil-principal"])
      "anonymous"))

(defn- extract-action [req route-match]
  (or (get-in route-match [:data :anvil/action])
      :read))

(defn- extract-resource [req route-match]
  (or (get-in route-match [:data :anvil/resource])
      (:uri req)
      "/"))

;; ---------------------------------------------------------------------------
;; 403 response
;; ---------------------------------------------------------------------------

(defn- forbidden [reason]
  {:status  403
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    (str "anvil: permission denied — " reason "\n")})

;; ---------------------------------------------------------------------------
;; Core middleware
;; ---------------------------------------------------------------------------

(defn wrap-rbac
  "Ring middleware factory. Wraps `handler` with RBAC enforcement.

   When :multi-tenant feature flag is off, this is a no-op wrapper (pass-through).

   `route-match` is the reitit match data for the current request; pass it
   from the reitit router or as `nil` to use defaults.

   Usage in routes.clj:

       (ring/ring-handler
         (ring/router routes)
         (ring/create-default-handler)
         {:middleware [(fn [h] (middleware/wrap-rbac h nil))]})

   Or per-route (wrap the handler fn directly):

       {:get {:handler (middleware/wrap-rbac my-handler {:data {:anvil/action :read}})}}"
  [handler route-match]
  (fn [req]
    (if-not (features/enabled? :multi-tenant)
      ;; Flag off -- single-tenant pass-through (no perf overhead)
      (handler req)
      ;; Flag on -- extract identity, check, log audit
      (let [tenant    (extract-tenant req)
            principal (extract-principal req)
            action    (extract-action req route-match)
            resource  (extract-resource req route-match)
            {:keys [allowed? reason]} (rbac/check! (rbac/backend)
                                                   {:tenant-id tenant
                                                    :principal principal
                                                    :action    action
                                                    :resource  resource})]
        (rbac/audit! tenant principal action resource
                     (if allowed? :allowed :denied))
        (if allowed?
          (handler (assoc req
                          :anvil/tenant-id  tenant
                          :anvil/principal  principal))
          (do
            (log/info "anvil.rbac: denied" principal action resource
                      "(tenant=" tenant ") reason=" reason)
            (forbidden reason)))))))

(defn wrap-rbac-route
  "Convenience wrapper for use directly in a reitit route handler map.

   Example:
     [\"/cost\" {:get (wrap-rbac-route cost-page/handler :read \"/cost\")}]"
  [handler action resource]
  (fn [req]
    (if-not (features/enabled? :multi-tenant)
      (handler req)
      (let [tenant    (extract-tenant req)
            principal (extract-principal req)
            {:keys [allowed? reason]} (rbac/check! (rbac/backend)
                                                   {:tenant-id tenant
                                                    :principal principal
                                                    :action    action
                                                    :resource  resource})]
        (rbac/audit! tenant principal action resource
                     (if allowed? :allowed :denied))
        (if allowed?
          (handler (assoc req
                          :anvil/tenant-id  tenant
                          :anvil/principal  principal))
          (do
            (log/info "anvil.rbac: denied" principal action resource)
            (forbidden reason)))))))
