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
    :provenance})

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

   Unknown flags in the file are ignored (forward-compat). Missing
   flags are recorded as `false`."
  []
  (let [edn (config/load-edn "anvil" {})
        flags (into {}
                    (for [f known-features]
                      [f (boolean (get edn (flag-keyword f) false))]))
        on (filter #(get flags %) known-features)]
    (reset! state flags)
    (log/info (str "anvil.features: "
                   (if (seq on)
                     (str (count on) "/" (count known-features)
                          " enabled — " (pr-str (sort on)))
                     (str "all " (count known-features) " disabled (v0.3 default)"))))
    flags))

(defn enabled?
  "True if feature `f` (one of `known-features`) is enabled. Unknown
   features return `false` — closed-by-default protects against a
   feature being queried before its flag was registered."
  [f]
  (boolean (get @state f false)))

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
