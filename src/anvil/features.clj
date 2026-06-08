(ns anvil.features
  "Feature-flag mechanism for in-progress v0.3 / v0.4 work.

   v0.3 shipped seven Tier-1 features across T1–T7 of the parity board:

     | flag                | tranche | shipped in |
     |---------------------|---------|------------|
     | :junit              | T1      | v0.3.0     |
     | :problem-matchers   | T2      | v0.3.0     |
     | :pr-checks          | T3      | v0.3.0     |
     | :matrix             | T4      | v0.3.0     |
     | :scheduler          | T5      | v0.3.0     |
     | :secrets            | T6      | v0.3.0     |
     | :mise               | T7      | v0.3.0     |

   v0.4 reserves four leapfrog flags (closed-by-default through the v0.4
   ship; flipped to true as each tranche merges):

     | flag                | tranche | target     |
     |---------------------|---------|------------|
     | :flaky              | T1      | v0.4.0     |
     | :container-step     | T2      | v0.4.0     |
     | :ai-authoring       | T3      | v0.4.0     |
     | :provenance         | T4      | v0.4.0     |

   The AN6 honesty-series tickets (T5 of the v0.4 board) ride on
   existing flags (translator + dispatcher paths) and do NOT reserve
   new flag keys — they're parity work, not new feature surfaces.

   While T1–T4 are merging incrementally on master, the flags default
   to `false`. That keeps half-finished UIs and routes invisible to
   dogfood users until the tranche owner flips the flag to `true`
   (typically in the last commit of the tranche). Each feature's
   routes / SSE producers / dispatcher hooks check `enabled?` before
   doing anything; disabled features 404 on the route layer rather
   than rendering a half-page.

   ## Config source

   Flags live under the namespaced key `:anvil.features/<feature>` in
   `anvil.edn`, which loads through `anvil.config/load-edn` (so the
   usual ANVIL_CONFIG_DIR / ./config/ / classpath search order
   applies). Example:

     ;; anvil.edn
     {:anvil.features/junit            true
      :anvil.features/problem-matchers false}

   Unknown flags in the file are accepted silently (forward compat
   for v0.3.x add-ons). Unknown flags queried at runtime return
   `false` (closed-by-default).

   ## Lifecycle

   `(load-flags!)` is called once at daemon startup (from
   `anvil.core/run-daemon`). Tests that need a specific flag state
   call `set!` directly. No hot-reload — restart the process to
   pick up an edit, matching `anvil.config`'s read-once stance."
  (:require [anvil.config :as config]
            [taoensso.timbre :as log]))

