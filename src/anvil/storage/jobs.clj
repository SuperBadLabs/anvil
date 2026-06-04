(ns anvil.storage.jobs
  "SQLite-backed persistence for anvil's jobs + builds.

   v1 reads and writes plain rows via next.jdbc. effects + parameters
   are stored as serialized EDN in a text column — they're arbitrary
   Clojure data, EDN round-trips them cleanly.

   This namespace is the persistence backend. The HTTP-facing layer
   (anvil.web.jenkins-api.jobs) is unchanged in its public API; it
   delegates here when `anvil.storage.db/init!` has been called and
   stays atom-only otherwise (so tests that never opt into persistence
   continue to work)."
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [anvil.storage.db :as db])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Row ⇄ Clojure conversion
;; ---------------------------------------------------------------------------

(defn- bool->int [b] (if b 1 0))
(defn- int->bool [i] (= 1 (or i 0)))

(defn- read-edn [s]
  (when (and s (not= s "")) (edn/read-string s)))

(defn- write-edn [v]
  (if (nil? v) "" (pr-str v)))

(defn- job-row->map [row]
  (when row
    (cond-> {:name                  (:anvil_jobs/name row)
             :jenkinsfile-source    (:anvil_jobs/jenkinsfile_source row)
             :buildable?            (int->bool (:anvil_jobs/buildable row))
             :color                 (keyword (:anvil_jobs/color row))
             :last-build            (:anvil_jobs/last_build row)
             :last-successful-build (:anvil_jobs/last_successful_build row)
             :last-failed-build     (:anvil_jobs/last_failed_build row)}
      ;; Migration 008: SCM columns are NULL on legacy rows. Only attach
      ;; the :scm sub-map when both URL is present — that's the contract
      ;; the runner checks before attempting a clone.
      (and (:anvil_jobs/scm_url row) (seq (:anvil_jobs/scm_url row)))
      (assoc :scm {:type   (or (some-> (:anvil_jobs/scm_type row) keyword) :git)
                   :url    (:anvil_jobs/scm_url row)
                   :branch (or (:anvil_jobs/scm_branch row) "main")}))))

(defn- build-row->map [row]
  (when row
    {:job-name      (:anvil_builds/job_name row)
     :number        (:anvil_builds/number row)
     :id            (str (:anvil_builds/number row))
     :result        (some-> (:anvil_builds/result row) keyword)
     :building?     (int->bool (:anvil_builds/building row))
     :started-at    (some-> (:anvil_builds/started_at row) Instant/parse)
     :ended-at      (some-> (:anvil_builds/ended_at row) Instant/parse)
     :duration-ms   (:anvil_builds/duration_ms row)
     :console-log   (or (:anvil_builds/console_log row) "")
     :log-path      (:anvil_builds/log_path row)
     :effects       (or (read-edn (:anvil_builds/effects_edn row)) [])
     :parameters    (or (read-edn (:anvil_builds/parameters_edn row)) {})
     :url           (str "/jenkins/job/"
                         (:anvil_builds/job_name row) "/"
                         (:anvil_builds/number row) "/")}))

;; ---------------------------------------------------------------------------
;; Jobs CRUD
;; ---------------------------------------------------------------------------

(defn upsert-job!
  "Insert-or-replace a job row. Preserves last-* counters when an
   existing row's are non-nil and the caller doesn't supply replacements
   (so updating just the jenkinsfile-source doesn't clobber build
   history).

   `:scm` is optional — `{:type :git :url ... :branch ...}`. When
   supplied, the runner auto-checks-out the repo into the workspace
   before the first sh step (migration 008)."
  [{:keys [name jenkinsfile-source buildable? scm]
    :or {buildable? true}}]
  (let [ds (db/datasource)
        scm-type   (some-> scm :type clojure.core/name)
        scm-url    (:url scm)
        scm-branch (:branch scm)]
    (jdbc/execute-one!
     ds
     ["INSERT INTO anvil_jobs (name, jenkinsfile_source, buildable, scm_type, scm_url, scm_branch, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, datetime('now'))
       ON CONFLICT(name) DO UPDATE SET
         jenkinsfile_source = excluded.jenkinsfile_source,
         buildable = excluded.buildable,
         scm_type = excluded.scm_type,
         scm_url = excluded.scm_url,
         scm_branch = excluded.scm_branch,
         updated_at = datetime('now')"
      name jenkinsfile-source (bool->int buildable?) scm-type scm-url scm-branch])
    nil))

