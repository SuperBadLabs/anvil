(ns anvil.storage.problems
  "SQLite persistence for per-build problem-matcher diagnostics
   (T2.3 of the v0.3 board).

   One row per matched diagnostic; per-build denormalized summary
   for the Problems-tab pill row. See migration 006."
  (:require [next.jdbc :as jdbc]
            [anvil.storage.db :as db]))

(defn- problem-row->map [row]
  (when row
    {:id           (:anvil_problems/id row)
     :job-name     (:anvil_problems/job_name row)
     :build-number (:anvil_problems/build_number row)
     :log-seq      (:anvil_problems/log_seq row)
     :source       (:anvil_problems/source row)
     :severity     (keyword (:anvil_problems/severity row))
     :file         (:anvil_problems/file_path row)
     :line         (:anvil_problems/line_no row)
     :column       (:anvil_problems/column_no row)
     :message      (:anvil_problems/message row)}))

(defn- summary-row->map [row]
  (when row
    {:job-name     (:anvil_problem_summaries/job_name row)
     :build-number (:anvil_problem_summaries/build_number row)
     :errors       (:anvil_problem_summaries/errors row)
     :warnings     (:anvil_problem_summaries/warnings row)
     :notes        (:anvil_problem_summaries/notes row)
     :infos        (:anvil_problem_summaries/infos row)}))

(defn- update-summary-on-insert! [tx job-name build-number severity]
  (let [col (case severity
              :error    "errors"
              :warning  "warnings"
              :note     "notes"
              :info     "infos"
              "infos")]
    (jdbc/execute-one!
     tx
     [(str "INSERT INTO anvil_problem_summaries
              (job_name, build_number, " col ")
            VALUES (?, ?, 1)
            ON CONFLICT(job_name, build_number) DO UPDATE SET
              " col " = " col " + 1,
              updated_at = datetime('now')")
      job-name build-number])))

(defn record-problem!
  "Insert one problem row + bump the per-build counter atomically.
   `problem` is the IR map from anvil.compat.problem-matchers/match-line."
  [job-name build-number log-seq problem]
  (when-let [ds (db/datasource)]
    (jdbc/with-transaction [tx ds]
      (jdbc/execute-one!
       tx
       ["INSERT INTO anvil_problems
           (job_name, build_number, log_seq, source, severity,
            file_path, line_no, column_no, message)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        job-name build-number log-seq
        (:source problem)
        (name (:severity problem :info))
        (:file problem)
        (:line problem)
        (:column problem)
        (or (:message problem) "")])
      (update-summary-on-insert! tx job-name build-number
                                 (:severity problem :info)))
    nil))

(defn find-summary [job-name build-number]
  (when-let [ds (db/datasource)]
    (-> (jdbc/execute-one!
         ds
         ["SELECT * FROM anvil_problem_summaries
           WHERE job_name = ? AND build_number = ?"
          job-name build-number])
        summary-row->map)))

(defn find-problems
  "All persisted problems for a build, ordered by log_seq (i.e. as
   they appeared in the build log)."
  [job-name build-number]
  (when-let [ds (db/datasource)]
    (->> (jdbc/execute!
          ds
          ["SELECT * FROM anvil_problems
            WHERE job_name = ? AND build_number = ?
            ORDER BY log_seq"
           job-name build-number])
         (mapv problem-row->map))))

(defn find-problems-by-severity
  "Filtered variant — `severities` is a set of #{:error :warning :note :info}."
  [job-name build-number severities]
  (when-let [ds (db/datasource)]
    (let [in-clause (clojure.string/join "," (repeat (count severities) "?"))]
      (->> (jdbc/execute!
            ds
            (concat
             [(str "SELECT * FROM anvil_problems
                   WHERE job_name = ? AND build_number = ?
                     AND severity IN (" in-clause ")
                   ORDER BY log_seq")
              job-name build-number]
             (map name severities)))
           (mapv problem-row->map)))))