(def known-features
  "The feature keys recognized by anvil v0.3. Add an entry when a new
   tranche reserves its flag; remove when a feature is graduated to
   always-on (typically in the v0.3.x cycle following its ship).

   :scripted-eval — post-v0.3.0 Tier-3 worthiness work. Routes the
   WHOLE scripted Jenkinsfile through Groovy + anvil's expanded
   Pipeline DSL bindings so GStrings, combinations, destructured
   bindings, etc. work natively. Closed-by-default; existing
   scripted-Pipeline static-IR path remains the v0.3 behavior."
  #{:junit :problem-matchers :pr-checks :matrix :scheduler :secrets :mise
    :scripted-eval

    ;; AN5-4 — h-sh rewrites standalone `mvn ... deploy ...` calls to
    ;; `mvn ... package ...` BEFORE subprocess spawn. Wild-corpus
    ;; Jenkinsfiles call `mvn clean deploy` expecting Apache's deploy
    ;; credentials; without them, the deploy step crashes with HTTP
    ;; 401 and no jar lands despite all earlier phases succeeding.
    ;; With this flag on, the rewrite happens, jar lands in target/,
    ;; archiveArtifacts picks it up. Emits a `[:mvn/deploy-degraded]`
    ;; effect so the rewrite is operator-visible.
    :mvn-deploy-degrade

    ;; --- v0.4 leapfrog reservations (T0.2 of the v0.4 board) ---
    ;;
    ;; :flaky — T1. Passed-on-retry analysis layered on T1 JUnit infra.
    ;; A test that failed an earlier attempt but passed a later attempt
    ;; in the same build gets tagged :flaky? true in test_results.
    ;; UI: /flaky dashboard + per-job flaky widget. Per AV4-3, passed-
    ;; on-retry is the ONLY definition at v0.4.0; statistical models
    ;; defer to v0.4.x.
    :flaky

    ;; :container-step — T2. `steps { container 'image' { sh '...' } }`
    ;; routes the wrapped step through chengis-core's DockerBackend
    ;; (reuses AN5-3 plumbing per AV4-2). Composes with declarative
    ;; matrix — each cell can declare a different image.
    :container-step

    ;; :ai-authoring — T3. `anvil init / explain / optimize` CLI calls
    ;; Anthropic API via ANTHROPIC_API_KEY from env (local-first per
    ;; AV4-4 — never a hosted anvil service). UI adds an Explain
    ;; button on /jobs/<j>. Operator opts in per-job via
    ;; :anvil.ai/explain-enabled? on top of this flag.
    :ai-authoring

    ;; :provenance — T4. Each artifact emitted by a build gets a
    ;; sigstore-signed in-toto v1 attestation written as
    ;; <artifact>.intoto.jsonl. Per AV4-5: sigstore/cosign with
    ;; Fulcio keyless flow by default; long-lived offline key as
    ;; fallback for air-gapped operators (see R4).
    :provenance

    ;; :dockerfile-agent — AN6-3. `agent { dockerfile { filename '…' } }`
    ;; runs `docker build` against the named Dockerfile in the
    ;; workspace, tags the result with a content-hash of the
    ;; Dockerfile + its COPY/ADD sources, and routes the build's
    ;; sh steps into that image via the AN5-3 DockerBackend bridge.
    ;; Closed-by-default per AV4-7 — when off, the dispatcher's
    ;; existing :agent/degraded :runtime-unsupported path surfaces
    ;; the unhonored shape honestly. Implementation lives in
    ;; anvil.tools.dockerfile at v0.4.0; will extract to
    ;; chengis.tools.dockerfile when chengis-core 0.4.0 ships.
    :dockerfile-agent

    ;; --- v0.5 scale-tranche reservations (T0.2 of the v0.5 board) ---
    ;;
    ;; :cache — T1. Content-addressed step-level cache per AV5-2.
    ;; Cache key = (image-digest, command, env-fingerprint, input-
    ;; tree-merkle). Local FS store at ~/.anvil/cache; optional
    ;; remote S3-style store behind the companion :cache-remote
    ;; flag. Closed-by-default through T1 ship; flipped in the last
    ;; T1 commit.
    :cache
    :cache-remote

    ;; :cost-reporting — T2. Wall-time × declared host rate. Per-host
    ;; rates live in :anvil.cost/host-rates in anvil.edn. Recorder
    ;; persists to anvil_build_costs (new migration 012); UI surfaces
    ;; on /cost dashboard + per-build pill. Closed-by-default.
    :cost-reporting

    ;; :gitlab-mr — T3. anvil.integration.gitlab-subscriber mirrors
    ;; the v0.4 T3.4 GitHub Checks pattern (AV5-4) — subscribes to
    ;; :build-started / :build-done, PATCHes the MR status.
    :gitlab-mr

    ;; :bitbucket-pr — T3 companion. anvil.integration.bitbucket-
    ;; subscriber for Bitbucket's PR check API. Ships alongside
    ;; :gitlab-mr; either can be enabled independently.
    :bitbucket-pr

    ;; :multi-tenant — T4. Chengis 0.1 RBAC adapter (AV5-5). When
    ;; on, anvil's auth path routes through chengis-rbac/check;
    ;; audit events flow to chengis audit log. When off, anvil's
    ;; existing single-tenant local-auth path holds — adapter is
    ;; completely no-op. Chengis 0.1 ships from its own repo;
    ;; this flag only enables the optional adapter.
    :multi-tenant

    ;; --- v0.6 runtime-expansion + fidelity reservations
    ;;     (T0.1 of the v0.6 board) ---
    ;;
    ;; :k8s-agent — T1. Kubernetes agent runtime via chengis-core 0.4
    ;; (AV6-2). When on, `agent { kubernetes { yaml '...' } }` and
    ;; `agent { kubernetes { containerTemplate(...) } }` route through
    ;; chengis-core's K8sBackend (pod-per-step lifecycle, kubeconfig
    ;; lookup, :resource-limits → pod resource requests/limits).
    ;; When off, k8s agent shapes degrade to :unsupported honestly.
    ;; Unblocks eclipse-epsilon + eclipse-mojarra + the cassandra-real
    ;; Jenkinsfile (the `cassandra-large` k8s label).
    :k8s-agent

    ;; :vault-backend — T2. anvil.secrets.vault adapter (AV6-3).
    ;; SecretBackend protocol impl backed by Hashicorp Vault KV v2.
    ;; When on, credentials resolved via withCredentials hit Vault
    ;; before the local-disk fallback. Operator configures via
    ;; :anvil.vault/url + :anvil.vault/token-path in anvil.edn.
    ;;
    ;; **Graduated to default-on at v0.6.0** (see `default-on-features`
    ;; below). The flag default-on is safe because the install! path
    ;; refuses to take over without operator config (:anvil.vault/url
    ;; + :token-path) — when those are absent, the local-disk backend
    ;; remains active and behavior is bit-identical to v0.5.x.
    :vault-backend

    ;; :cloud-kms-backend — T2. anvil.secrets.kms adapter (AV6-3).
    ;; SecretBackend protocol impl backed by Cloud KMS (AWS-first;
    ;; GCP + Azure stubs in v0.6.x). Encrypted blob lives in
    ;; anvil.edn / Git; KMS decrypts at resolve time.
    ;;
    ;; **Graduated to default-on at v0.6.0** (see `default-on-features`
    ;; below). Same safety as :vault-backend — without operator config
    ;; (:anvil.kms/provider + region + blobs) the install! path no-ops
    ;; with a WARN; the local-disk backend stays active.
    :cloud-kms-backend

    ;; :dockerfile-multistage — T3. Multi-stage Dockerfile support
    ;; for `agent { dockerfile { filename 'Dockerfile' dir '...'
    ;; args '--target prod' } }`. Builds upon the existing
    ;; :dockerfile-agent flag (v0.4 AN6-3). When on, the --target
    ;; flag is honored and the chengis-core docker backend's
    ;; :dockerfile-build step caches the image hash per (Dockerfile
    ;; content + COPY/ADD sources + --target) tuple. Unblocks
    ;; cassandra-real's multi-stage dockerfile agent.
    :dockerfile-multistage

    ;; :scm-checkout-lifecycle — AN8-4. Universal fidelity fix —
    ;; declarative pipelines without an explicit `checkout scm` step
    ;; get an implicit checkout BEFORE stage 1 runs (matches Jenkins's
    ;; declarative lifecycle). Without this flag, the AN7-5c receipt
    ;; documents the failure mode: `git clean -fxd` exits 128 in
    ;; ~800 ms against an empty workspace. With this flag on, the
    ;; dispatcher inserts the implicit checkout. Closed-by-default
    ;; through T5 ship; flipped to true once AN8-4 receipt lands.
    :scm-checkout-lifecycle})

