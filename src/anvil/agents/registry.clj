(ns anvil.agents.registry
  "TX11C — Labeled-agent registry.

   Scripted Pipelines pin work to labeled executors:

     node('maven-21') { sh 'mvn install' }

   This namespace resolves the label string into an executor config —
   what env vars to export, what cwd to default to, whether the label
   is degraded (i.e. we're substituting a different executor with a
   warning).

   The default config ships at `resources/anvil/config/agents.edn`.
   Operators override via `$ANVIL_CONFIG_DIR/agents.edn` or
   `./config/agents.edn` in the daemon's working dir.

   Resolution policy:
     - exact label match in :labels  → use that entry
     - no match                       → use :default with a [warn] log
                                         and a {:fallback-from STRING}
                                         marker on the returned config
     - empty/nil label                → :default silently

   Returned shape:
     {:executor   :local | :docker
      :label      STRING
      :env        {STRING STRING ...}     ;; export-as-environment
      :cwd        STRING                  ;; default workspace
      :degraded?  bool                    ;; set on substitution
      :degrade-reason STRING?             ;; if degraded
      :fallback-from STRING?              ;; if unmatched
      :raw        <original entry, for debug>}

   Caching: agents.edn is read at first access via delay so reload =
   process restart. Use `(reset-cache!)` from a REPL or test to force
   re-read."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [anvil.config :as cfg]))

;; ---------------------------------------------------------------------------
;; Cache + reload
;; ---------------------------------------------------------------------------

(def ^:private registry-cache
  (atom nil))

(defn- load-registry! []
  (cfg/load-edn "agents"
                {:default {:executor :local :env {}}
                 :labels  {}}))

(defn registry
  "Force-loaded registry data. Reads agents.edn on first call."
  []
  (or @registry-cache
      (let [loaded (load-registry!)]
        (reset! registry-cache loaded)
        loaded)))

(defn reset-cache!
  "Drop the cached registry so the next access re-reads agents.edn.
   Used by tests + REPL operators."
  []
  (reset! registry-cache nil))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(defn- merge-defaults [default entry label]
  (cond-> {:executor (or (:executor entry) (:executor default) :local)
           :label    label
           :env      (merge (or (:env default) {}) (or (:env entry) {}))
           :cwd      (or (:cwd entry) (:cwd default) "/tmp/anvil-workspace")
           :raw      entry}
    (:degraded? entry)
    (assoc :degraded? true
           :degrade-reason (or (:degrade-reason entry)
                               "Label flagged as degraded in agents.edn"))))

(defn resolve-label
  "Look up `label` in the registry. Returns a fully-merged executor
   config (see ns docstring for shape). On miss, returns the default
   with `:fallback-from` set and logs a warning at WARN level."
  [label]
  (let [reg (registry)
        defaults (:default reg)
        labels (:labels reg)
        clean (some-> label str/trim)]
    (cond
      (str/blank? clean)
      (merge-defaults defaults defaults "<unspecified>")

      (contains? labels clean)
      (merge-defaults defaults (get labels clean) clean)

      :else
      (do (log/warn (str "anvil agent registry: no entry for label "
                         (pr-str clean) " — falling back to :default. "
                         "Add an entry to agents.edn to silence this warning."))
          (-> (merge-defaults defaults defaults "<fallback>")
              (assoc :fallback-from clean
                     :degraded? true
                     :degrade-reason
                     (str "no agents.edn entry for label " (pr-str clean))))))))

(defn known-labels
  "List of label strings currently registered. Useful for the admin UI
   and `anvil agents list` CLI."
  []
  (vec (sort (keys (:labels (registry))))))
