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

   ## Load semantics

   Lazy: first call reads `anvil.edn` once and caches under a delay. No
   hot-reload — operator restarts the daemon after editing overrides
   (same contract as agents.edn / libraries.edn).

   Tests can call `reset!` to clear the cache."
  (:require [anvil.config :as config]))

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
   this between fixtures; operators restart the daemon (the cache won't
   re-read on its own without a process restart)."
  []
  (reset! cache nil))
