(ns anvil.cli.ai
  "v0.4.1 T3.3 — CLI commands for AI-assisted Jenkinsfile authoring.

   Three subcommands per the v0.4 board:

     anvil init [--out PATH] [--model MODEL] [--no-stream]
         Scaffold a Jenkinsfile from the current directory's
         repo-context (detected build tool + languages + existing CI).

     anvil explain <Jenkinsfile-path> [--model MODEL] [--no-stream]
         Plain-English description of what the pipeline does.

     anvil optimize <Jenkinsfile-path> [--model MODEL] [--no-stream]
         Suggest concrete improvements (parallelism, container-step,
         caching, etc).

   AV4-4 + R3 contract:
     - Calls Anthropic's Messages API directly via ANTHROPIC_API_KEY
       from env (operator's key, operator's bill).
     - `init` sends the repo-context summary (file extension counts,
       build-tool names, existing CI configs) — never the file
       contents at large.
     - `explain` / `optimize` send the Jenkinsfile content the
       operator named explicitly + a short system prompt.  No other
       workspace files leave the host.

   Streaming default: every command streams tokens to stdout as they
   arrive (long Jenkinsfile explanations are otherwise a long pause).
   `--no-stream` buffers for callers that want a single chunk."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [clojure.java.io :as io]
            [anvil.ai.client :as ai]
            [anvil.ai.repo-context :as repo-ctx]))

;; ---------------------------------------------------------------------------
;; Shared option parser
;; ---------------------------------------------------------------------------

(def ^:private common-opts
  [[nil  "--model MODEL"  "Anthropic model id"
    :default ai/default-model]
   [nil  "--no-stream"    "Buffer the full response instead of streaming tokens"
    :default false]
   ["-h" "--help"]])

(defn- run-call!
  "Drive a Messages API call.  In stream mode, prints tokens to stdout
   as they arrive and returns {:stop-reason :usage}.  In buffered
   mode, prints the full text once and returns the same shape."
  [{:keys [no-stream model system user-text]}]
  (let [opts {:model model
              :system system
              :messages [{:role "user" :content user-text}]}]
    (if no-stream
      (let [resp (ai/messages opts)
            text (ai/extract-text resp)]
        (println text)
        (flush)
        {:stop-reason (:stop-reason resp) :usage (:usage resp)})
      (let [accum (volatile! {:stop-reason nil :usage {}})]
        (doseq [{:keys [event data]} (ai/messages-stream opts)]
          (case event
            "content_block_delta"
            (when-let [txt (get-in data [:delta :text])]
              (print txt)
              (flush))

            "message_delta"
            (vswap! accum (fn [a]
                            (-> a
                                (assoc :stop-reason (get-in data [:delta :stop_reason]))
                                (update :usage merge
                                        {:output-tokens (get-in data [:usage :output_tokens])}))))

            "message_start"
            (vswap! accum update :usage merge
                    {:input-tokens (get-in data [:message :usage :input_tokens])})

            nil))
        (println)
        (flush)
        @accum))))

(defn- print-footer
  "After streaming, surface stop-reason + token usage on stderr.  Goes
   to stderr (not stdout) so operators can `> Jenkinsfile` the command
   output cleanly without the footer landing in the file."
  [{:keys [stop-reason usage]}]
  (binding [*out* *err*]
    (println)
    (case stop-reason
      "end_turn"     (println "✓ Done.")
      "max_tokens"   (println "⚠ Hit max-tokens — response may be truncated.  Retry with a focused prompt.")
      "refusal"      (println "⚠ Model refused to respond.  Check Console for refusal details.")
      (println (str "Done.  stop_reason=" (pr-str stop-reason))))
    (when (seq usage)
      (println (format "  tokens: in=%s  out=%s"
                       (or (:input-tokens usage) "?")
                       (or (:output-tokens usage) "?"))))
    (flush)))

(defn- run-with-error-trapping
  "Wrap an API call with operator-friendly error handling.  Returns an
   exit code suitable for the CLI dispatcher."
  [f]
  (try
    (let [{:keys [stop-reason] :as result} (f)]
      (print-footer result)
      (if (= stop-reason "refusal") 4 0))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (str "ERROR: " (ex-message e)))
        (when-let [hint (:hint (ex-data e))]
          (println (str "  hint: " hint)))
        (when-let [fix (:fix (ex-data e))]
          (println (str "  fix: " fix))))
      2)
    (catch Throwable e
      (binding [*out* *err*]
        (println (str "ERROR: " (.getMessage e))))
      2)))

