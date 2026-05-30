(ns anvil.storage.db
  "Anvil's storage backend — SQLite via chengis-core's connection + migrate
   plumbing.

   Anvil is intentionally single-node + SQLite-only at v1. The HikariCP /
   PostgreSQL paths in chengis.db.connection are present for the Chengis
   product to use; anvil doesn't reach for them.

   Default location: ~/.anvil/anvil-data.db (created on first start).
   Tests override via the ANVIL_DB_PATH env var or by direct API calls.

   Lifecycle:
     - `init!` runs migrations against the configured DB file
     - `datasource` returns the underlying javax.sql.DataSource for store-
       level code
     - `close!` shuts the connection pool (test teardown)"
  (:require [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]))

(defonce ^:private state (atom {:datasource nil :db-path nil}))

(defn default-db-path
  "Where anvil persists by default. Override with ANVIL_DB_PATH."
  []
  (or (System/getenv "ANVIL_DB_PATH")
      (str (System/getProperty "user.home") "/.anvil/anvil-data.db")))

(defn- ensure-parent! [path]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))))

(defn- run-migrations! [path]
  ;; chengis-core's `migrate/migrate!` accepts a config map with
  ;; :migrations-dir override (TX9-phase-3 addition) so anvil can point
  ;; at its own resources/anvil-migrations/sqlite/ tree without
  ;; conflicting with chengis-product migrations.
  (migrate/migrate!
   {:type "sqlite"
    :path path
    :migrations-dir "anvil-migrations/sqlite"}))

(defn init!
  "Open (or create) the anvil DB at `path` and run migrations. Idempotent —
   calling again with the same path is a no-op."
  ([] (init! (default-db-path)))
  ([path]
   (when (or (nil? (:db-path @state))
             (not= path (:db-path @state)))
     (ensure-parent! path)
     (run-migrations! path)
     (let [ds (conn/create-datasource path)]
       (reset! state {:datasource ds :db-path path})))
   (:datasource @state)))

(defn datasource
  "Return the active datasource, or nil if init! hasn't run."
  []
  (:datasource @state))

(defn close!
  "Tear down the connection pool. Tests call this in fixtures."
  []
  (reset! state {:datasource nil :db-path nil})
  nil)

(defn reset-for-test!
  "Convenience: close any existing datasource, delete the file, init! a fresh DB."
  [path]
  (close!)
  (when (.exists (io/file path)) (.delete (io/file path)))
  (init! path))
