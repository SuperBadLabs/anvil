(ns anvil.web.jenkins-api.jobs
  "Job + build store for the Jenkins REST API shim.

   v1 in-memory atom (TX6); TX9 phase 3 adds optional SQLite persistence
   via anvil.storage.jobs. When anvil.storage.db/init! has been called,
   every mutation also writes through to disk and reads fall back to disk
   when the atom doesn't have the row. Tests that never opt in keep the
   atom-only behavior — the dual-write is transparent.

   Shape:

     state {:jobs {NAME job}
            :builds [build ...]              ; flat list, all builds
            :next-build-number {NAME N}      ; per-job sequence
            :counter ATOMIC-INT}

     job {:name STRING
          :jenkinsfile-source STRING
          :buildable? BOOL
          :builds-by-number {N BUILD}        ; convenience index
          :last-build N?
          :last-successful-build N?
          :last-failed-build N?
          :color KEYWORD}                    ; jenkins color enum

     build {:job-name STRING
            :number N
            :id STRING
            :result :running | :success | :failure | :aborted | :unstable
            :building? BOOL
            :duration-ms LONG
            :started-at INSTANT
            :ended-at INSTANT?
            :console-log STRING              ; concatenated effect lines
            :effects [...]                   ; raw from the dispatcher
            :url STRING}

   Concurrency: a single atom holds everything. swap! handles CAS on
   build creation; reads are lock-free."
  (:require [clojure.string :as str]
            [anvil.events.bus :as bus]
            [anvil.storage.db :as db]
            [anvil.storage.jobs :as persist])
  (:import [java.time Instant]))

(defn- persistence? [] (some? (db/datasource)))

(defonce ^:private state (atom {:jobs {} :builds [] :next-build-number {}}))

(defn clear!
  "For tests. Removes all jobs and builds from atom + persistence (if active)."
  []
  (reset! state {:jobs {} :builds [] :next-build-number {}})
  (when (persistence?) (persist/delete-all-jobs!))
  nil)