;; ---------------------------------------------------------------------------
;; anvil init — scaffold from repo-context
;; ---------------------------------------------------------------------------

(def ^:private init-system-prompt
  (str "You are a senior CI engineer who writes idiomatic Jenkinsfile pipelines.\n"
       "Given a repo-context summary, produce a single Jenkinsfile that:\n"
       "  - Uses the declarative `pipeline { agent any; stages { ... } }` syntax\n"
       "  - Includes the canonical build → test → package stages for the\n"
       "    detected build tool (Maven `mvn -B verify`, Gradle `./gradlew build`,\n"
       "    npm `npm ci && npm test`, etc.)\n"
       "  - Adds a `junit` step pointing at the build tool's surefire output\n"
       "  - Uses `parallel { … }` when the test stage would otherwise be slow\n"
       "  - Stays minimal — no plugins, no shared libraries, no agents-by-label;\n"
       "    those are the operator's call to add later\n"
       "\n"
       "Output the raw Jenkinsfile only.  No prose, no code fences, no preamble."))

(defn- cmd-init [argv]
  (let [opts (concat common-opts
                     [[nil "--out PATH" "Where to write the scaffold (default: Jenkinsfile in cwd)"
                       :default "Jenkinsfile"]
                      [nil "--force" "Overwrite an existing Jenkinsfile"
                       :default false]
                      [nil "--print" "Print to stdout instead of writing to a file"
                       :default false]])
        {:keys [options summary errors]} (tools-cli/parse-opts argv opts)]
    (cond
      errors
      (do (binding [*out* *err*]
            (println "ERROR: " (str/join "; " errors))
            (println summary))
          2)

      (:help options)
      (do (println "Usage: anvil init [options]")
          (println summary)
          0)

      :else
      (let [ctx (repo-ctx/scan ".")
            primary (repo-ctx/primary-language ctx)
            summary-text (repo-ctx/summary-string ctx)
            out-path (:out options)
            user-text (str "Scaffold a Jenkinsfile for this repository.\n\n"
                           "Repo context:\n\n"
                           summary-text
                           (when primary (str "\n\nPrimary language: " primary)))]
        (binding [*out* *err*]
          (println (format "anvil init: calling %s (model=%s)..."
                           (if (:no-stream options) "Anthropic API" "Anthropic API [streaming]")
                           (:model options)))
          (when-not (or (:print options) (:force options))
            (when (.exists (io/file out-path))
              (println (str "ERROR: " out-path " already exists.  Use --force to overwrite, --print to send to stdout."))
              (System/exit 3))))
        (if (:print options)
          (run-with-error-trapping
           #(run-call! (merge options
                              {:no-stream (:no-stream options)
                               :model (:model options)
                               :system init-system-prompt
                               :user-text user-text})))
          ;; Write-to-file path: always buffer so we get the full
          ;; Jenkinsfile in one shot (a stream that drops mid-render
          ;; would leave a half-written file on disk).
          (run-with-error-trapping
           (fn []
             (let [resp (ai/messages {:model (:model options)
                                      :system init-system-prompt
                                      :messages [{:role "user" :content user-text}]})
                   text (ai/extract-text resp)]
               (spit out-path text)
               (binding [*out* *err*]
                 (println (str "✓ Wrote " out-path " (" (count text) " bytes)")))
               {:stop-reason (:stop-reason resp)
                :usage       (:usage resp)}))))))))

;; ---------------------------------------------------------------------------
;; anvil explain
;; ---------------------------------------------------------------------------

(def ^:private explain-system-prompt
  (str "You are a senior CI engineer explaining a Jenkinsfile to someone\n"
       "who knows software but doesn't know Jenkins.\n\n"
       "Given the Jenkinsfile content, produce a plain-English description\n"
       "in this exact shape:\n\n"
       "1. **What this pipeline does** — one sentence.\n"
       "2. **Stages** — bulleted list, one line per stage, naming what runs.\n"
       "3. **Triggers** — when does this build run (commits, tags, schedule)?\n"
       "4. **Outputs** — what artifacts / reports does it produce?\n"
       "5. **Gotchas** — any plugin dependencies, credential requirements,\n"
       "   or shared-library imports the operator must set up.\n\n"
       "Be concise.  No code fences.  No restating the Jenkinsfile.\n"
       "If the file isn't a Jenkinsfile, say so and stop."))

