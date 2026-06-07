(ns anvil.ai.client
  "v0.4.1 T3.1 — Anthropic Messages API HTTP client for anvil's AI
   authoring features (`anvil init`, `anvil explain`, `anvil optimize`).

   AV4-4 locked decision: local-first.  No hosted anvil service, no
   proxied calls.  Reads `ANTHROPIC_API_KEY` from env; the operator
   owns the key and the bill.  R3 contract: only the artifact content
   the operator hands us (Jenkinsfile text, repo-context map) goes on
   the wire — never workspace files at large.

   Implementation: java.net.http.HttpClient.  Built into JDK 21, no
   new dep needed.  Streaming uses the SSE response format
   (`text/event-stream`) parsed line-by-line via a BufferedReader.

   Design notes:
     - Model defaults to `claude-sonnet-4-6` (the v0.4 board's
       'fast + good for code' lane, updated from the now-stale
       claude-sonnet-4-5 the board originally named).  Operators
       can override via :anvil.ai/model in anvil.edn or per-call.
     - `temperature` / `top_p` / `top_k` are NOT sent — newer
       Claude versions (Opus 4.7+) return 400 if any of them appear,
       and steering via prompt is the recommended path.
     - `thinking` is left off by default — these are short, focused
       code-gen calls.  Operators with budget can flip on adaptive
       thinking via :anvil.ai/thinking? but it's not the default.
     - The `:stop_reason` is returned verbatim so callers can
       distinguish `end_turn` (normal), `max_tokens` (truncated),
       `refusal` (Claude declined for safety reasons)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient
                          HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.io BufferedReader InputStreamReader)
           (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def ^:private api-base
  "https://api.anthropic.com/v1")

(def ^:private anthropic-version
  "Bumped only when an actual breaking change ships and we audit the
   migration guide.  See shared/model-migration.md."
  "2023-06-01")

(def default-model
  "Sonnet 4.6: best speed + intelligence balance for interactive CLI
   code-gen.  Per the v0.4 board's AV4-4 lane (was claude-sonnet-4-5
   when the board was written; 4-5 retired, 4-6 is the live default)."
  "claude-sonnet-4-6")

(def ^:private default-max-tokens
  "Generous default — Jenkinsfile explanations and scaffolds can run
   long.  Operators with cost budget can lower this per-call."
  4096)

(defn- read-api-key []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (throw (ex-info "ANTHROPIC_API_KEY not set"
                      {:fix "Export ANTHROPIC_API_KEY in your shell or anvil's service env"
                       :docs "https://platform.claude.com/docs/en/api/getting-started"}))))

;; ---------------------------------------------------------------------------
;; HTTP client (process-lifetime singleton)
;; ---------------------------------------------------------------------------

(defonce ^:private http-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- build-request
  "Build the immutable HttpRequest for `POST /v1/messages`."
  [{:keys [api-key body-json timeout-s stream?]
    :or {timeout-s 600}}]
  (let [b (-> (HttpRequest/newBuilder)
              (.uri (URI/create (str api-base "/messages")))
              (.timeout (Duration/ofSeconds timeout-s))
              (.header "x-api-key" api-key)
              (.header "anthropic-version" anthropic-version)
              (.header "content-type" "application/json"))]
    (when stream?
      ;; SSE response; the API switches on the request's `stream` field
      ;; (we set it in the body), so this header is just clarity for
      ;; intermediaries.
      (.header b "accept" "text/event-stream"))
    (-> b
        (.POST (HttpRequest$BodyPublishers/ofString
                ^String body-json StandardCharsets/UTF_8))
        (.build))))

(defn- build-body
  "Compose the Messages API request body.  `opts` keys consumed:
     :model           model id (default `default-model`)
     :max-tokens      response cap (default `default-max-tokens`)
     :system          system prompt string (optional)
     :messages        REQUIRED — vec of {:role :content}
     :stream?         if truthy, body sets `stream: true`
     :thinking?       if truthy, sends `thinking: {type: 'adaptive'}`"
  [{:keys [model max-tokens system messages stream? thinking?]
    :or {model default-model
         max-tokens default-max-tokens}}]
  (when-not (and (sequential? messages) (seq messages))
    (throw (ex-info ":messages must be a non-empty sequence"
                    {:messages messages})))
  (let [body (cond-> {:model model
                      :max_tokens max-tokens
                      :messages (vec messages)}
               system     (assoc :system system)
               stream?    (assoc :stream true)
               thinking?  (assoc :thinking {:type "adaptive"}))]
    (json/write-str body)))

;; ---------------------------------------------------------------------------
;; Non-streaming call — used for short responses or when the caller
;; wants the whole thing buffered.
;; ---------------------------------------------------------------------------

