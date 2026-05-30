(ns anvil.web.sse
  "Server-Sent Events helper for anvil — the real-time transport for
   TU1+ UI features.

   Why SSE and not WebSocket: anvil's UI never needs the browser to
   stream bytes the *other* direction. Forms POST via plain htmx swaps;
   the server pushes deltas one way. SSE is in every browser since
   2012, auto-reconnects with a `retry:` directive, survives most
   reverse proxies, and has zero framing overhead beyond `data: …\\n\\n`.

   Decision AU2 (see docs/anvil-ui/decisions.md).

   Architecture:

     producer ──► event-bus ──► [open-channels topic→#{ch}]
                                       │
                                       ▼ broadcast!
                                  http-kit channels held open
                                  for browser EventSource clients

   The bus lives in anvil.events.bus (built in TU1.1); this namespace
   just provides the SSE-over-http-kit primitive that bus subscribers
   use to push to a browser.

   Format:
     data: <json-encoded payload>\\n
     event: <optional event name>\\n
     id:    <optional event id>\\n
     \\n

   Heartbeat:
     : <comment>\\n\\n         every 15s, to keep nginx/cloudflare from
                              dropping the connection as idle.

   Tests: anvil.web.sse-test."
  (:require [clojure.data.json :as json]
            [org.httpkit.server :as hk]
            [taoensso.timbre :as log]))

(def ^:private heartbeat-interval-ms
  "Send a `:\\n\\n` comment line every 15s. Standard EventSource
   semantics — clients ignore comment lines, but proxies see traffic
   and don't close the connection as idle. 15s is conservative
   (most proxies idle out at 60s+); plenty of headroom."
  15000)

;; ---------------------------------------------------------------------------
;; Wire format
;; ---------------------------------------------------------------------------

(defn- format-event
  "Format a single SSE frame. `event-type` is the EventSource event
   name; `data` is the payload (will be JSON-encoded if not a string).
   `id` is optional; if present the client echoes it back as
   `Last-Event-ID` on reconnect — we don't use that yet."
  [{:keys [event-type data id]}]
  (let [payload (if (string? data) data (json/write-str data))]
    (str (when id        (str "id: "    id         "\n"))
         (when event-type (str "event: " event-type "\n"))
         "data: " payload "\n\n")))

(defn- heartbeat-frame []
  ":\n\n")

;; ---------------------------------------------------------------------------
;; Public surface
;; ---------------------------------------------------------------------------

(defn sse-headers
  "Standard SSE response headers. Disabling buffering matters under
   nginx (X-Accel-Buffering: no) — otherwise nginx holds frames in
   its buffer waiting for a 'reasonable' chunk and the browser sees
   nothing for seconds."
  []
  {"Content-Type"      "text/event-stream; charset=utf-8"
   "Cache-Control"     "no-cache, no-transform"
   "Connection"        "keep-alive"
   "X-Accel-Buffering" "no"})

(defn send!
  "Send a single SSE frame to an open channel.
   Returns true if the send succeeded, false if the channel was closed."
  [ch event]
  (try
    (hk/send! ch (format-event event) false)
    (catch Exception e
      (log/debug e "SSE send failed; channel likely closed")
      false)))

(defn send-comment!
  "Send a raw comment line (used for heartbeats). Browsers ignore
   comment lines but proxies see them as traffic."
  [ch]
  (try
    (hk/send! ch (heartbeat-frame) false)
    (catch Exception e
      (log/debug e "SSE heartbeat failed; channel likely closed")
      false)))

(defn close!
  "Close an SSE channel."
  [ch]
  (try (hk/close ch) (catch Exception _ nil)))

(defn open!
  "Open an SSE channel from a ring request. Returns a map:
     {:channel <opaque http-kit channel>
      :stop! (fn [] …)        — call to tear down heartbeat + close}

   `on-open` is called once with the channel as soon as the stream is
   live, so the caller can register the channel with the event bus or
   send a hello frame.

   `on-close` is called once with no args when the browser disconnects
   (or close! is invoked) — use it to deregister from the bus.

   The fn arranges its own heartbeat timer; the caller does not need
   to send keepalives.

   Usage:
     (defn my-handler [req]
       (sse/open! req
         {:on-open  (fn [ch] (bus/subscribe! :my-topic ch))
          :on-close (fn []  (bus/unsubscribe-all-by-channel! :my-topic ...))}))"
  [req {:keys [on-open on-close]}]
  (hk/as-channel
   req
   {:on-open
    (fn [ch]
      ;; Prelude: send headers + an opening heartbeat so the browser
      ;; flips EventSource to OPEN immediately rather than after the
      ;; first real event.
      (hk/send! ch
                {:status  200
                 :headers (sse-headers)
                 :body    (heartbeat-frame)}
                false)
      ;; Heartbeat loop on a background thread. We don't use a
      ;; ScheduledExecutorService because anvil already has core.async
      ;; on the classpath and we want zero new infrastructure here.
      (let [running (atom true)
            stop! (fn []
                    (reset! running false)
                    (close! ch))]
        (future
          (try
            (while @running
              (Thread/sleep heartbeat-interval-ms)
              (when @running
                (when-not (send-comment! ch)
                  ;; Channel is gone — stop heartbeating.
                  (reset! running false))))
            (catch InterruptedException _
              (reset! running false))))
        (when on-open (on-open ch))
        ;; Stash the stopper so on-close can call it.
        (hk/on-close ch (fn [_status]
                          (reset! running false)
                          (when on-close (on-close))))))}))
