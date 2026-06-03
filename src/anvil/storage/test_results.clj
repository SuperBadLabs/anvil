(ns anvil.storage.test-results
  "SQLite persistence for per-build test results + per-build summary
   (T1.3 of the v0.3 board).

   Two tables (see migration 005-test-results):

     anvil_test_results
       — one row per <testcase> across all suites in a build
       — keyed on (job_name, build_number, test_id) but with an
         INTEGER autoincrement PK so duplicate test_ids in the same
         build (parameterized tests, retries) don't conflict
       — CASCADE delete on builds

     anvil_test_summaries
       — one row per (job_name, build_number)
       — denormalized counts so dashboard renders don't COUNT-with-
         CASE-WHEN per page load (TU0.7 budget allergy)

   The scanner (anvil.compat.junit/scan-build-artifacts) calls
   `record-build-results!` after parsing — one transactional bulk
   insert + summary upsert per build. Producers above the storage
   layer never see SQL."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [anvil.storage.db :as db]))

;; ---------------------------------------------------------------------------
;; Row ⇄ map
;; ---------------------------------------------------------------------------

(defn- result-row->map [row]
  (when row
    {:id            (:anvil_test_results/id row)
     :job-name      (:anvil_test_results/job_name row)
     :build-number  (:anvil_test_results/build_number row)
     :test-id       (:anvil_test_results/test_id row)
     :name          (:anvil_test_results/test_name row)
     :class         (:anvil_test_results/test_class row)
     :status        (keyword (:anvil_test_results/status row))
     :duration-ms   (:anvil_test_results/duration_ms row)
     :failure-msg   (:anvil_test_results/failure_msg row)
     :failure-type  (:anvil_test_results/failure_type row)
     :failure-trace (:anvil_test_results/failure_trace row)}))

(defn- summary-row->map [row]
  (when row
    {:job-name     (:anvil_test_summaries/job_name row)
     :build-number (:anvil_test_summaries/build_number row)
     :tests        (:anvil_test_summaries/tests row)
     :passed       (:anvil_test_summaries/passed row)
     :failed       (:anvil_test_summaries/failed row)
     :errored      (:anvil_test_summaries/errored row)
     :skipped      (:anvil_test_summaries/skipped row)
     :duration-ms  (:anvil_test_summaries/duration_ms row)
     :parse-errors (:anvil_test_summaries/parse_errors row)}))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(defn- insert-case-row [job-name build-number c]
  [job-name
   build-number
   (:test-id c)
   (:name c)
   (:class c)
   (name (:status c))
   (or (:duration-ms c) 0)
   (:failure-msg c)
   (:failure-type c)
   (:failure-trace c)])

(defn record-build-results!
  "Persist a parsed surefire tree (the output of
   `anvil.compat.junit/parse-surefire-tree`) for `(job-name,
   build-number)`. Replaces any previous test rows for this build
   (retries / re-scans) under a single transaction so partial
   inserts aren't observable.

   Returns the persisted summary map."
  [job-name build-number tree]
  (let [ds (db/datasource)
        cases (mapcat :cases (:suites tree))
        totals (:totals tree)
        parse-error-count (count (:parse-errors tree))]
    (when ds
      (jdbc/with-transaction [tx ds]
        ;; Idempotent: scan-then-rescan replaces prior rows.
        (jdbc/execute-one!
         tx
         ["DELETE FROM anvil_test_results
           WHERE job_name = ? AND build_number = ?"
          job-name build-number])
        (when (seq cases)
          (jdbc/execute-batch!
           tx
           "INSERT INTO anvil_test_results
             (job_name, build_number, test_id, test_name, test_class,
              status, duration_ms, failure_msg, failure_type, failure_trace)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
           (mapv #(insert-case-row job-name build-number %) cases)
           {}))
        (jdbc/execute-one!
         tx
         ["INSERT INTO anvil_test_summaries
             (job_name, build_number, tests, passed, failed, errored,
              skipped, duration_ms, parse_errors)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(job_name, build_number) DO UPDATE SET
             tests        = excluded.tests,
             passed       = excluded.passed,
             failed       = excluded.failed,
             errored      = excluded.errored,
             skipped      = excluded.skipped,
             duration_ms  = excluded.duration_ms,
             parse_errors = excluded.parse_errors,
             scanned_at   = datetime('now')"
          job-name build-number
          (:tests totals) (:passed totals) (:failed totals)
          (:errored totals) (:skipped totals) (:duration-ms totals)
          parse-error-count])))
    {:job-name job-name
     :build-number build-number
     :tests (:tests totals)
     :passed (:passed totals)
     :failed (:failed totals)
     :errored (:errored totals)
     :skipped (:skipped totals)
     :duration-ms (:duration-ms totals)
     :parse-errors parse-error-count}))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn find-summary
  "Lookup the per-build test summary. Returns nil if no scan landed."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (-> (jdbc/execute-one!
         ds
         ["SELECT * FROM anvil_test_summaries
           WHERE job_name = ? AND build_number = ?"
          job-name build-number])
        summary-row->map)))

(defn find-results
  "All persisted case rows for a build, ordered by class then name."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_results
            WHERE job_name = ? AND build_number = ?
            ORDER BY test_class, test_name"
           job-name build-number])
         (mapv result-row->map))))

(defn find-failed-results
  "Just the failed/errored rows for a build — the dashboard's
   'Failures' card. Faster than fetching all + filtering when most
   tests pass."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_results
            WHERE job_name = ? AND build_number = ?
              AND status IN ('failed','errored')
            ORDER BY test_class, test_name"
           job-name build-number])
         (mapv result-row->map))))

(defn find-test-history
  "Recent-N results for a single test (job + test_id), most-recent-
   first. Drives the per-test sparkline + the trend-by-status
   dashboard widget (T1.4)."
  [job-name test-id limit]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_results
            WHERE job_name = ? AND test_id = ?
            ORDER BY build_number DESC LIMIT ?"
           job-name test-id limit])
         (mapv result-row->map))))

(defn recent-summaries
  "Recent-N per-build summaries for a job — drives the build-page
   sparkline of pass-rate from the v0.3 board T1.4 spec."
  [job-name limit]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_test_summaries
            WHERE job_name = ?
            ORDER BY build_number DESC LIMIT ?"
           job-name limit])
         (mapv summary-row->map))))
