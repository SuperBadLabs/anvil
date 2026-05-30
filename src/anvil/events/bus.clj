(ns anvil.events.bus
  "In-process topic-keyed pub/sub for anvil UI's real-time path (TU1.1).

   Why we wrote our own and not core.async:

     - core.async's chan/mult ergonomics fight us when subscribers have
       different lifetimes (one SSE session opens, one closes) and
       different fan-out shapes (1 build event → N tabs subscribed to
       that job + M tabs subscribed to :global).
     - We don't need transducer pipelines. We need: 'tell me when X
       happens', cheap to register, cheap to drop.
     - Atom-of-{topic → set-of-listener-records} is ~30 lines of code
       and lets us hand the producer a synchronous publish! whose hot
       path is one deref + one doseq.

   Topics:

     A topic is any value (typically a keyword or [keyword name] pair):
       :global                ; catch-all — every publish fans out here
       :job/<name>            ; build lifecycle for one job
       [:build job-name n]    ; per-build (step events, log lines)
       :queue                 ; queue size changes
     The bus does NO interpretation — it just routes by equality on
     the topic value. The :global keyword is special: every publish,
     regardless of its own topic, ALSO fans out to :global subscribers.

   Subscription lifetime:

     (def token (subscribe! :queue (fn [event] ...)))
     ...later...
     (unsubscribe! token)

     A subscriber fn that throws is logged and dropped — one bad
     listener can't take down the producer. We don't auto-unsubscribe
     it though; the caller (typically an SSE handler) is responsible
     for explicit cleanup on disconnect.

   Back-pressure:

     publish! is synchronous and calls each subscriber inline. The
     SSE handler's subscriber wraps http-kit's non-blocking send!, so
     this stays sub-millisecond even with hundreds of subscribers.
     If a future producer wants to publish from a hot loop without
     blocking, wrap the call in (future ...).

   Thread safety:

     - subscribe! / unsubscribe! mutate the registry atom via swap!.
     - publish! takes a snapshot (single deref) and iterates that.
     - A subscriber added during a publish may or may not see that
       event; one removed during a publish may still receive it.
       Both are acceptable — there is no exactly-once contract.

   Tested in anvil.events.bus-test."
  (:require [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Internal state
;;
;; Registry shape:
;;   {topic-value #{ {:id token :fn listener} ... }}
;;
;; Sets-of-records, keyed by topic. Two listeners on the same topic
;; with the same fn share NO state — each subscription has a unique
;; token so unsubscribe is unambiguous.
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))
(defonce ^:private token-seq (atom 0))

(defn- next-token! []
  (swap! token-seq inc))

;; Reverse index: token → topic, so unsubscribe! is O(1) for finding
;; which topic-bucket to scrub. Built alongside the main registry.
(defonce ^:private token->topic (atom {}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn subscribe!
  "Register `listener` (a fn of one arg — the event map) to receive
   publishes on `topic`. Returns an opaque token for later
   unsubscribe!.

   Use :global as the topic to receive every published event regardless
   of its own topic."
  [topic listener]
  (let [token (next-token!)
        record {:id token :fn listener}]
    (swap! registry update topic (fnil conj #{}) record)
    (swap! token->topic assoc token topic)
    token))

(defn unsubscribe!
  "Remove the subscription identified by `token`. Idempotent — calling
   twice with the same token is a no-op."
  [token]
  (when-let [topic (get @token->topic token)]
    (swap! registry update topic
           (fn [subs]
             (let [remaining (into #{} (remove #(= (:id %) token)) subs)]
               (if (empty? remaining) nil remaining))))
    (swap! registry (fn [r]
                      (if (and (contains? r topic) (nil? (get r topic)))
                        (dissoc r topic)
                        r)))
    (swap! token->topic dissoc token)
    true))

(defn unsubscribe-all!
  "Unregister EVERY subscription. Used by tests + by server shutdown
   to make sure no listeners survive into the next test/run."
  []
  (reset! registry {})
  (reset! token->topic {})
  nil)

(defn publish!
  "Synchronously deliver `event` to every subscriber registered on
   `topic`, then to every subscriber registered on :global.

   `event` is whatever map the producer cares to send; convention is:
     {:type    <keyword>          ; e.g. :build-started
      :topic   <topic-value>      ; copied from this arg for convenience
      :ts      <java.time.Instant>
      :payload {...}}             ; type-specific

   The bus enriches the event with the topic if it isn't already
   present, so subscribers always know where it came from (useful for
   :global subscribers fanning many topics into one stream).

   ## Self-removing subscribers

   A subscriber may signal that it no longer wants events by RETURNING
   the keyword `::bus/unsubscribe` (or, equivalently,
   `:anvil.events.bus/unsubscribe`). The bus removes it post-publish.
   This is how the SSE handler cleans itself up when the underlying
   browser connection is gone — much more reliable than depending on
   http-kit's `:on-close` being eagerly fired on a TCP FIN.

   ## Error handling

   A subscriber fn that throws is logged at WARN; the exception does
   NOT propagate and other subscribers still see the event. A throwing
   subscriber is also auto-removed, on the principle that one that
   crashed once will crash again."
  [topic event]
  (let [enriched (cond-> event
                   (not (contains? event :topic)) (assoc :topic topic))
        snapshot @registry
        topic-subs (get snapshot topic)
        global-subs (when (not= :global topic) (get snapshot :global))
        to-drop (transient [])]
    (doseq [{:keys [id fn]} (concat topic-subs global-subs)]
      (try
        (let [r (fn enriched)]
          (when (= r ::unsubscribe)
            (conj! to-drop id)))
        (catch Throwable t
          (log/warn t "anvil.events.bus: subscriber threw on" (:type event)
                    "; auto-removing")
          (conj! to-drop id))))
    (doseq [tok (persistent! to-drop)] (unsubscribe! tok))
    nil))

;; ---------------------------------------------------------------------------
;; Introspection (for tests + future /metrics page)
;; ---------------------------------------------------------------------------

(defn subscriber-counts
  "Return {topic → count} of currently-registered subscribers. Mainly
   for tests + a future /metrics page."
  []
  (into {} (map (fn [[topic subs]] [topic (count subs)])) @registry))

(defn subscriber-count
  "Total number of live subscriptions across all topics."
  []
  (reduce + 0 (vals (subscriber-counts))))