(defn messages
  "POST a Messages API request and return the parsed response map.

   Returns:
     {:content [...]            ; vec of content blocks, each {:type :text :text \"…\"}
      :stop-reason \"end_turn\" ; or \"max_tokens\", \"refusal\", \"tool_use\", \"pause_turn\"
      :usage {:input-tokens N :output-tokens M …}
      :model \"…\"
      :id    \"msg_…\"}

   Throws ex-info on non-2xx with {:status :body} for the caller to inspect."
  [opts]
  (let [api-key  (or (:api-key opts) (read-api-key))
        body     (build-body opts)
        req      (build-request {:api-key api-key :body-json body})
        resp     (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode resp)
        body-str (.body resp)]
    (if (<= 200 status 299)
      (let [m (json/read-str body-str :key-fn keyword)]
        {:content     (:content m)
         :stop-reason (:stop_reason m)
         :usage       {:input-tokens         (get-in m [:usage :input_tokens])
                       :output-tokens        (get-in m [:usage :output_tokens])
                       :cache-read-tokens    (get-in m [:usage :cache_read_input_tokens])
                       :cache-creation-tokens (get-in m [:usage :cache_creation_input_tokens])}
         :model       (:model m)
         :id          (:id m)})
      (throw (ex-info (str "Anthropic API returned HTTP " status)
                      {:status status
                       :body   body-str
                       ;; Common ones to flag for operators
                       :hint   (cond
                                 (= 401 status) "API key missing or invalid"
                                 (= 429 status) "Rate limited — wait and retry"
                                 (= 529 status) "API overloaded — wait and retry"
                                 (<= 500 status 599) "API error — wait and retry"
                                 :else nil)})))))

(defn extract-text
  "Pull the concatenated text from a `messages` response's content
   blocks.  Skips non-text blocks (thinking, tool_use, etc.) — for
   T3.1's use case (Jenkinsfile generation/explanation) we only care
   about the text."
  [{:keys [content]}]
  (->> content
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "")))

;; ---------------------------------------------------------------------------
;; Streaming — for CLI commands that want to print tokens as they
;; arrive.  We parse the SSE wire format inline because the events we
;; care about (text deltas, stop reason, usage) are a small subset and
;; bringing in an SSE-parsing dep would be overkill.
;;
;; SSE shape (anthropic):
;;   event: <event-name>
;;   data: <json>
;;
;;   <blank>
;;
;; Event names we care about: content_block_delta (text chunks),
;; message_delta (carries stop_reason + final usage), message_stop.
;; ---------------------------------------------------------------------------

(defn- parse-sse-line
  "Parse one line from the SSE stream.  Returns {:type :event :name X}
   for an `event: X` line, {:type :data :json Y} for a `data: Y` line,
   or nil for blank lines / unknown."
  [line]
  (cond
    (str/blank? line) nil
    (str/starts-with? line "event: ") {:type :event :name (subs line 7)}
    (str/starts-with? line "data: ")  {:type :data :raw (subs line 6)}
    :else nil))

(defn- sse-event-seq
  "Lazy sequence of {:event :data} maps from a BufferedReader of the
   SSE response body.  Each emitted item pairs the most recent
   `event:` line with its `data:` payload (json-parsed).  Stops when
   the reader is exhausted or a `message_stop` event arrives."
  [^BufferedReader rdr]
  (let [step (fn step [pending-event]
               (lazy-seq
                (if-let [line (.readLine rdr)]
                  (if-let [{:keys [type] :as parsed} (parse-sse-line line)]
                    (case type
                      :event (step (:name parsed))
                      :data  (let [data (try (json/read-str (:raw parsed) :key-fn keyword)
                                             (catch Exception _ nil))]
                               (cons {:event pending-event :data data}
                                     (if (= pending-event "message_stop")
                                       nil
                                       (step nil)))))
                    (step pending-event))
                  nil)))]
    (step nil)))

(defn messages-stream
  "Like `messages` but returns a lazy seq of stream events as
   {:event <name> :data <parsed-json-map>}.

   The caller is responsible for consuming the seq (which closes the
   HTTP response when exhausted).  For CLI use the common pattern is:

     (doseq [{:keys [event data]} (messages-stream {…})]
       (when (= event \"content_block_delta\")
         (when-let [txt (get-in data [:delta :text])]
           (print txt)
           (flush))))

   The terminal event is `message_stop`; the `message_delta` event
   preceding it carries the final `stop_reason` and accumulated
   `usage`."
  [opts]
  (let [api-key (or (:api-key opts) (read-api-key))
        body    (build-body (assoc opts :stream? true))
        req     (build-request {:api-key api-key
                                :body-json body
                                :stream? true})
        resp    (.send http-client req (HttpResponse$BodyHandlers/ofInputStream))
        status  (.statusCode resp)
        is      (.body resp)]
    (if (<= 200 status 299)
      (let [rdr (BufferedReader. (InputStreamReader. is StandardCharsets/UTF_8))]
        (sse-event-seq rdr))
      (let [body-str (slurp is)]
        (.close is)
        (throw (ex-info (str "Anthropic API streaming returned HTTP " status)
                        {:status status :body body-str}))))))

(defn collect-stream-text
  "Walk a `messages-stream` seq and return
     {:text <concatenated text>
      :stop-reason <final stop_reason>
      :usage {:input-tokens, :output-tokens}}.

   Useful when you want streaming for progress UX but also need the
   complete result at the end (CLI commands that print as they go
   then write the artifact to disk afterward)."
  [event-seq]
  (let [acc (atom {:text-buf (StringBuilder.)
                   :stop-reason nil
                   :usage {}})]
    (doseq [{:keys [event data]} event-seq]
      (case event
        "content_block_delta"
        (when-let [txt (get-in data [:delta :text])]
          (.append ^StringBuilder (:text-buf @acc) ^String txt))

        "message_delta"
        (swap! acc (fn [s]
                     (-> s
                         (assoc :stop-reason (get-in data [:delta :stop_reason]))
                         (update :usage merge
                                 {:output-tokens (get-in data [:usage :output_tokens])}))))

        "message_start"
        (swap! acc update :usage merge
               {:input-tokens (get-in data [:message :usage :input_tokens])
                :cache-read-tokens (get-in data [:message :usage :cache_read_input_tokens])
                :cache-creation-tokens (get-in data [:message :usage :cache_creation_input_tokens])})

        ;; ignore the others (content_block_start/stop, message_stop, ping)
        nil))
    {:text (str (:text-buf @acc))
     :stop-reason (:stop-reason @acc)
     :usage (:usage @acc)}))
