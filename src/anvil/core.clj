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

(defn- run-daemon [args]
  (let [port (parse-port args)
        worker-count (or (some-> (System/getenv "ANVIL_WORKERS") Integer/parseInt) 2)
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
