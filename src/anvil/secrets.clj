(ns anvil.secrets
  "T2.1 — `SecretBackend` protocol + active-backend registry.

   anvil's withCredentials step needs to map a credentialsId to a
   secret value at dispatch time. v0.3 shipped a local-disk SQLite-
   encrypted store (anvil.storage.credentials). v0.6 T2 generalises
   that into a protocol with two more reference impls: Hashicorp Vault
   (`anvil.secrets.vault`) and Cloud-KMS (`anvil.secrets.kms`).

   ## Protocol

     (resolve! [this id]) → {:value <str> :type <kw>} or nil
       Synchronous lookup. nil means \"this backend doesn't know about
       this id\" (the caller may fall back to another backend). The
       returned map carries the decrypted secret value PLUS its type
       (:string / :username-password / :file). Backends that can't
       express type metadata return :string.

     (list-ids [this]) → seq of credentialId strings
       For the /secrets admin UI. NEVER returns values.

   ## Registry

   At process startup, `anvil.core` calls `install-default-backend!`
   which registers the local-disk store as the active backend. T2 PRs
   add the Vault + KMS adapters; the operator chooses one via
   anvil.edn:

       ;; anvil.edn
       {:anvil.features/vault-backend true
        :anvil.vault/url \"https://vault.example.com\"
        :anvil.vault/token-path \"/var/run/secrets/vault-token\"
        :anvil.vault/kv-mount \"secret\"
        :anvil.vault/kv-path-prefix \"anvil/\"}

   When `:vault-backend` is enabled at startup, anvil.secrets.vault's
   constructor installs itself as the active backend. Same for KMS.
   Closed-by-default: with no flag flipped, the local-disk store
   remains the resolver.

   ## Audit event

   On every successful resolve! call (returning non-nil), the dispatcher
   publishes `:secret-resolved` on the per-build topic. The payload
   carries credential-id + backend type + latency in ms — NEVER the
   secret value. See `anvil.events.topics/evt-secret-resolved`.

   The `emit-resolved!` helper in this ns wraps the timing + publish
   logic so backends don't reinvent it. Backends call resolve! through
   `resolve-with-emit!` which measures latency, publishes the event,
   and returns the credential map (or nil).

   ## AV6-3 — operator-pluggable

   Operators may register their own backend via `register-backend!`.
   The protocol is small on purpose: implement `resolve!` + `list-ids`
   and you're a first-class backend. Custom HSMs, dev-secrets, etc."
  (:require [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol SecretBackend
  "Two-method protocol. Implementations MUST be thread-safe; resolve!
   may be called concurrently from multiple dispatch threads."
  (resolve! [this id]
    "Look up `id` and return {:value <decrypted-str> :type <kw>} or nil
     when this backend has no value for that id. nil lets the caller
     compose backends (try-vault, then try-local) without each impl
     reimplementing the fallback.")
  (list-ids [this]
    "Return a seq of credentialId strings known to this backend. Used
     by the admin UI. MUST NOT return values."))

(defn backend-kind
  "Tag the backend with a small keyword (:local / :vault / :kms / ...).
   Used in the :secret-resolved event payload so operators can audit
   which backend served which lookup. Defaults to :unknown for
   third-party impls that haven't supplied a kind via metadata."
  [backend]
  (or (some-> backend meta :anvil.secrets/kind)
      :unknown))

;; ---------------------------------------------------------------------------
;; Default impl — wraps anvil.storage.credentials
;; ---------------------------------------------------------------------------

(defrecord LocalDiskBackend []
  SecretBackend
  (resolve! [_ id]
    ;; Lazy require so anvil.secrets can be required from tests that
    ;; don't want the SQLite store on the classpath path.
    (try
      (when-let [lookup (requiring-resolve 'anvil.storage.credentials/lookup)]
        (when-let [row (lookup id)]
          (select-keys row [:value :type])))
      (catch Throwable t
        (log/debug t "anvil.secrets/LocalDiskBackend: lookup failed")
        nil)))
  (list-ids [_]
    (try
      (when-let [list-all (requiring-resolve 'anvil.storage.credentials/list-all)]
        (mapv :id (list-all)))
      (catch Throwable t
        (log/debug t "anvil.secrets/LocalDiskBackend: list failed")
        []))))

(defn local-disk-backend
  "Construct the default backend that wraps the SQLite-backed
   anvil.storage.credentials store. Tagged with :anvil.secrets/kind
   `:local` so audit events identify it."
  []
  (with-meta (->LocalDiskBackend) {:anvil.secrets/kind :local}))

;; ---------------------------------------------------------------------------
;; Active-backend registry
;; ---------------------------------------------------------------------------

(defonce ^:private active (atom nil))

(defn register-backend!
  "Install `backend` as the active secret backend. Returns the
   previous backend (or nil if none). Subsequent resolve! calls go
   through `backend`.

   Operators / adapter constructors call this. anvil.core calls
   `install-default-backend!` at startup; Vault / KMS constructors
   may then take over per the feature-flag wiring."
  [backend]
  (let [prev @active]
    (reset! active backend)
    (log/info (str "anvil.secrets: backend installed — "
                   (name (backend-kind backend))))
    prev))

(defn active-backend
  "Return the currently active backend, or a freshly-constructed
   `local-disk-backend` if nothing's been installed yet. Used by the
   dispatcher's `resolve-credential-from-store` so a test that hasn't
   called `install-default-backend!` still gets a sane resolver."
  []
  (or @active
      ;; Install lazily on first access so test-suites that never
      ;; touch the SQLite store don't pay any setup cost.
      (let [b (local-disk-backend)]
        (compare-and-set! active nil b)
        @active)))

(defn install-default-backend!
  "Idempotent — install `local-disk-backend` as the active backend if
   nothing else has claimed the slot. Called from anvil.core/run-daemon."
  []
  (compare-and-set! active nil (local-disk-backend))
  (active-backend))

(defn reset-for-tests!
  "Clear the active-backend registry. Tests that install a fake
   backend should call this in their fixture's teardown."
  []
  (reset! active nil))

;; ---------------------------------------------------------------------------
;; Resolve + emit
;; ---------------------------------------------------------------------------

(defn- publish-resolved!
  "Publish a :secret-resolved event on the per-build topic. The event
   NEVER carries the secret value — only credential-id + backend kind
   + latency-ms. Topic-resolution is lazy via requiring-resolve so
   this ns doesn't load the bus into tests that don't need it.

   `ctx` is the dispatcher ctx (carries :job-name + :build-number when
   available). If the ctx lacks either, we skip the publish — the
   event is per-build only, and a backend-driven resolve outside a
   build (e.g. admin UI preview) has nothing to publish to."
  [ctx credential-id kind latency-ms]
  (try
    (let [jn (:job-name ctx)
          bn (:build-number ctx)]
      (when (and jn bn)
        (let [publish!    (requiring-resolve 'anvil.events.bus/publish!)
              topic-build (requiring-resolve 'anvil.events.topics/topic-build)
              evt         (requiring-resolve 'anvil.events.topics/evt-secret-resolved)]
          (when (and publish! topic-build evt)
            (publish! (topic-build jn bn)
                      {:type           @evt
                       :job-name       jn
                       :build-number   bn
                       :credential-id  credential-id
                       :backend        kind
                       :latency-ms     latency-ms})))))
    (catch Throwable t
      (log/debug t "anvil.secrets: :secret-resolved publish failed (non-fatal)"))))

(defn- assert-no-value-leak!
  "Defence-in-depth — if a backend's resolve! returned a map containing
   a :value, ensure the payload we're about to publish does NOT carry
   it. Throws at dev/test time; logs + drops at production. The
   protocol's contract is no-value-in-event, but a buggy custom
   backend that hands us a string-formatted map could surprise us."
  [payload]
  (when (contains? payload :value)
    (let [msg "anvil.secrets: refusing to publish :secret-resolved with :value key"]
      (log/error msg)
      (throw (ex-info msg {:offending-keys (vec (keys payload))})))))

(defn publish-resolved-event!
  "Public entry point for callers that did their own resolve via
   `(active-backend)` + `resolve!`. Emits the :secret-resolved SSE
   event using the active backend's kind. Used by the dispatcher's
   `inject-credentials-into-env` which threads its own latency
   measurement around the lookup. The event payload NEVER carries
   the secret value."
  [ctx credential-id latency-ms]
  (let [kind (backend-kind (active-backend))
        payload {:credential-id credential-id
                 :backend kind
                 :latency-ms latency-ms}]
    (assert-no-value-leak! payload)
    (publish-resolved! ctx credential-id kind latency-ms)))

(defn resolve-with-emit!
  "Production entry point — call `resolve!` on the active backend,
   measure latency, publish :secret-resolved if a value came back,
   and return the credential map (or nil).

   `ctx` is the dispatcher ctx for :job-name / :build-number lookup.
   Pass nil/empty ctx for non-build callers (admin UI preview).

   The :secret-resolved event NEVER contains the secret value — by
   construction. `assert-no-value-leak!` checks the payload we
   actually build, defensively."
  [ctx credential-id]
  (let [backend (active-backend)
        kind    (backend-kind backend)
        t0      (System/nanoTime)
        result  (resolve! backend credential-id)
        t1      (System/nanoTime)
        ms      (long (/ (- t1 t0) 1e6))]
    (when result
      (let [payload {:credential-id credential-id
                     :backend       kind
                     :latency-ms    ms}]
        (assert-no-value-leak! payload)
        (publish-resolved! ctx credential-id kind ms)))
    result))
