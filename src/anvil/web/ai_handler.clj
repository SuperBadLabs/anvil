(ns anvil.web.ai-handler
  "v0.4.1 T3.4 + T3.5 — HTTP handlers for the AI 'Explain'/'Optimize'
   buttons on `/jobs/<j>`.

   Wire shape:
     POST /jobs/:name/ai/explain   →  HTML fragment for the modal
     POST /jobs/:name/ai/optimize  →  HTML fragment for the modal
                                       + publishes :ai-suggested on
                                         the bus (T3.4)

   Both handlers:
     - Reject 404 when the :ai-authoring flag is off (wrap-feature)
     - Look up the job; 404 when the job doesn't exist
     - Read the job's :jenkinsfile-source and feed it to anvil.ai.client
     - Render the response (or error) into the modal target.

   AV4-4 + R3 carry over from PR-2: ANTHROPIC_API_KEY from env, only
   the Jenkinsfile content goes on the wire."
  (:require [hiccup2.core :as h2]
            [anvil.ai.client :as ai]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.views.ai-modal :as ai-modal]))

;; ---------------------------------------------------------------------------
;; System prompts — same as the CLI's, kept inline so the UI surface
;; doesn't depend on the CLI ns.
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

(def ^:private optimize-system-prompt
  (str "You are a senior CI engineer reviewing a Jenkinsfile for improvements.\n\n"
       "Given the Jenkinsfile content, propose concrete improvements focused on:\n"
       "  - Parallelism — sibling stages that don't depend on each other\n"
       "  - Caching — Maven/Gradle/npm caches that aren't yet declared\n"
       "  - Container-step — replace agent labels with `container('image') { … }`\n"
       "  - Retry blocks around known-flaky operations (network, downloads)\n"
       "  - Junit/artifact reporting that's missing\n\n"
       "Output format — markdown:\n\n"
       "## Suggestion 1: <short title>\n"
       "**Why:** one sentence.\n"
       "**Change:** show the before → after as a unified diff.\n\n"
       "Limit yourself to the top 3 most impactful suggestions.  If the file\n"
       "is already well-optimized, say so and propose just 1 nit, or zero."))

;; ---------------------------------------------------------------------------
;; Render helpers
;; ---------------------------------------------------------------------------

(defn- html-response
  "Wrap a hiccup fragment in a 200 text/html response — the modal
   target is what htmx swaps into innerHTML."
  [hiccup]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str (h2/html hiccup))})

(defn- not-found-fragment [job-name]
  (ai-modal/error-fragment
   {:message (str "Job '" job-name "' not found")
    :hint    "The job may have been deleted or renamed since the page loaded."}))

(defn- no-jenkinsfile-fragment [job-name]
  (ai-modal/error-fragment
   {:message (str "Job '" job-name "' has no jenkinsfile-source on file.")
    :hint    "This usually means the job was registered without a Jenkinsfile body."
    :fix     "Trigger a build (or re-register the job) before calling Explain/Optimize."}))

(defn- run-ai-call!
  "Execute a buffered Messages API call and render the response into
   the modal target.  All Anthropic errors land here as ex-info and
   are surfaced via ai-modal/error-fragment.  Returns a Ring response."
  [{:keys [system-prompt jenkinsfile-source kind]}]
  (try
    (let [resp (ai/messages {:system system-prompt
                             :messages [{:role "user"
                                         :content (str (case kind
                                                         :explain "Explain this Jenkinsfile:\n\n"
                                                         :optimize "Review this Jenkinsfile for improvements:\n\n"
                                                         "")
                                                       jenkinsfile-source)}]})
          text (ai/extract-text resp)]
      {:response (html-response
                  (ai-modal/response-fragment
                   {:text text
                    :stop-reason (:stop-reason resp)
                    :usage (:usage resp)
                    :kind kind}))
       :text text
       :stop-reason (:stop-reason resp)})
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        {:response (html-response
                    (ai-modal/error-fragment
                     {:message (ex-message e)
                      :hint    (:hint d)
                      :fix     (:fix d)}))
         :error true}))
    (catch Throwable e
      {:response (html-response
                  (ai-modal/error-fragment
                   {:message (.getMessage e)}))
       :error true})))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn explain-handler
  "POST /jobs/:name/ai/explain — buffered explain call → modal HTML."
  [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)]
    (cond
      (nil? job)
      (html-response (not-found-fragment job-name))

      (or (nil? (:jenkinsfile-source job))
          (clojure.string/blank? (:jenkinsfile-source job)))
      (html-response (no-jenkinsfile-fragment job-name))

      :else
      (:response (run-ai-call! {:system-prompt explain-system-prompt
                                :jenkinsfile-source (:jenkinsfile-source job)
                                :kind :explain})))))

(defn optimize-handler
  "POST /jobs/:name/ai/optimize — buffered optimize call → modal HTML.
   Additionally publishes :ai-suggested on the bus (T3.4) so any
   subscribed UI fragments (e.g. a per-job 'last optimization' card
   in a future iteration) can swap live."
  [req]
  (let [job-name (get-in req [:path-params :name])
        job (jobs/find-job job-name)]
    (cond
      (nil? job)
      (html-response (not-found-fragment job-name))

      (or (nil? (:jenkinsfile-source job))
          (clojure.string/blank? (:jenkinsfile-source job)))
      (html-response (no-jenkinsfile-fragment job-name))

      :else
      (let [{:keys [response text stop-reason error]}
            (run-ai-call! {:system-prompt optimize-system-prompt
                           :jenkinsfile-source (:jenkinsfile-source job)
                           :kind :optimize})]
        ;; T3.4 — publish :ai-suggested on success so live subscribers
        ;; see the optimization landed.  Skip on errors; an error
        ;; doesn't count as a suggestion.
        (when (and (not error) (= stop-reason "end_turn"))
          (try
            (bus/publish! (topics/topic-job job-name)
                          {:type topics/evt-ai-suggested
                           :job-name job-name
                           :build-id nil ; T3.5 surface; per-build context lands when
                                         ; we wire this into the build page (future)
                           :suggestions [{:text text}]})
            (catch Throwable _ nil))) ; bus failures don't break the UX
        response))))