(defn register-job!
  "Register a job. Idempotent — re-registering replaces the source
   string but preserves the build history.

   `:scm` is optional — `{:type :git :url ... :branch ...}`. When set,
   the runner will `git clone --depth 1 --branch X` the URL into the
   workspace before the first sh step. Without it, the workspace stays
   empty (current behavior) — Jenkinsfiles whose `sh` steps don't
   reference workspace files still work."
  [{:keys [name jenkinsfile-source buildable? max-concurrent-builds scm]
    :or {buildable? true max-concurrent-builds 1}
    :as args}]
  (assert (string? name) "name must be string")
  (assert (string? jenkinsfile-source) "jenkinsfile-source must be string")
  (when scm
    (assert (string? (:url scm)) "scm.url must be string"))
  (swap! state update-in [:jobs name]
         (fn [existing]
           (merge {:name name
                   :jenkinsfile-source jenkinsfile-source
                   :buildable? buildable?
                   :max-concurrent-builds max-concurrent-builds
                   :builds-by-number {}
                   :color :notbuilt}
                  (select-keys existing
                               [:builds-by-number :last-build
                                :last-successful-build :last-failed-build :color])
                  ;; Always overwrite per-registration fields if a new value is
                  ;; provided — including max-concurrent-builds so operators can
                  ;; raise/lower a job's parallelism without re-creating it
                  ;; (Codex P2, PR #164).
                  {:jenkinsfile-source jenkinsfile-source
                   :buildable? buildable?
                   :max-concurrent-builds max-concurrent-builds
                   :scm scm}
                  {:name name})))
  (when (persistence?) (persist/upsert-job! args))
  nil)

(defn delete-job!
  "Remove a job from both the in-memory atom and (when persistence is on)
   the SQLite store. Returns nil. Idempotent."
  [name]
  (swap! state update :jobs dissoc name)
  (swap! state update :next-build-number dissoc name)
  (when (persistence?) (persist/delete-job! name))
  nil)

(defn- merge-disk-into-atom!
  "When persistence is on but the atom doesn't have a row (e.g. fresh
   daemon start), pull it from disk into the cache. Idempotent.

   Also seeds `:next-build-number` from the persisted `:last-build`
   counter for each job, so post-restart build allocation continues
   from `last-build + 1` rather than from 1 (which would collide
   with the persisted (job_name, number) primary key on the very
   first new build — Codex P1, PR #164)."
  []
  (when (persistence?)
    (doseq [j (persist/list-jobs)]
      (when-not (get-in @state [:jobs (:name j)])
        ;; Restore the job's last-* counters and color; build history
        ;; gets lazy-loaded by list-builds-for-job / find-build below.
        (swap! state update-in [:jobs (:name j)]
               (fn [existing]
                 (merge existing
                        (assoc j :builds-by-number (or (:builds-by-number existing) {}))))))
      ;; Seed the counter ONLY if not already set in the atom, so test
      ;; setups that pre-populate :next-build-number aren't clobbered.
      (let [last-build (or (:last-build j) 0)]
        (swap! state update-in [:next-build-number (:name j)]
               (fn [existing] (or existing last-build)))))))

(defn list-jobs []
  (merge-disk-into-atom!)
  (->> @state :jobs vals (sort-by :name) vec))

(defn find-job [name]
  (merge-disk-into-atom!)
  (get-in @state [:jobs name]))

(defn find-build [job-name number]
  ;; Atom-first; fall through to disk if not cached.
  (or (get-in @state [:jobs job-name :builds-by-number number])
      (when (persistence?)
        (when-let [b (persist/find-build job-name number)]
          (swap! state assoc-in [:jobs job-name :builds-by-number number] b)
          b))))

(defn list-builds-for-job [job-name]
  (or (let [in-atom (->> (find-job job-name)
                         :builds-by-number
                         vals)]
        (when (seq in-atom) (vec (sort-by :number > in-atom))))
      (when (persistence?)
        (persist/list-builds-for-job job-name))
      []))

;; ---------------------------------------------------------------------------
;; Build lifecycle
;; ---------------------------------------------------------------------------

(defn- result->color
  "Map an anvil build result to the Jenkins color enum. Anvil's enums
   (:success/:failure/...) map straight to Jenkins's colors; the
   `_anime` suffix is appended for in-progress builds."
  [result building?]
  (cond
    building?              :blue_anime    ; in-progress; Jenkins uses anime variants
    (= :success result)    :blue
    (= :failure result)    :red
    (= :unstable result)   :yellow
    (= :aborted result)    :aborted
    (= :running result)    :blue_anime
    :else                  :notbuilt))

(defn allocate-build-number!
  "Atomically allocate the next build number for `job-name`. Returns the
   allocated number."
  [job-name]
  (let [new-state (swap! state update-in [:next-build-number job-name]
                         (fnil inc 0))]
    (get-in new-state [:next-build-number job-name])))

(defn record-build-start!
  "Record that a build has started. Returns the build number."
  [job-name {:keys [parameters]}]
  (assert (find-job job-name)
          (str "no such job: " job-name))
  (let [n (allocate-build-number! job-name)
        url (str "/jenkins/job/" job-name "/" n "/")
        build {:job-name job-name
               :number n
               :id (str n)
               :result :running
               :building? true
               :started-at (Instant/now)
               :ended-at nil
               :duration-ms 0
               :console-log ""
               :effects []
               :parameters (or parameters {})
               :url url}]
    (swap! state
           (fn [s]
             (-> s
                 (assoc-in [:jobs job-name :builds-by-number n] build)
                 (assoc-in [:jobs job-name :last-build] n)
                 (assoc-in [:jobs job-name :color]
                           (result->color :running true))
                 (update :builds conj build))))
    (when (persistence?)
      (persist/insert-build-start!
       {:job-name job-name :number n :parameters parameters})
      (persist/update-job-summary!
       job-name {:color (name (result->color :running true)) :last-build n}))
    ;; TU1.2: announce on the event bus. Synchronous + non-throwing
    ;; (bus's publish! catches subscriber failures). Pure additive —
    ;; if nobody's subscribed (e.g. CLI usage), this is one atom deref
    ;; + a no-op doseq.
    (bus/publish! [:job job-name]
                  {:type :build-started
                   :job-name job-name
                   :build-number n
                   :url url
                   :ts (Instant/now)
                   :parameters (or parameters {})})
    n))

(defn- effects->console-log
  "Render the dispatcher's chronological effects vector to a Jenkins-style
   console-log string. Real subprocess output (:stdout / :stderr lines
   from TX9) is interleaved with the recorded step markers in order."
  [effects]
  (->> effects
       (map (fn [ev]
              (cond
                (= :sh (first ev))
                (let [{:keys [cmd exit]} (second ev)]
                  (if (some? exit)
                    (str "+ " cmd (when-not (zero? exit) (str "  [exit " exit "]")))
                    (str "+ " cmd)))

                ;; Real subprocess output lines from TX9's :execute? mode.
                (= :stdout (first ev))
                (str (second ev))

                (= :stderr (first ev))
                (str "[stderr] " (second ev))

                (= :echo (first ev))
                (str (second ev))

                (= :junit (first ev))
                (str "[junit] " (-> ev second :results))

                (= :archive (first ev))
                (str "[archive-artifacts] " (-> ev second :artifacts))

                (= :delete-dir (first ev))
                "[delete-dir]"

                (= :dir/enter (first ev))
                (str "+ cd " (second ev))

                (= :dir/leave (first ev))
                "+ cd .."

                (#{:agent/stage-enter :agent/stage-leave} (first ev))
                (str "[stage] " (-> ev second :stage)
                     (when-let [s (-> ev second :summary)]
                       (str " — agent: " s)))

                :else
                (str "[" (name (first ev)) "] " (pr-str (rest ev))))))
       (str/join "\n")))

(defn record-build-end!
  "Mark a build complete. `result` is one of :success / :failure /
   :aborted / :unstable / :neutral / :unsupported. Updates the job's
   color + last-* fields.

   `:classify-rule` and `:classify-explain` (from AN4-1's classifier)
   are stored on the build record so the build page UI can show the
   operator WHY a build wasn't green (the AN4-5 receipt).

   In streaming mode, `:log-path` points at the on-disk log file; the
   in-memory `:console-log` is augmented with the file contents on read.
   In buffered mode, `:console-log` is the full rendered string."
  [job-name build-number {:keys [result effects log-path
                                 classify-rule classify-explain]
                          :or {result :success effects []}}]
  (let [console-log-atom (atom nil)
        dur-atom (atom nil)]
    (swap! state
           (fn [s]
             (let [build (get-in s [:jobs job-name :builds-by-number build-number])
                   now (Instant/now)
                   dur (- (.toEpochMilli now)
                          (.toEpochMilli ^Instant (:started-at build)))
                   console (effects->console-log effects)
                   final-build (assoc build
                                      :result result
                                      :building? false
                                      :ended-at now
                                      :duration-ms dur
                                      :effects effects
                                      :console-log console
                                      :log-path log-path
                                      :classify-rule classify-rule
                                      :classify-explain classify-explain)
                   color (result->color result false)]
               (reset! console-log-atom console)
               (reset! dur-atom dur)
               (-> s
                   (assoc-in [:jobs job-name :builds-by-number build-number] final-build)
                   (assoc-in [:jobs job-name :color] color)
                   (assoc-in [:jobs job-name
                              (if (= :success result) :last-successful-build :last-failed-build)]
                             build-number)))))
    (when (persistence?)
      (persist/update-build-end!
       {:job-name job-name :number build-number :result result
        :effects effects :console-log @console-log-atom
        :duration-ms @dur-atom :log-path log-path})
      (persist/update-job-summary!
       job-name {:color (name (result->color result false))
                 (if (= :success result) :last-successful-build :last-failed-build) build-number}))
    ;; TU1.2: announce build completion. Same posture as build-started.
    (bus/publish! [:job job-name]
                  {:type :build-done
                   :job-name job-name
                   :build-number build-number
                   :result result
                   :duration-ms @dur-atom
                   :color (result->color result false)
                   :ts (Instant/now)}))
  nil)

(defn console-log-for
  "Resolve a build's full console log. When a streaming :log-path is
   present, slurp the file and concatenate with the metadata-rendered
   prefix from `effects→console-log`. Otherwise return the in-memory
   string."
  [build]
  (let [prefix (or (:console-log build) "")
        log-path (:log-path build)
        streamed (when (and log-path
                            (let [f (java.io.File. ^String log-path)]
                              (.exists f)))
                   (slurp log-path))]
    (cond
      (and (seq prefix) (seq streamed)) (str prefix "\n" streamed)
      streamed                          streamed
      :else                             prefix)))

;; ---------------------------------------------------------------------------
;; Convenience accessors for the handler layer
;; ---------------------------------------------------------------------------

(defn job-summary
  "Pluck the fields the Jenkins job-list endpoint cares about."
  [job]
  (-> job
      (select-keys [:name :color :buildable?])
      (assoc :build-count (count (:builds-by-number job)))
      (assoc :last-build (:last-build job))
      (assoc :last-successful-build (:last-successful-build job))
      (assoc :last-failed-build (:last-failed-build job))))
