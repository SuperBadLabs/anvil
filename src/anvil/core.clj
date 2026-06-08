(ns anvil.core
  "Entry point. `lein run` lands here.

   Routing:
     - No args, or first arg is `run`/`--port`: start the daemon.
     - Otherwise: delegate to anvil.cli.core/dispatch for subcommands
       (import, build, help, …).

   This keeps the TX2 daemon-start behavior the default for bare
   `lein run` while making `lein run -- import jenkinsfile <path>` work
   for the TX7 CLI."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [anvil.web.server :as server]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.runner :as runner]
            [anvil.cli.core :as cli]
            [anvil.storage.db :as db]
            [anvil.features :as features]
            [anvil.version :as v]
            [chengis.product :as product])
  (:import [java.util.concurrent CountDownLatch])
  (:gen-class))

(defn- parse-port [args]
  (let [port-idx (.indexOf args "--port")]
    (if (and (>= port-idx 0) (> (count args) (inc port-idx)))
      (try (Integer/parseInt (nth args (inc port-idx)))
           (catch NumberFormatException _ 8080))
      8080)))

(defn- resolve-worker-count
  "Pick the worker pool size. Highest precedence first:
     1. `ANVIL_WORKERS` env var (operator override at boot)
     2. `:anvil.queue/workers` in `anvil.edn`
     3. `queue/default-worker-count` (max(2, cores/4))

   Returns `[n source]` so the boot log can tell operators which knob
   they're on — invaluable when an over-saturated fleet host doesn't
   match the daemon-side config you thought you shipped."
  []
  (or (when-let [s (System/getenv "ANVIL_WORKERS")]
        ;; pos-int? guard: newFixedThreadPool throws on n<=0, and a
        ;; "0 workers" config silently never dispatches anything —
        ;; either way, a typo'd env value should fall through to the
        ;; config / default path, not poison the daemon at boot.
        (try (let [n (Integer/parseInt s)]
               (if (pos? n)
                 [n :env]
                 (do (log/warn (format "ANVIL_WORKERS=%s ignored (must be >0)" s))
                     nil)))
             (catch NumberFormatException _
               (log/warn (format "ANVIL_WORKERS=%s ignored (not an integer)" s))
               nil)))
      (let [cfg ((requiring-resolve 'anvil.config/load-edn) "anvil" {})
            n (:anvil.queue/workers cfg)]
        (when (pos-int? n) [n :config]))
      [(queue/default-worker-count) :default]))

(defn- run-daemon [args]
  (let [port (parse-port args)
        [worker-count worker-source] (resolve-worker-count)
        latch (CountDownLatch. 1)]
    (log/info (str "Starting " (v/version-string)))
    ;; Initialize SQLite persistence on the conventional path
    ;; (~/.anvil/anvil-data.db). Without this, every job/build registered
    ;; via the REST shim stays atom-only and is lost on restart even
    ;; though the persistence layer is fully wired (Codex P1, PR #164).
    ;; Tests that don't want persistence simply never call `lein run`
    ;; and either init! a temp DB themselves or skip persistence entirely.
    (try
      (db/init!)
      (log/info "anvil persistence: " (db/default-db-path))
      (catch Exception e
        (log/warn e "anvil persistence init failed; running atom-only")))
    ;; T0.2 — Load v0.3 feature flags from anvil.edn. Defaults closed;
    ;; per-feature flips enable T1–T7 routes/producers as tranches land.
    (features/load-flags!)
    ;; v0.6 T4 — Hot-reload build-overrides on anvil.edn change. Best
    ;; effort: no-op when anvil.edn is classpath-bundled (no filesystem
    ;; path to watch). When live, an edit-and-save of anvil.edn clears
    ;; the override cache; the next build sees the new shape without
    ;; daemon restart. Never crashes boot — watcher failure falls back
    ;; to v0.5.x restart-to-reload contract.
    (try
      ((requiring-resolve 'anvil.build-overrides/start-watcher!))
      (catch Throwable t
        (log/warn t "anvil.build-overrides: watcher start failed; falling back to restart-to-reload")))
    ;; T3.4 — GitHub Checks subscriber listens to :build-started /
    ;; :build-done on the bus and PATCHes the github check-run state.
    (when (features/enabled? :pr-checks)
      ((requiring-resolve 'anvil.integration.github-subscriber/start!)))
    ;; v0.5 T2.1 — Cost recorder subscribes to :build-done and records
    ;; build cost to anvil_build_costs. Gated by :cost-reporting flag
    ;; (closed by default per AV5-7).
    (when (features/enabled? :cost-reporting)
      ((requiring-resolve 'anvil.cost.recorder/start!)))
    ;; v0.5 T3.1 — GitLab MR commit-status subscriber. Posts running/final
    ;; state to GitLab Commit Status API. Gated by :gitlab-mr flag.
    (when (features/enabled? :gitlab-mr)
      ((requiring-resolve 'anvil.integration.gitlab-subscriber/start!)))
    ;; v0.5 T3.2 — Bitbucket PR commit-status subscriber. Posts
    ;; INPROGRESS/final state to Bitbucket Build Status API.
    ;; Gated by :bitbucket-pr flag.
    (when (features/enabled? :bitbucket-pr)
      ((requiring-resolve 'anvil.integration.bitbucket-subscriber/start!)))
    ;; v0.5 T4 — Chengis RBAC adapter. When :multi-tenant is on,
    ;; installs the ChengisBackend (HTTP calls to chengis service) and
    ;; starts the audit-log subscriber. When off, the NoOpBackend is
    ;; used (already the default at load time).
    (when (features/enabled? :multi-tenant)
      (let [edn ((requiring-resolve 'anvil.config/load-edn) "anvil" {})]
        (if (:anvil.tenancy/service-url edn)
          (let [set-backend! (requiring-resolve 'anvil.tenancy.rbac/set-backend!)
                make-backend (requiring-resolve 'anvil.tenancy.rbac/->ChengisBackend)]
            (set-backend! (make-backend))
            (log/info "anvil.rbac: ChengisBackend installed"))
          (log/warn "anvil.rbac: :multi-tenant on but :anvil.tenancy/service-url not set; using NoOpBackend")))
      ((requiring-resolve 'anvil.tenancy.audit-subscriber/start!)))
    ;; T5.2 — Cron scheduler. Jobs registered from
    ;; :anvil.scheduler/jobs in anvil.edn at startup; trigger-fn
    ;; routes to record-build-start! so a cron fire kicks off a real
    ;; anvil build.
    (when (features/enabled? :scheduler)
      (let [start! (requiring-resolve 'anvil.scheduler.engine/start!)
            reg!   (requiring-resolve 'anvil.scheduler.engine/register-job!)
            cfg    ((requiring-resolve 'anvil.config/load-edn) "anvil" {})
            jobs   (get cfg :anvil.scheduler/jobs)
            trigger-fn (fn [job-name params]
                         (try
                           ((requiring-resolve 'anvil.web.jenkins-api.jobs/record-build-start!)
                            job-name {:parameters params})
                           (catch Throwable t
                             (log/warn t (str "anvil.scheduler: trigger of " job-name " failed")))))]
        (start!)
        (doseq [[job-name expr] jobs]
          (try (reg! job-name expr trigger-fn)
               (catch Throwable t
                 (log/warn t (str "anvil.scheduler: register " job-name " failed")))))))
    (log/info (format "anvil queue: starting %d worker(s) [source=%s, cores=%d]"
                      worker-count (name worker-source)
                      (.availableProcessors (Runtime/getRuntime))))
    (queue/start-workers! worker-count runner/run-build!)
    (server/start! {:port port})
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                       (fn []
                         (log/info "SIGTERM/SIGINT — shutting anvil down")
                         (queue/stop-workers!)
                         (when (features/enabled? :cost-reporting)
                           ((requiring-resolve 'anvil.cost.recorder/stop!)))
                         (when (features/enabled? :gitlab-mr)
                           ((requiring-resolve 'anvil.integration.gitlab-subscriber/stop!)))
                         (when (features/enabled? :bitbucket-pr)
                           ((requiring-resolve 'anvil.integration.bitbucket-subscriber/stop!)))
                         (when (features/enabled? :multi-tenant)
                           ((requiring-resolve 'anvil.tenancy.audit-subscriber/stop!)))
                         (server/stop!)
                         (.countDown latch))))
    (.await latch)
    (log/info "anvil exited cleanly")
    0))

(defn -main
  "Anvil entry point. Bare invocation starts the daemon; subcommands route
   to the CLI dispatcher.

   Declares the `:anvil` product profile via `chengis.product/set-profile!`
   as the first thing it does — chengis-core subsystems gated on profile
   (DB-type fit, capability registry) need this set before they touch
   config or schema. See docs/architecture/anvil-vs-chengis-boundary.md."
  [& args]
  (product/set-profile! :anvil)
  (let [args (vec args)
        first-arg (first args)
        is-daemon-invocation? (or (empty? args)
                                  (= "run" first-arg)
                                  (str/starts-with? (or first-arg "") "--"))]
    (let [exit-code (if is-daemon-invocation?
                      (run-daemon (if (= "run" first-arg) (subvec args 1) args))
                      (cli/dispatch args))]
      (when-not is-daemon-invocation?
        (System/exit (or exit-code 0))))))