(defn- cmd-explain [argv]
  (let [{:keys [arguments options summary errors]}
        (tools-cli/parse-opts argv common-opts)]
    (cond
      errors
      (do (binding [*out* *err*]
            (println "ERROR: " (str/join "; " errors))
            (println summary))
          2)

      (:help options)
      (do (println "Usage: anvil explain <Jenkinsfile-path> [options]")
          (println summary)
          0)

      (empty? arguments)
      (do (binding [*out* *err*]
            (println "ERROR: anvil explain needs a path to a Jenkinsfile")
            (println "Usage: anvil explain <Jenkinsfile-path> [options]")
            (println summary))
          2)

      :else
      (let [path (first arguments)
            f    (io/file path)]
        (if-not (.exists f)
          (do (binding [*out* *err*]
                (println (str "ERROR: not found: " path)))
              2)
          (let [content (slurp f)
                user-text (str "Explain this Jenkinsfile:\n\n" content)]
            (binding [*out* *err*]
              (println (format "anvil explain: %s (model=%s, %d bytes)..."
                               path (:model options) (count content))))
            (run-with-error-trapping
             #(run-call! (merge options
                                {:no-stream (:no-stream options)
                                 :model (:model options)
                                 :system explain-system-prompt
                                 :user-text user-text})))))))))

;; ---------------------------------------------------------------------------
;; anvil optimize
;; ---------------------------------------------------------------------------

(def ^:private optimize-system-prompt
  (str "You are a senior CI engineer reviewing a Jenkinsfile for improvements.\n\n"
       "Given the Jenkinsfile content, propose concrete improvements focused on:\n"
       "  - Parallelism — sibling stages that don't depend on each other\n"
       "  - Caching — Maven/Gradle/npm caches that aren't yet declared\n"
       "  - Container-step — replace agent labels with `container('image') { … }`\n"
       "    where the image is well-known\n"
       "  - Retry blocks around known-flaky operations (network, downloads)\n"
       "  - Junit/artifact reporting that's missing\n\n"
       "Output format — markdown, no code fences around the whole reply:\n\n"
       "## Suggestion 1: <short title>\n"
       "**Why:** one sentence.\n"
       "**Change:** show the before → after as a unified diff (use ```diff blocks).\n\n"
       "Limit yourself to the top 3 most impactful suggestions.  If the file\n"
       "is already well-optimized, say so and propose just 1 nit, or zero.\n"
       "Don't suggest changes you can't justify with a concrete win."))

(defn- cmd-optimize [argv]
  (let [{:keys [arguments options summary errors]}
        (tools-cli/parse-opts argv common-opts)]
    (cond
      errors
      (do (binding [*out* *err*]
            (println "ERROR: " (str/join "; " errors))
            (println summary))
          2)

      (:help options)
      (do (println "Usage: anvil optimize <Jenkinsfile-path> [options]")
          (println summary)
          0)

      (empty? arguments)
      (do (binding [*out* *err*]
            (println "ERROR: anvil optimize needs a path to a Jenkinsfile")
            (println "Usage: anvil optimize <Jenkinsfile-path> [options]")
            (println summary))
          2)

      :else
      (let [path (first arguments)
            f    (io/file path)]
        (if-not (.exists f)
          (do (binding [*out* *err*]
                (println (str "ERROR: not found: " path)))
              2)
          (let [content (slurp f)
                user-text (str "Review this Jenkinsfile for improvements:\n\n" content)]
            (binding [*out* *err*]
              (println (format "anvil optimize: %s (model=%s, %d bytes)..."
                               path (:model options) (count content))))
            (run-with-error-trapping
             #(run-call! (merge options
                                {:no-stream (:no-stream options)
                                 :model (:model options)
                                 :system optimize-system-prompt
                                 :user-text user-text})))))))))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn run-init [argv] (cmd-init argv))
(defn run-explain [argv] (cmd-explain argv))
(defn run-optimize [argv] (cmd-optimize argv))
