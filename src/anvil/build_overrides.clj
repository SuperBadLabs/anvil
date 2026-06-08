(ns anvil.build-overrides
  "AN7-5b — operator-side build overrides.

   Lets operators inject docker resource limits + extra env vars into
   specific builds via `anvil.edn` WITHOUT modifying the upstream
   Jenkinsfile. This is the path that lets the wild-corpus heavies
   (activemq, zookeeper, jdt-core, hbase) request `--memory=4g` +
   `MAVEN_OPTS=-Xmx2g` even though their upstream Jenkinsfiles declare
   only `agent { label 'ubuntu' }`.

   ## Config shape

   In `anvil.edn`:

     {:anvil.build-overrides
        {\"wild-apache-activemq\"
           {:docker-resource-limits {:memory-mb 4096 :cpus 2.0}
            :env-extra {\"MAVEN_OPTS\" \"-Xmx2g -XX:+UseG1GC\"}}

         \"wild-apache-zookeeper\"
           {:docker-resource-limits {:memory-mb 4096}
            :env-extra {\"MAVEN_OPTS\" \"-Xmx2g\"}}}}

   Keys map directly onto:
     :docker-resource-limits → chengis-core's `:resource-limits` shape
                                (:memory-mb, :cpus, :pids-max, :cpu-shares)
                                — merged on top of any flags parsed from
                                the Jenkinsfile's `agent { docker { args } }`
                                so operator wins
     :env-extra              → merged into ctx :env at execute-via-backend
                                time, so the docker container sees them in
                                its `-e KEY=VAL` flags

   ## Load semantics — v0.6 T4 hot-reload

   Lazy: first call reads `anvil.edn` once and caches via `swap!`.

   v0.6 T4: when `(start-watcher!)` is called (from daemon boot in
   `anvil.core/run-daemon`), a daemon thread watches the resolved
   `anvil.edn` directory via `java.nio.file.WatchService` for
   `ENTRY_MODIFY` / `ENTRY_CREATE` events. On a matching event for
   `anvil.edn`, `clear-cache!` is called; the next `for-job` lookup
   triggers a fresh load.

   Tests can call `clear-cache!` directly without the watcher; the
   watcher is only attached when the daemon starts."
  (:require [anvil.config :as config]
            [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import [java.nio.file FileSystems Path Paths
            StandardWatchEventKinds WatchEvent$Kind WatchService]
           [java.io File]))

(def ^:private cache (atom nil))

(defn- load-overrides []
  (or (get (config/load-edn "anvil" {}) :anvil.build-overrides)
      {}))

(defn all
  "Returns the full overrides map. Lazy-loads on first call (race-safe via
   `swap!` — concurrent callers may load twice but the cache settles to a
   single value). Returns an empty map when no overrides configured."
  []
  (or @cache
      (swap! cache (fn [v] (or v (load-overrides))))))

(defn for-job
  "Returns the override map for `job-name`, or nil if no override is
   configured. The shape is `{:docker-resource-limits {…}? :env-extra {…}?}`
   — either key may be absent."
  [job-name]
  (when job-name
    (get (all) job-name)))

(defn clear-cache!
  "Clear the load cache so the next call re-reads anvil.edn. Tests use
   this between fixtures; operators (since v0.6 T4) get this called
   automatically when the file-watcher detects an anvil.edn change."
  []
  (reset! cache nil))

;; ---------------------------------------------------------------------------
;; v0.6 T4 — hot-reload via java.nio.file.WatchService
;; ---------------------------------------------------------------------------
;;
;; Why a directory watch + filename filter, not a file watch directly:
;;   WatchService.register() takes a *directory* path and emits events
;;   for entries inside it. Watching a single file means watching its
;;   parent directory and filtering events by filename. That's what
;;   we do.
;;
;; The watcher runs on a single daemon thread. JVM shutdown reaps it
;; (no explicit stop hook needed for the daemon-style use, but we
;; expose stop! for tests + future :scheduler-style lifecycle).

(defn- resolve-anvil-edn-path
  "Return the absolute `File` for the anvil.edn the loader would pick,
   or nil if the loader is using the classpath-bundled default
   (which can't be watched — classpath URLs aren't filesystem-watchable)."
  []
  (let [fname "anvil.edn"]
    (or (when-let [d (System/getenv "ANVIL_CONFIG_DIR")]
          (let [f (io/file d fname)]
            (when (.exists ^File f) f)))
        (let [f (io/file "config" fname)]
          (when (.exists ^File f) f)))))

(defonce ^:private watcher-state
  ;; {:thread Thread :watch-service WatchService :stop? (atom boolean)} or nil
  (atom nil))

(defn- run-watcher [^WatchService ws ^File anvil-edn-file stop-atom]
  (let [target-name (.getName anvil-edn-file)]
    (try
      (loop []
        (when-not @stop-atom
          (let [key (.take ws)]
            (try
              (doseq [^java.nio.file.WatchEvent ev (.pollEvents key)]
                (let [filename (str (.context ev))]
                  (when (= target-name filename)
                    (log/info (str "anvil.build-overrides: anvil.edn changed ("
                                   (.name (.kind ev))
                                   ") — clearing cache for hot-reload"))
                    (clear-cache!))))
              (finally (.reset key)))
            (recur))))
      (catch java.nio.file.ClosedWatchServiceException _
        ;; Normal shutdown — service closed by stop!.
        nil)
      (catch InterruptedException _
        nil)
      (catch Throwable t
        (log/warn t "anvil.build-overrides: file-watch thread crashed")))))

(defn start-watcher!
  "Start a daemon thread watching the directory containing anvil.edn
   for ENTRY_MODIFY / ENTRY_CREATE events. Calls `clear-cache!` when
   the file changes.

   Idempotent — calling twice is safe; the second call no-ops if a
   watcher is already running. No-op when no on-disk anvil.edn exists
   (classpath-bundled default isn't filesystem-watchable; operator
   gets the v0.5.x restart-to-reload contract in that case).

   Returns true if a watcher was started, false otherwise (already
   running, or no file to watch)."
  []
  (locking watcher-state
    (cond
      @watcher-state
      false

      :else
      (if-let [anvil-edn (resolve-anvil-edn-path)]
        (let [parent ^File (.getParentFile anvil-edn)
              parent-path ^Path (.toPath parent)
              ws (.newWatchService (FileSystems/getDefault))
              kinds (into-array WatchEvent$Kind
                                [StandardWatchEventKinds/ENTRY_MODIFY
                                 StandardWatchEventKinds/ENTRY_CREATE])
              _ (.register parent-path ws kinds)
              stop? (atom false)
              t (Thread. ^Runnable
                         (fn [] (run-watcher ws anvil-edn stop?))
                         "anvil-build-overrides-watcher")]
          (.setDaemon t true)
          (.start t)
          (reset! watcher-state {:thread t :watch-service ws :stop? stop?
                                 :watched-file (.getAbsolutePath anvil-edn)})
          (log/info (str "anvil.build-overrides: hot-reload watcher on "
                         (.getAbsolutePath anvil-edn)))
          true)
        (do (log/info "anvil.build-overrides: no on-disk anvil.edn; hot-reload disabled (classpath default in use)")
            false)))))

(defn stop-watcher!
  "Stop the file-watch thread, if any. Idempotent. Tests use this to
   tear down between fixtures."
  []
  (locking watcher-state
    (when-let [{:keys [^WatchService watch-service stop? ^Thread thread]} @watcher-state]
      (reset! stop? true)
      (try (.close watch-service) (catch Exception _ nil))
      (try (.interrupt thread) (catch Exception _ nil))
      (reset! watcher-state nil)
      true)))

(defn watcher-running?
  "Tests + ops: is the watcher attached?"
  []
  (some? @watcher-state))