(def default-on-features
  "Features whose **default** is `true` rather than `false`.

   Used when a tranche ships and the tranche owner decides the new
   behavior should be on out-of-the-box (because it's strictly
   additive — same code path on the disabled side as before, just
   more honored shapes on the enabled side). Listed here, the flag
   defaults to true *unless* anvil.edn explicitly sets it false.

   Closed-by-default remains the rule for features that change
   observable behavior for existing builds — those stay off until
   the operator opts in.

   Currently graduated:
     :vault-backend     — v0.6 T2. install! refuses to take over
                          without operator config; without
                          :anvil.vault/url + :token-path in
                          anvil.edn, the local-disk backend stays
                          active and behavior is bit-identical to
                          v0.5.x.
     :cloud-kms-backend — v0.6 T2. Same posture as :vault-backend
                          — install! no-ops without :anvil.kms
                          config; local-disk stays active."
  #{:vault-backend :cloud-kms-backend})

(def ^:private flag-ns "anvil.features")

(defn- flag-keyword
  "Namespaced keyword form of a feature flag, e.g. `:junit` →
   `:anvil.features/junit`. This is what anvil.edn uses on disk."
  [feature]
  (keyword flag-ns (name feature)))

(defonce ^:private state (atom {}))

(defn load-flags!
  "Read `anvil.edn` via `anvil.config/load-edn` and snapshot the flag
   values into the in-process registry. Idempotent — re-calling
   re-reads the file. Returns the resulting flag map for logging.

   Unknown flags in the file are ignored (forward-compat). For flags
   missing from anvil.edn, the per-flag default applies:
   `default-on-features` membership → true; otherwise → false."
  []
  (let [edn (config/load-edn "anvil" {})
        flags (into {}
                    (for [f known-features]
                      [f (boolean (get edn (flag-keyword f)
                                       (contains? default-on-features f)))]))
        on (filter #(get flags %) known-features)]
    (reset! state flags)
    (log/info (str "anvil.features: "
                   (if (seq on)
                     (str (count on) "/" (count known-features)
                          " enabled — " (pr-str (sort on)))
                     (str "all " (count known-features) " disabled"))))
    flags))

(defn enabled?
  "True if feature `f` (one of `known-features`) is enabled. Unknown
   features return `false` — closed-by-default protects against a
   feature being queried before its flag was registered.

   When the registry is unloaded (before `load-flags!` runs, or in
   bare-bones unit tests that don't touch anvil.edn), features in
   `default-on-features` still report `true`. This matches the
   post-load-flags! behavior so production and test see the same
   answer for default-on flags."
  [f]
  (let [s @state]
    (if (contains? s f)
      (boolean (get s f))
      (contains? default-on-features f))))

(defn set!
  "Test helper: force a flag on or off without going through anvil.edn.
   Use only from tests / REPL — production paths should mutate the
   on-disk config and call `load-flags!` instead."
  [feature on?]
  (swap! state assoc feature (boolean on?))
  nil)

(defn snapshot
  "Return the current flag map. Mainly for diagnostics / future
   /metrics endpoint."
  []
  @state)

;; ---------------------------------------------------------------------------
;; Ring middleware
;; ---------------------------------------------------------------------------

(defn- feature-disabled-404 [feature]
  {:status 404
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str "anvil: feature `" (name feature) "` is disabled.\n"
              "Enable by adding to anvil.edn:\n\n"
              "  {:anvil.features/" (name feature) " true}\n")})

(defn wrap-feature
  "Ring middleware: 404 if `feature` is disabled, else delegate to
   `handler`. Use to gate routes for in-progress v0.3 work:

     (require '[anvil.features :as features])
     [\"/test-results/:build-id\"
      {:get (features/wrap-feature :junit handler-test-results)}]

   The 404 body names the feature + the flag key, so operators who
   hit a disabled route in the browser get a self-explanatory page."
  [feature handler]
  (fn [req]
    (if (enabled? feature)
      (handler req)
      (feature-disabled-404 feature))))
