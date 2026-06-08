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
    ;; T3.4 — GitHub Checks subscriber listens to :build-started /
    ;; :build-done on the bus and PATCHes the github check-run state.
    (when (features/enabled? :pr-checks)
      ((requiring-resolve 'anvil.integration.github-subscriber/start!)))
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
