(ns anvil.cost.recorder
  "v0.5 T2.1 — Cost recorder.

   Subscribes to :build-done on :global. On each event:

     1. Read the build's duration_ms from the DB.
     2. Look up the per-minute rate for the host (anvil.cost/host-rate).
     3. Compute total_cents = duration_ms × rate / 60_000.
     4. Scan build effects for :cache-hit? = true; estimate cache_saved_cents
        by attributing a proportional fraction of wall-time to cache hits
        vs total sh-step count (T2.5 honesty: we don't know per-step duration
        for non-cache hits, so we use: saved = total_cents × hits / total_steps).
     5. Persist via anvil.storage.costs/record!.
     6. Publish :cost-tallied on the [:job <name>] topic (T2.2).

   Gated by :cost-reporting feature flag. When the flag is off the
   subscriber is a no-op (does not unsubscribe — just skips on each event)."
  (:require [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.cost :as cost]
            [anvil.features :as features]
            [anvil.storage.costs :as cost-store]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Subscription lifecycle
;; ---------------------------------------------------------------------------

(defonce ^:private subscription-tokens (atom []))

;; ---------------------------------------------------------------------------
;; Cache savings estimation (T2.5)
;;
;; We don't have per-step wall-times in the current build effects.
;; Honest approximation: for every :sh effect with :cache-hit? true,
;; assume it saved 1/(total-sh-steps) of the build's total cost.
;;
;; This slightly understates savings (cached steps are often the heavy
;; compilation steps, not the light echo steps) but it's honest and
;; never invents numbers we don't have. T1.5 (workspace fingerprinting)
;; will add per-step wall-ms to effects — at that point we can replace
;; this with exact numbers.
;; ---------------------------------------------------------------------------

(defn- estimate-cache-savings
  "Estimate cache_saved_cents for a build.

   `effects` — the build's effect stream (a seq of tuples)
   `total-cents` — the build's total cost in cents

   Returns estimated cents saved by cache hits."
  [effects total-cents]
  (if (or (zero? total-cents) (empty? effects))
    0
    (let [sh-effects (filter #(= :sh (first %)) effects)
          total-sh (count sh-effects)
          cache-hits (count (filter #(:cache-hit? (second %)) sh-effects))]
      (if (or (zero? total-sh) (zero? cache-hits))
        0
        (long (Math/round (* total-cents (/ (double cache-hits) total-sh))))))))

;; ---------------------------------------------------------------------------
;; Build-done handler
;; ---------------------------------------------------------------------------

(defn- on-build-done [event]
  (try
    (when (features/enabled? :cost-reporting)
      (let [{:keys [job-name build-number duration-ms effects host]}
            (:payload event)
            ;; Host defaults to the system hostname if not in the payload.
            ;; The runner sets :host when it fires :build-done; fallback
            ;; for builds triggered before T2 ships.
            host-name (or host
                          (try (.getHostName (java.net.InetAddress/getLocalHost))
                               (catch Exception _ "localhost")))
            dur (or duration-ms 0)
            {:keys [cents currency rate-per-min]} (cost/cost-for host-name dur)
            saved-cents (estimate-cache-savings (or effects []) cents)]
        (cost-store/record! {:job-name          job-name
                             :build-number      build-number
                             :host              host-name
                             :duration-ms       dur
                             :rate-per-min      rate-per-min
                             :currency          currency
                             :total-cents       cents
                             :cache-saved-cents saved-cents})
        ;; T2.2 — publish :cost-tallied on the per-job topic.
        (bus/publish! (topics/topic-job job-name)
                      {:type              topics/evt-cost-tallied
                       :job-name          job-name
                       :build-number      build-number
                       :total-cents       cents
                       :cache-saved-cents saved-cents
                       :currency          currency})
        (log/debug (str "anvil.cost.recorder: recorded cost for "
                        job-name "#" build-number
                        " = " cents " cents"
                        " (saved " saved-cents " via cache)"))))
    (catch Throwable t
      (log/warn t "anvil.cost.recorder/on-build-done"))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Subscribe to :build-done and start recording costs.
   Idempotent — calling twice just adds a second subscription (caller
   should call stop! first if restarting)."
  []
  (let [tok (bus/subscribe! topics/topic-global
                             (fn [ev]
                               (case (:type ev)
                                 :build-done (on-build-done ev)
                                 nil)))]
    (swap! subscription-tokens conj tok)
    (log/info "anvil.cost.recorder: started")))

(defn stop!
  "Unsubscribe all cost-recorder subscriptions."
  []
  (doseq [t @subscription-tokens] (bus/unsubscribe! t))
  (reset! subscription-tokens [])
  (log/info "anvil.cost.recorder: stopped"))
