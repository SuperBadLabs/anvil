(ns anvil.events.topics
  "Registry of event topics + event :type values used on the anvil bus.

   ## Why this exists (T0.4)

   `anvil.events.bus` is topic-agnostic — it routes any value as a
   topic and any keyword as an event :type. That gives producers
   total freedom but no central place to discover:

     - What topics already exist (so a new subscriber knows what to
       listen on)
     - What event :types flow on each topic (so a UI widget knows
       what payload to expect)
     - What's coming in v0.3 but not yet produced (so a feature
       owner doesn't accidentally re-use a reserved name)

   This namespace is the discoverable manifest. **New producers
   should import constants from here rather than inlining keywords.**
   Existing producers (TX2-TU6) are left as-is to avoid churn; they
   will migrate opportunistically.

   ## Topics

   A topic is whatever value a producer publishes under. The bus
   matches by equality. Three shapes are in use:

     :queue                  ; global queue-state changes
     [:job <job-name>]       ; build lifecycle for one job
     [:build <job> <n>]      ; per-build steps + console lines
     :global                 ; (subscriber-only) catch-all

   No new topic shapes ship in v0.3.

   ## Event :type values

   Each event map carries a :type keyword identifying what happened.
   The vars below are the canonical names — using a Var instead of a
   bare keyword catches typos at compile time (`unbound-symbol` >
   silent miss).

   ### Existing (TX2–TU6, in production)

     :build-started     [:job <name>]       — dispatcher → started
     :build-done        [:job <name>]       — dispatcher → finished
     :queue-enqueued    :queue              — queue → grew
     :queue-dispatched  :queue              — queue → shrank
     :console-line      [:build <j> <n>]    — log streamer line
     :console-end       [:build <j> <n>]    — log streamer EOF

   ### Reserved for v0.3 (NOT yet produced)

     :test-completed    [:build <j> <n>]    — T1.5  surefire scan done
     :problem-found     [:build <j> <n>]    — T2.2  problem-matcher hit
     :checks-updated    [:job <name>]       — T3.3  GitHub check posted
     :schedule-fired    [:job <name>]       — T5.3  cron tick → trigger
     :secret-rotated    :global             — T6.7  master-key rotation

   ### Reserved for v0.4 leapfrog (NOT yet produced)

     :flaky-flagged          [:build <j> <n>]  — T1.4 passed-on-retry tag
     :container-step-started [:build <j> <n>]  — T2.2 docker step kicked
     :ai-suggested           [:job <name>]     — T3.4 anvil optimize result
     :provenance-attested    [:build <j> <n>]  — T4.5 sigstore attestation

   Subscribers in v0.3 T1–T7 and v0.4 T1–T4 may register for these
   now; the events will start flowing when the producer code lands
   in each respective tranche. No-producer events are silently
   absent — subscribing early is safe."
  )

;; ---------------------------------------------------------------------------
;; Topic constants (when producers/subscribers need to compose a topic)
;; ---------------------------------------------------------------------------

(def topic-queue
  "Global queue-state topic. Producers: anvil.web.jenkins-api.queue."
  :queue)

(def topic-global
  "Catch-all topic — subscribers receive every published event. Producers
   never publish *to* :global directly; the bus fans every publish to
   :global subscribers as a feature."
  :global)

(defn topic-job
  "Per-job topic. Producers publish build lifecycle events here."
  [job-name]
  [:job job-name])

(defn topic-build
  "Per-build topic. Producers publish step/console events here."
  [job-name build-number]
  [:build job-name build-number])

;; ---------------------------------------------------------------------------
;; Event :type constants — existing (mirrors current producers)
;; ---------------------------------------------------------------------------

(def evt-build-started   :build-started)
(def evt-build-done      :build-done)
(def evt-queue-enqueued  :queue-enqueued)
(def evt-queue-dispatched :queue-dispatched)
(def evt-console-line    :console-line)
(def evt-console-end     :console-end)

;; ---------------------------------------------------------------------------
;; Event :type constants — reserved for v0.3 (no producer yet)
;; ---------------------------------------------------------------------------

(def evt-test-completed
  "T1.5 — emitted after anvil.compat.junit/scan-build-artifacts finishes.
   Payload: {:type :test-completed :build-id <id> :total <n> :passed <n>
             :failed <n> :skipped <n> :duration-ms <n>}.
   Topic: [:build <job> <n>]."
  :test-completed)

