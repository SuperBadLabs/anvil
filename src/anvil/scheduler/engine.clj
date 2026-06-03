(ns anvil.scheduler.engine
  "Cron scheduler engine (T5.2 of the v0.3 board).

   Single daemon thread + ScheduledExecutorService. For each
   registered job:
     - Compute next-fire time via cron-parser/next-fire-after.
     - Schedule a one-shot wake-up at that moment.
     - On wake: publish :schedule-fired on [:job <name>], trigger
       the build via the supplied trigger-fn, then recompute the
       next fire and reschedule.

   Timezone defaults to UTC; override per anvil.edn :anvil.scheduler/timezone."
  (:require [anvil.scheduler.cron-parser :as cron]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.config :as config]
            [taoensso.timbre :as log])
  (:import [java.time ZonedDateTime ZoneId]
           [java.util.concurrent Executors ScheduledExecutorService TimeUnit
                                 ScheduledFuture]))

(defn timezone
  "ZoneId from anvil.edn or default UTC."
  []
  (let [tz (or (:anvil.scheduler/timezone (config/load-edn "anvil" {}))
               "UTC")]
    (ZoneId/of tz)))

(defonce ^:private state
  (atom {:exec nil          ; ScheduledExecutorService
         :jobs {}}))         ; {job-name → {:ir … :next-fire ZDT :future SF}}

(defn now []
  (ZonedDateTime/now (timezone)))

(defn- millis-until [^ZonedDateTime t]
  (let [diff-ms (.toEpochMilli (.toInstant t))
        now-ms (System/currentTimeMillis)]
    (max 0 (- diff-ms now-ms))))

(declare reschedule!)

(defn- fire-job! [job-name trigger-fn]
  (try
    (log/info (str "anvil.scheduler: firing " job-name))
    (let [now-t (now)
          info (get-in @state [:jobs job-name])
          next-t (cron/next-fire-after (:ir info) now-t)]
      (bus/publish! (topics/topic-job job-name)
                    {:type topics/evt-schedule-fired
                     :job-name job-name
                     :triggered-at (.toInstant now-t)
                     :next-run (some-> next-t .toInstant)})
      (when trigger-fn
        (trigger-fn job-name {"TRIGGER" "scheduled"
                              "SCHEDULED_AT" (str now-t)})))
    (catch Throwable t
      (log/warn t "anvil.scheduler/fire-job! threw")))
  (reschedule! job-name trigger-fn))

(defn- reschedule!
  "Compute the next fire-time and schedule a one-shot wake-up."
  [job-name trigger-fn]
  (let [{:keys [exec jobs]} @state
        ir (get-in jobs [job-name :ir])]
    (when (and exec ir)
      (let [next-t (cron/next-fire-after ir (now))]
        (if-not next-t
          (log/warn (str "anvil.scheduler: no fire time within 366d for " job-name))
          (let [delay (millis-until next-t)
                ^Runnable r (fn [] (fire-job! job-name trigger-fn))
                fut (.schedule ^ScheduledExecutorService exec
                               r delay TimeUnit/MILLISECONDS)]
            (swap! state assoc-in [:jobs job-name]
                   {:ir ir :next-fire next-t :future fut})))))))

(defn register-job!
  "Register / re-register a job's cron schedule. `cron-expr` is a
   Jenkins-style cron string. `trigger-fn` is called as
   `(trigger-fn job-name params-map)` when the schedule fires."
  [job-name cron-expr trigger-fn]
  (when-let [old-fut ^ScheduledFuture (get-in @state [:jobs job-name :future])]
    (.cancel old-fut false))
  (let [ir (cron/parse cron-expr job-name)]
    (swap! state assoc-in [:jobs job-name] {:ir ir :future nil})
    (reschedule! job-name trigger-fn)))

(defn unregister-job! [job-name]
  (when-let [old-fut ^ScheduledFuture (get-in @state [:jobs job-name :future])]
    (.cancel old-fut false))
  (swap! state update :jobs dissoc job-name))

(defn next-fire-for
  "Inspector for the UI pill (T5.5)."
  [job-name]
  (get-in @state [:jobs job-name :next-fire]))

(defn start!
  "Spin up the scheduler thread pool. Idempotent."
  []
  (when-not (:exec @state)
    (let [exec (Executors/newSingleThreadScheduledExecutor)]
      (swap! state assoc :exec exec)
      (log/info "anvil.scheduler: started"))))

(defn stop!
  "Shut down the pool and cancel pending fires."
  []
  (when-let [exec ^ScheduledExecutorService (:exec @state)]
    (.shutdownNow exec))
  (reset! state {:exec nil :jobs {}}))
