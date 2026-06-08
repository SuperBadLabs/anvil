(ns anvil.parameters-defaults
  "AN8-2 — operator-side per-job parameter defaults.

   Sits between the Jenkinsfile-declared `parameters { choice(...) }`
   defaults (which the translator extracts statically) and the
   runtime-supplied parameters (REST trigger payload, build form
   submission). Resolution order at build-start time:

     1. Translator defaults (the Jenkinsfile)
     2. anvil.edn :anvil.parameters/defaults <job-name> (this ns)
     3. Runtime parameters from the trigger payload (REST/form/webhook)

   Each later source wins on key conflict — the operator overrides
   the Jenkinsfile; the runtime trigger overrides both.

   ## Config shape

   In `anvil.edn`:

     {:anvil.parameters/defaults
        {\"wild-apache-activemq\"
           {\"nodeLabel\"  \"ubuntu\"
            \"jdkVersion\" \"jdk_17_latest\"}
         \"wild-apache-camel\"
           {\"PLATFORM_FILTER\" \"all\"
            \"JDK_FILTER\"      \"jdk_17_latest\"}}}

   The shape mirrors `:anvil.build-overrides` from AN7-5b so operators
   manage them side-by-side in the same file."
  (:require [anvil.config :as config]))

(def ^:private cache (atom nil))

(defn- load-defaults []
  (or (get (config/load-edn "anvil" {}) :anvil.parameters/defaults)
      {}))

(defn all
  "Return the full {job-name → {param-name → value}} map. Lazy-loads
   from anvil.edn on first call."
  []
  (or @cache
      (swap! cache (fn [v] (or v (load-defaults))))))

(defn for-job
  "Return the operator-side {param-name → value} map for `job-name`,
   or nil if no defaults are configured for that job."
  [job-name]
  (when job-name
    (get (all) job-name)))

(defn clear-cache!
  "Drop the cached map so the next call re-reads anvil.edn."
  []
  (reset! cache nil))

(defn resolve-build-parameters
  "Merge translator-extracted defaults, operator-side defaults, and
   runtime-supplied parameters per the AN8-2 resolution order.

     translator-defaults < operator-defaults < runtime-params

   All maps are String → String. Missing inputs are treated as
   empty. Returns the merged map; the caller threads this into
   ctx :parameters.

   When `pipeline-ir` is nil (e.g. a pure-scripted Jenkinsfile that
   never reached the static `parameters {}` translator), translator
   defaults are an empty map and operator/runtime layers behave the
   same way."
  [{:keys [translator-defaults operator-defaults runtime-params]
    :or {translator-defaults {} operator-defaults {} runtime-params {}}}]
  (merge {}
         (or translator-defaults {})
         (or operator-defaults {})
         (or runtime-params {})))