(defn find-job [name]
  (when-let [ds (db/datasource)]
    (-> (jdbc/execute-one!
         ds
         ["SELECT * FROM anvil_jobs WHERE name = ?" name])
        job-row->map)))

(defn list-jobs []
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute! ds ["SELECT * FROM anvil_jobs ORDER BY name"])
         (mapv job-row->map))))

(defn update-job-summary!
  "Update a job's color + last-* fields after a build completes."
  [job-name {:keys [color last-build last-successful-build last-failed-build]}]
  (let [ds (db/datasource)
        existing (find-job job-name)
        next-color (or color (some-> existing :color name))
        next-lb (or last-build (:last-build existing))
        next-lsb (or last-successful-build (:last-successful-build existing))
        next-lfb (or last-failed-build (:last-failed-build existing))]
    (jdbc/execute-one!
     ds
     ["UPDATE anvil_jobs
       SET color = ?, last_build = ?, last_successful_build = ?, last_failed_build = ?, updated_at = datetime('now')
       WHERE name = ?"
      next-color next-lb next-lsb next-lfb job-name])
    nil))

;; ---------------------------------------------------------------------------
;; Builds CRUD
;; ---------------------------------------------------------------------------

(defn allocate-build-number!
  "Return the next build number for `job-name` — max(existing) + 1."
  [job-name]
  (when-let [ds (db/datasource)]
    (let [{:keys [maxn]} (jdbc/execute-one!
                          ds
                          ["SELECT COALESCE(MAX(number), 0) AS maxn
                            FROM anvil_builds WHERE job_name = ?"
                           job-name]
                          {:builder-fn rs/as-unqualified-lower-maps})]
      (inc (or maxn 0)))))

(defn insert-build-start!
  "Insert a fresh-build row with building=1."
  [{:keys [job-name number parameters]}]
  (let [ds (db/datasource)]
    (jdbc/execute-one!
     ds
     ["INSERT INTO anvil_builds
         (job_name, number, building, started_at, parameters_edn)
       VALUES (?, ?, 1, ?, ?)"
      job-name number (str (Instant/now)) (write-edn (or parameters {}))])
    nil))

(defn update-build-end!
  "Mark a build complete. Stores effects as EDN; the renderer in
   web.jenkins-api.jobs is responsible for the human-readable
   console-log derived from those effects.

   `:log-path` records the on-disk streaming-log file location. In
   streaming mode the `console_log` column holds the metadata-only
   prefix; the full log lives at `log_path`."
  [{:keys [job-name number result effects console-log duration-ms log-path]}]
  (let [ds (db/datasource)]
    (jdbc/execute-one!
     ds
     ["UPDATE anvil_builds
       SET result = ?, building = 0, ended_at = ?, duration_ms = ?,
           console_log = ?, effects_edn = ?, log_path = ?
       WHERE job_name = ? AND number = ?"
      (some-> result name)
      (str (Instant/now))
      (or duration-ms 0)
      (or console-log "")
      (write-edn (or effects []))
      log-path
      job-name number])
    nil))

(defn find-build [job-name number]
  (when-let [ds (db/datasource)]
    (-> (jdbc/execute-one!
         ds
         ["SELECT * FROM anvil_builds WHERE job_name = ? AND number = ?"
          job-name number])
        build-row->map)))

(defn list-builds-for-job [job-name]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_builds
            WHERE job_name = ? ORDER BY number DESC"
           job-name])
         (mapv build-row->map))))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn delete-all-jobs!
  "Wipe everything — for test fixtures."
  []
  (when-let [ds (db/datasource)]
    (jdbc/execute! ds ["DELETE FROM anvil_builds"])
    (jdbc/execute! ds ["DELETE FROM anvil_jobs"])
    nil))

(defn delete-job!
  "Delete one job + its builds. Returns nil. Idempotent.

   We DELETE FROM anvil_builds FIRST and then DELETE FROM anvil_jobs.
   The schema has `ON DELETE CASCADE` declared on the FK, but SQLite
   only enforces it when `PRAGMA foreign_keys = ON` is set on every
   connection. chengis.db.connection doesn't set that today, so we
   delete explicitly to avoid orphaned builds attaching to a freshly
   re-registered job with the same name (Codex P2, PR #164)."
  [name]
  (when-let [ds (db/datasource)]
    (jdbc/execute-one! ds ["DELETE FROM anvil_builds WHERE job_name = ?" (str name)])
    (jdbc/execute-one! ds ["DELETE FROM anvil_jobs   WHERE name     = ?" (str name)])
    nil))
