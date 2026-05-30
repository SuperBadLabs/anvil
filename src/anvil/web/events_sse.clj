(ns anvil.web.events-sse
  "GET /anvil/events — the SSE endpoint that bridges anvil.events.bus
   to browser EventSource clients (TU1.3).

   Composes two pieces from the foundation:

     - anvil.web.sse           : the wire-format + http-kit plumbing
     - anvil.events.bus        : the in-process pub/sub

   This namespace IS the integration: open an SSE channel, subscribe
   to the requested topics on the bus, push every received event over
   the wire as a `data:` frame whose `event:` line is the bus event's
   `:type`, so htmx-sse's `sse-swap='build-started build-done …'`
   selector works out of the box.

   Topic selection:

     ?topics=global                          ; default — every event
     ?topics=job:foo                         ; one job's lifecycle
     ?topics=job:foo,job:bar,queue           ; multiple, comma-sep

   Topic encoding on the wire is `name` / `namespace:name` / nothing
   else — keeps URLs readable without URL-encoding. The bus uses
   richer values internally (e.g. `[:job \"foo\"]`); this fn maps the
   short string form to the canonical bus topic.

   Lifecycle:

     open  → 1× tap event (so the client knows it's wired up)
           → subscribe! on the bus for each requested topic
     event → for every bus event, send! over the SSE channel
     close → unsubscribe! every token; close the http-kit channel
             (the heartbeat thread in anvil.web.sse also exits)"
  (:require [clojure.string :as str]
            [anvil.events.bus :as bus]
            [anvil.web.sse :as sse]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Topic encoding
;;
;; The bus accepts arbitrary values as topics; the wire only sees
;; strings. Mapping rules:
;;
;;   "global"       → :global
;;   "queue"        → :queue
;;   "job:NAME"     → [:job NAME]
;;   "build:JOB:N"  → [:build JOB (Long/parseLong N)]
;;
;; Unknown forms are rejected with a 400 — fail loud rather than
;; silently subscribing to a topic nobody ever publishes on.
;; ---------------------------------------------------------------------------

(defn- decode-topic
  "Decode one wire-format topic string to a bus topic value.
   Returns nil for unrecognized shapes."
  [s]
  (let [s (str/trim s)]
    (cond
      (= s "global") :global
      (= s "queue")  :queue
      (str/starts-with? s "job:")
      (let [job (subs s 4)]
        (when (seq job) [:job job]))
      (str/starts-with? s "build:")
      (let [parts (str/split (subs s 6) #":" 2)]
        (when (and (= 2 (count parts)) (seq (first parts)))
          (try [:build (first parts) (Long/parseLong (second parts))]
               (catch Exception _ nil)))))))

(defn parse-topics
  "Parse the comma-separated ?topics= query value into a vector of
   bus topic values. Returns:
     {:ok? true  :topics [...]}        — every entry decoded
     {:ok? false :error str :bad str}  — first bad entry name returned"
  [s]
  (let [s (or s "global")
        raw (->> (str/split s #",") (map str/trim) (remove str/blank?))]
    (if (empty? raw)
      {:ok? true :topics [:global]}
      (loop [in raw, out []]
        (if-let [head (first in)]
          (if-let [t (decode-topic head)]
            (recur (rest in) (conj out t))
            {:ok? false :bad head
             :error (str "unrecognized topic '" head "'. "
                         "expected one of: global, queue, job:NAME, build:JOB:N")})
          {:ok? true :topics out})))))

;; ---------------------------------------------------------------------------
;; Handler
;; ---------------------------------------------------------------------------

;; The SSE handler's per-connection heartbeat. Plain atom (not a
;; dynamic var) so it's visible from http-kit's worker threads, which
;; don't inherit caller bindings. Tests override; production stays
;; at sse/default-heartbeat-interval-ms.
(defonce ^:private heartbeat-override (atom nil))

(defn set-heartbeat-override!
  "Test seam. Pass nil to reset to the default (15s)."
  [ms]
  (reset! heartbeat-override ms))

(defn handler
  "Ring/http-kit handler for GET /anvil/events. The response is
   asynchronous; this fn returns immediately after handing the channel
   to http-kit."
  [req]
  (let [topics-raw (get-in req [:query-params "topics"])
        parsed (parse-topics topics-raw)]
    (if-not (:ok? parsed)
      {:status 400
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (:error parsed)}
      (let [topics (:topics parsed)
            ;; subscription tokens are collected so on-close can scrub.
            tokens (atom [])
            heartbeat-ms (or @heartbeat-override
                             sse/default-heartbeat-interval-ms)]
        (sse/open!
         req
         {:heartbeat-ms heartbeat-ms
          :on-open
          (fn [ch]
            ;; Send a hello frame so the browser's EventSource.onopen
            ;; fires with a real event rather than waiting for the
            ;; first bus publish.
            (sse/send! ch {:event-type "hello"
                           :data {:topics (mapv pr-str topics)}})
            ;; One subscription per requested topic. Each subscriber's
            ;; fn just relays to the open channel.
            (doseq [topic topics]
              (let [token
                    (bus/subscribe!
                     topic
                     (fn [event]
                       ;; SSE event-type comes from the bus event's
                       ;; :type so htmx-sse can demux via
                       ;; sse-swap='build-started build-done …'.
                       ;; On write failure (remote has closed),
                       ;; return ::bus/unsubscribe so the bus drops
                       ;; us. We don't rely on http-kit's :on-close
                       ;; because NIO doesn't surface client FIN
                       ;; until the next write attempt anyway.
                       (if (sse/send! ch
                                      {:event-type (some-> (:type event) name)
                                       :data (-> event
                                                 (update :ts str)
                                                 (update :topic pr-str))})
                         nil
                         (do (sse/close! ch)
                             :anvil.events.bus/unsubscribe))))]
                (swap! tokens conj token)))
            (log/debug "SSE client connected, topics=" topics))
          :on-close
          (fn []
            (doseq [t @tokens] (bus/unsubscribe! t))
            (log/debug "SSE client disconnected, "
                       (count @tokens) "subscription(s) released"))})))))
