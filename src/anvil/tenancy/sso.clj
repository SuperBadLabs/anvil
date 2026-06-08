(ns anvil.tenancy.sso
  "v0.5 T4.4 -- SSO entry-point stub.

   When :multi-tenant is on AND :anvil.tenancy/sso-url is configured,
   unauthenticated requests to anvil web routes are redirected to the
   chengis SSO endpoint instead of being served directly.

   When :multi-tenant is off (the default), this namespace is never
   loaded and anvil's existing local-auth / no-auth path holds exactly
   as today. The flag check happens in wrap-sso-redirect below.

   ## How it works (when enabled)

   1. The middleware checks for an :anvil/principal in the request. If
      present (set by an upstream token-validation step), the request
      proceeds normally.
   2. If absent (unauthenticated), the middleware issues an HTTP 302 to
      the chengis SSO login URL with a `return_to` query param pointing
      back to the original request URI.
   3. The chengis SSO flow sets a session cookie; subsequent requests are
      validated by anvil.tenancy.session/validate! (T4.4 scope: stub
      that reads the cookie; full JWT validation defers to chengis 0.1).

   ## Config (anvil.edn)

       {:anvil.tenancy/sso-url    \"http://chengis:8090/sso/login\"
        :anvil.tenancy/cookie-key \"anvil-session\"}

   When sso-url is absent but :multi-tenant is on, anvil falls back to
   accepting X-Anvil-Principal header as trust-on-first-use (dev-friendly;
   NOT for production). Log a WARN at daemon startup.

   ## Honest gaps (AV5-6)

   - Full JWT/OIDC token validation defers to chengis 0.1.
   - Session persistence (server-side session store) defers to chengis 0.1.
   - Token revocation and refresh defers to chengis 0.1.
   - The stub reads X-Anvil-Principal as-is; any downstream code that
     trusts :anvil/principal must be aware of this gap in the v0.5 dev build."
  (:require [anvil.features :as features]
            [anvil.config :as config]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(defn- sso-url []
  (:anvil.tenancy/sso-url (config/load-edn "anvil" {})))

(defn- cookie-key []
  (or (:anvil.tenancy/cookie-key (config/load-edn "anvil" {}))
      "anvil-session"))

;; ---------------------------------------------------------------------------
;; Session validation stub
;; ---------------------------------------------------------------------------

(defn validate-session
  "Validate the session token from the request cookie or Authorization header.
   Returns {:principal STRING} on success or nil on failure.

   v0.5 stub: reads the cookie value or X-Anvil-Principal header directly.
   Full JWT validation defers to chengis 0.1 (T4.4 honest gap)."
  [req]
  (let [ck (cookie-key)
        ;; Check cookie first (browser flow)
        cookie-val (get-in req [:cookies ck :value])
        ;; Then check X-Anvil-Principal (service-to-service)
        header-val (get-in req [:headers "x-anvil-principal"])]
    (when-let [p (or cookie-val header-val)]
      {:principal p})))

;; ---------------------------------------------------------------------------
;; SSO redirect middleware
;; ---------------------------------------------------------------------------

(defn- redirect-to-sso [req sso-endpoint]
  (let [return-to (str (:uri req)
                       (when-let [q (:query-string req)] (str "?" q)))]
    {:status  302
     :headers {"Location" (str sso-endpoint
                               "?return_to=" (java.net.URLEncoder/encode return-to "UTF-8"))}
     :body    ""}))

(defn wrap-sso-redirect
  "Ring middleware: when :multi-tenant is on and the request has no
   authenticated principal, redirect to the chengis SSO login page.

   When :multi-tenant is off, this is a pass-through.
   When :multi-tenant is on but sso-url is not configured, validates
   via X-Anvil-Principal header (dev-mode stub; logs a WARN once)."
  [handler]
  (let [warned? (atom false)]
    (fn [req]
      (if-not (features/enabled? :multi-tenant)
        (handler req)
        (if-let [session (validate-session req)]
          ;; Already authenticated -- inject principal and proceed
          (handler (assoc req :anvil/principal (:principal session)))
          ;; Not authenticated
          (if-let [ep (sso-url)]
            (redirect-to-sso req ep)
            ;; No SSO URL configured -- dev-mode fallback
            (do
              (when-not @warned?
                (reset! warned? true)
                (log/warn (str "anvil.sso: :multi-tenant is on but :anvil.tenancy/sso-url "
                               "is not set in anvil.edn. "
                               "Accepting X-Anvil-Principal header as trust-on-first-use. "
                               "DO NOT use this in production.")))
              (handler req))))))))
