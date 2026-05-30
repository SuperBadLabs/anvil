(ns anvil.web.jenkins-api.queue
  "Async build queue + worker pool.

   Replaces TX6's `(future (run-build! ...))` inline pattern with a
   real queue. Trigger handlers `enqueue!` and return immediately
   with the queue id; worker threads pick up items and run them
   through `anvil.web.jenkins-api.runner/run-build!`.

   v1 limits:
     - In-memory queue (lost on daemon restart). Persistence wires
       when chengis.engine.executor's scheduler integration lands.
     - FIFO ordering only; no priorities yet.
     - Cancellation supported at the queue-item level only;
       in-flight cancellation lands when the dispatcher gains a
       cooperative interrupt hook (TX9 phase 5+).

   Per-job concurrency:
     Each job has `:max-concurrent-builds` (defaults to 1 — matches
     Jenkins's `disableConcurrentBuilds()` posture). Workers check
     the running-per-job counter before dispatching; if at the cap,
     they re-enqueue the item at the tail and pick the next one."
  (:require [anvil.events.bus :as bus]
            [taoensso.timbre :as log]
            [anvil.web.jenkins-api.jobs :as jobs])
  (:import [java.util.concurrent
            ExecutorService Executors LinkedBlockingDeque TimeUnit
            ThreadFactory]
           [java.time Instant]
           [java.util.concurrent.atomic AtomicLong]))

(defonce ^:private next-queue-id (AtomicLong. 0))

(defonce ^:private state
  (atom {:deque (LinkedBlockingDeque.)
         :items-by-id {}            ; queue-id → item record
         :executor nil               ; ExecutorService when running
         :running-per-job {}         ; job-name → count
         :max-workers 2
         :run-fn nil
         :stopping? false}))

(defn- mk-thread-factory [^String name-prefix]
  (let [counter (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable)
          (.setName (str name-prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

;; ---------------------------------------------------------------------------
;; Per-job concurrency helpers
;; ---------------------------------------------------------------------------

(defn- job-concurrency-cap
  "Look up the job's max-concurrent-builds. Defaults to 1
   (matches Jenkins's disableConcurrentBuilds() default posture)."
  [job-name]
  (or (some-> (jobs/find-job job-name) :max-concurrent-builds) 1))

(defn- running-count [job-name]
  (get-in @state [:running-per-job job-name] 0))

(defn- can-start? [job-name]
  (< (running-count job-name) (job-concurrency-cap job-name)))

(defn- dec-running! [job-name]
  (swap! state update-in [:running-per-job job-name]
         (fn [n] (max 0 (dec (or n 0))))))

(defn- try-claim-slot!
  "Atomically check the per-job concurrency cap and increment the
   running counter. Returns true on success, false if at cap. Uses CAS
   so two workers can never both pass the check simultaneously."
  [job-name]
  (let [cap (job-concurrency-cap job-name)]
    (loop []
      (let [s @state
            cur (or (get-in s [:running-per-job job-name]) 0)]
        (cond
          (>= cur cap)
          false

          (compare-and-set! state s
                            (update-in s [:running-per-job job-name]
                                       (fnil inc 0)))
          true

          :else
          (recur))))))

;; ---------------------------------------------------------------------------
;; Queue admission
;; ---------------------------------------------------------------------------

(defn enqueue!
  "Add a build request to the queue. Returns a {:queue-id N :job-name S
   :enqueued-at INSTANT} record. Idempotent only at the granularity of
   distinct queue ids — enqueueing the same job twice produces two
   queue items."
  [job-name {:keys [parameters]}]
  (assert (string? job-name) "job-name must be string")
  (let [qid (.incrementAndGet ^AtomicLong next-queue-id)
        item {:queue-id qid
              :job-name job-name
              :parameters (or parameters {})
              :enqueued-at (Instant/now)
              :cancelled? false}]
    (swap! state
           (fn [s]
             (-> s
                 (assoc-in [:items-by-id qid] item)
                 (update :deque (fn [^LinkedBlockingDeque d]
                                  (.addLast d qid) d)))))
    ;; TU1.2: announce queue admission. Lets the dashboard's "Queue"
    ;; stat card and the /queue page tick up live.
    (bus/publish! :queue
                  {:type :queue-enqueued
                   :queue-id qid
                   :job-name job-name
                   :ts (Instant/now)})
    item))

(defn cancel!
  "Mark a queued item cancelled. Returns true if the item existed and
   was queued; false otherwise. Workers skip cancelled items when
   they're picked up."
  [queue-id]
  (let [present? (boolean (get-in @state [:items-by-id queue-id]))]
    (when present?
      (swap! state assoc-in [:items-by-id queue-id :cancelled?] true))
    present?))

;; ---------------------------------------------------------------------------
;; Snapshots for the REST surface
;; ---------------------------------------------------------------------------

(defn queue-snapshot
  "Vector of currently-queued items in queue order."
  []
  (let [s @state
        ids (vec (.toArray ^LinkedBlockingDeque (:deque s)))]
    (mapv (fn [qid] (get-in s [:items-by-id qid])) ids)))

(defn find-by-id
  "Look up a queued item by its `queue-id`. Returns nil if the item
   has already been picked up by a worker or doesn't exist.

   Used by the Jenkins-compat `/jenkins/queue/item/<id>/` endpoint so
   clients following the Location header returned by `POST /build`
   don't get a 404 (Codex P2, PR #164)."
  [queue-id]
  (get-in @state [:items-by-id queue-id]))

(defn running-snapshot
  "Map of {job-name running-count} — what's currently building."
  []
  (:running-per-job @state))

;; ---------------------------------------------------------------------------
;; Worker loop
;; ---------------------------------------------------------------------------

(defn- pick-runnable!
  "Pull the next runnable item off the deque. Returns nil if either:
     - the deque is empty within `wait-ms`
     - none of the queued items pass the per-job concurrency claim
       (in which case picked items get put back at the TAIL so other
       workers can try them on the next pass).
   On success, the running-per-job counter has already been
   incremented via try-claim-slot! — the worker is responsible for
   calling dec-running! when done."
  [wait-ms]
  (let [^LinkedBlockingDeque dq (:deque @state)
        first-qid (.pollFirst dq wait-ms TimeUnit/MILLISECONDS)]
    (when first-qid
      (let [item (get-in @state [:items-by-id first-qid])]
        (cond
          ;; Cancelled before pickup — keep the item in items-by-id
          ;; tagged :cancelled so /jenkins/queue/item/<id> can report
          ;; "cancelled" instead of 404 (Codex P2, PR #164).
          (:cancelled? item)
          (do (swap! state assoc-in [:items-by-id first-qid :phase] :cancelled)
              ;; Try again immediately with another item.
              (recur 0))

          (try-claim-slot! (:job-name item))
          (do (swap! state assoc-in [:items-by-id first-qid :phase] :dispatched)
              ;; TU1.2: queue size went down by one + a build is about
              ;; to start. Dashboard "Queue" counter re-fetches.
              (bus/publish! :queue
                            {:type :queue-dispatched
                             :queue-id first-qid
                             :job-name (:job-name item)
                             :ts (Instant/now)})
              item)

          :else
          ;; Per-job cap hit — re-enqueue at the tail, try the next.
          (do (.addLast dq first-qid)
              (Thread/sleep 50)
              nil))))))

(defn- worker-loop
  "Per-worker thread loop. Picks runnable items, executes via the
   registered run-fn, updates per-job running counter."
  [run-fn]
  (while (not (:stopping? @state))
    (try
      (when-let [item (pick-runnable! 200)]
        (let [{:keys [queue-id job-name parameters]} item]
          ;; pick-runnable! already called try-claim-slot! which
          ;; incremented running-per-job atomically. We're responsible
          ;; for dec-running! when done.
          (try
            (let [result (run-fn job-name {:parameters parameters
                                           :queue-id queue-id})]
              ;; Mark the item as :completed and retain a back-pointer
              ;; to the build so /jenkins/queue/item/<id> can return
              ;; the executable URL (Codex P2, PR #164). Items are
              ;; KEPT in items-by-id; if memory pressure becomes real
              ;; in v1.x we can add a TTL sweep.
              (swap! state update-in [:items-by-id queue-id]
                     (fnil merge {})
                     {:phase :completed
                      :build-number (:build-number result)
                      :build-result (:result result)}))
            (catch Throwable t
              (log/error t "build run-fn threw" job-name queue-id)
              (swap! state update-in [:items-by-id queue-id]
                     (fnil merge {})
                     {:phase :failed
                      :error (str (.getMessage t))}))
            (finally
              (dec-running! job-name)))))
      (catch InterruptedException _ nil)
      (catch Throwable t
        (log/error t "worker-loop iteration failed")))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start-workers!
  "Spin up `n` worker threads that consume from the queue. `run-fn` is
   the function invoked per build — typically
   `anvil.web.jenkins-api.runner/run-build!`. Idempotent — calling
   again with the same args is a no-op."
  [n run-fn]
  (when-not (:executor @state)
    (let [exec (Executors/newFixedThreadPool
                ^int n (mk-thread-factory "anvil-build-worker"))]
      (swap! state assoc
             :executor exec
             :run-fn run-fn
             :max-workers n
             :stopping? false)
      (dotimes [_ n]
        (.submit ^ExecutorService exec ^Runnable #(worker-loop run-fn)))
      (log/info (str "anvil queue: " n " worker(s) started")))))

(defn stop-workers!
  "Signal workers to drain and shut down. Blocks up to `timeout-ms`
   for in-flight builds to complete."
  ([] (stop-workers! 5000))
  ([timeout-ms]
   (when-let [^ExecutorService exec (:executor @state)]
     (swap! state assoc :stopping? true)
     (.shutdown exec)
     (.awaitTermination exec timeout-ms TimeUnit/MILLISECONDS)
     (when-not (.isTerminated exec)
       (.shutdownNow exec))
     (swap! state assoc :executor nil :run-fn nil :stopping? false)
     (log/info "anvil queue: workers stopped"))))

(defn workers-running? []
  (some? (:executor @state)))

(defn clear!
  "Wipe the queue + counters. For tests."
  []
  (stop-workers!)
  (let [^LinkedBlockingDeque dq (:deque @state)]
    (.clear dq))
  (swap! state assoc
         :items-by-id {}
         :running-per-job {}
         :stopping? false)
  nil)