(def evt-problem-found
  "T2.2 — emitted per matched diagnostic in a build log line.
   Payload: {:type :problem-found :build-id <id> :file <str> :line <n>
             :col <n> :severity #{:error :warning :info} :message <str>
             :source <matcher-id>}.
   Topic: [:build <job> <n>]."
  :problem-found)

(def evt-checks-updated
  "T3.3/T3.4 — emitted after the GitHub Checks API call returns.
   Payload: {:type :checks-updated :job-name <str> :sha <str>
             :status #{:in-progress :success :failure :neutral :cancelled}
             :check-run-url <str>}.
   Topic: [:job <name>]."
  :checks-updated)

(def evt-schedule-fired
  "T5.3 — emitted by the cron scheduler when a job's next-fire time
   is reached.
   Payload: {:type :schedule-fired :job-name <str>
             :triggered-at <inst> :next-run <inst>}.
   Topic: [:job <name>]."
  :schedule-fired)

(def evt-secret-rotated
  "T6.7 — emitted after `anvil secrets rotate-master` completes.
   Payload: {:type :secret-rotated :key-id <str> :rotated-at <inst>
             :secrets-re-encrypted <n>}.
   Topic: :global (every UI tab refreshes the secrets list)."
  :secret-rotated)

;; ---------------------------------------------------------------------------
;; Event :type constants — reserved for v0.4 leapfrog (no producer yet)
;; ---------------------------------------------------------------------------

(def evt-flaky-flagged
  "v0.4 T1.4 — emitted when `anvil.flaky/detect` marks a previously-failed
   test as :flaky? true (passed on a later attempt within the same build).
   Payload: {:type :flaky-flagged :build-id <id> :test-id <str>
             :class <str> :name <str> :retry-count <n>}.
   Topic: [:build <job> <n>]."
  :flaky-flagged)

(def evt-container-step-started
  "v0.4 T2.2 — emitted when the dispatcher hands a `container { … }`
   block to chengis-core's DockerBackend; the wrapped step begins
   running inside the named image.
   Payload: {:type :container-step-started :build-id <id> :image <str>
             :step-index <n> :workspace <str>}.
   Topic: [:build <job> <n>]."
  :container-step-started)

(def evt-ai-suggested
  "v0.4 T3.4 — emitted after `anvil optimize` runs from the UI's
   Optimize button on the job page and the Anthropic API response
   has been parsed into a structured suggestion list.
   Payload: {:type :ai-suggested :job-name <str> :build-id <id>
             :suggestions [<map> …]}.
   Topic: [:job <name>]."
  :ai-suggested)

(def evt-provenance-attested
  "v0.4 T4.5 — emitted per attested artifact after sigstore signing
   writes <artifact>.intoto.jsonl beside it.
   Payload: {:type :provenance-attested :build-id <id> :artifact <str>
             :sha256 <str> :attestation-path <str>}.
   Topic: [:build <job> <n>]."
  :provenance-attested)

;; ---------------------------------------------------------------------------
;; Self-documenting registry
;; ---------------------------------------------------------------------------

(def all-event-types
  "Every :type value the bus is expected to carry, in-prod plus
   v0.3 and v0.4 reservations. Used by docs + a future /metrics page
   that wants to enumerate observable event types."
  #{;; existing
    evt-build-started evt-build-done
    evt-queue-enqueued evt-queue-dispatched
    evt-console-line evt-console-end
    ;; v0.3 reserved
    evt-test-completed evt-problem-found
    evt-checks-updated evt-schedule-fired evt-secret-rotated
    ;; v0.4 reserved
    evt-flaky-flagged evt-container-step-started
    evt-ai-suggested evt-provenance-attested})

(def reserved-v0-3
  "Subset of `all-event-types` declared for v0.3 (now all shipped, kept
   for symmetry with reserved-v0-4 + as a historical record of which
   names came from which board)."
  #{evt-test-completed evt-problem-found
    evt-checks-updated evt-schedule-fired evt-secret-rotated})

(def reserved-v0-4
  "Subset of `all-event-types` declared for v0.4 leapfrog tranches
   T1–T4 but not yet produced. Subscribing today is safe — no events
   flow until each tranche's producer code lands."
  #{evt-flaky-flagged evt-container-step-started
    evt-ai-suggested evt-provenance-attested})
