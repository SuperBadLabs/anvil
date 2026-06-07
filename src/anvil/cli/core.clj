(ns anvil.cli.core
  "Top-level CLI router. `anvil <subcommand> [args]` dispatches here.

   Subcommands:
     run                       — start the daemon (defaults to TX2's server)
     import jenkinsfile <path> — import a Jenkinsfile to a Chengisfile (TX7)
     import jenkins-server URL — bulk import a Jenkins instance (TX7 phase 2)
     build <pipeline>          — execute a pipeline locally (TX5/TX9)
     help                      — print top-level usage"
  (:require [clojure.string :as str]
            [anvil.cli.import-jenkinsfile :as import-jf]
            [anvil.cli.secrets :as secrets-cli]
            [anvil.cli.setup-tools :as setup-tools]
            [anvil.cli.ai :as ai-cli]
            [anvil.version :as v]
            [chengis.config :as config]
            [chengis.product :as product]
            [chengis.product.capability :as capability]))

(defn print-top-level-usage []
  (println)
  (println (str (v/version-string) " — drop-in Jenkins replacement"))
  (println)
  (println "Usage: anvil <subcommand> [options]")
  (println)
  (println "Subcommands:")
  (println "  run                          Start the anvil daemon")
  (println "  import jenkinsfile <path>    Convert a Jenkinsfile to a Chengisfile")
  (println "  import jenkins-server <url>  (TX7 phase 2) Bulk import from a Jenkins controller")
  (println "  build <pipeline>             (TX5/TX9) Run a pipeline locally")
  (println "  list-capabilities            Show the capability registry + effective state for this product")
  (println)
  (println "AI authoring (v0.4.1 T3 — requires ANTHROPIC_API_KEY):")
  (println "  init [--out PATH]            Scaffold a Jenkinsfile from the current repo")
  (println "  explain <Jenkinsfile>        Plain-English description of a pipeline")
  (println "  optimize <Jenkinsfile>       Suggest concrete improvements")
  (println)
  (println "  help                         Print this message")
  (println))

(defn- run-list-capabilities []
  ;; Anvil's -main is what normally sets the profile; CLI subcommands
  ;; run before -main may have done so, so set it idempotently here.
  (product/set-profile! :anvil)
  (let [cfg (try (config/load-config) (catch Exception _ {}))]
    (print (capability/format-listing {:cfg cfg :profile :anvil}))
    (flush))
  0)

(defn dispatch
  "Main dispatch — given the full argv (subcommand + rest), route to the
   appropriate command. Returns an exit code."
  [argv]
  (cond
    (empty? argv)
    (do (print-top-level-usage) 0)

    :else
    (let [[cmd & rest] argv]
      (case cmd
        ("help" "-h" "--help")
        (do (print-top-level-usage) 0)

        "import"
        (let [[subcmd & subrest] rest]
          (case subcmd
            "jenkinsfile"     (import-jf/run (vec subrest))
            "jenkins-server"  (do (println "ERROR: jenkins-server import not yet implemented (TX7 phase 2)") 3)
            (do (println (str "ERROR: unknown import subcommand: " subcmd))
                (print-top-level-usage)
                3)))

        "run"
        ;; The daemon entrypoint lives in anvil.core; this branch is here
        ;; for routing-table completeness. Real wiring happens via -main
        ;; in anvil.core which calls into anvil.web.server/start!.
        (do (println "Use `lein run` for the daemon (TX2 scaffolding).") 0)

        "build"
        (do (println "ERROR: build subcommand not yet implemented (TX5/TX9)") 3)

        "list-capabilities"
        (run-list-capabilities)

        "secrets"
        (secrets-cli/run (vec rest))

        "setup"
        (let [[subcmd & subrest] rest]
          (case subcmd
            "tools" (setup-tools/run (vec subrest))
            (do (println "ERROR: unknown setup subcommand: " subcmd) 3)))

        ;; v0.4.1 T3 — AI authoring commands.  Top-level per the board
        ;; (anvil init / explain / optimize), not nested under `anvil ai`.
        "init"     (ai-cli/run-init     (vec rest))
        "explain"  (ai-cli/run-explain  (vec rest))
        "optimize" (ai-cli/run-optimize (vec rest))

        (do (println (str "ERROR: unknown subcommand: " cmd))
            (print-top-level-usage)
            3)))))
