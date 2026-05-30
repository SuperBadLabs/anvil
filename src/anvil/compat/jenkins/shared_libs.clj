(ns anvil.compat.jenkins.shared-libs
  "TX11D — Shared library resolver.

   Jenkins's `@Library('infra')`-style global pipeline libraries
   expose Groovy classes whose methods are called from Jenkinsfile
   bodies:

     infra.checkoutSCM()
     infra.runWithMaven(args, jdk, extraArgs)
     infra.publishReports([…])
     infra.maybePublishIncrementals()
     infra.withArtifactCachingProxy { … }

   On ci.jenkins.io these are backed by real Groovy classes shipped in
   the jenkins-infra/pipeline-library repo. Anvil v1 ships built-in
   shims for the most common library calls (the `infra.*` set this
   namespace covers), enough to run jenkinsci/jenkins's own
   Jenkinsfile end-to-end.

   Mechanism: TX11A's translator records unknown method calls as
   `{:type :jenkins/unknown :name STRING :args [...]}`. When the
   dispatcher hits one of those, it consults this registry. If the
   name matches a registered shim, the dispatcher dispatches the
   appropriate handler (with body re-walking for wrappers like
   withArtifactCachingProxy). Unmatched unknowns log + no-op as
   before.

   Operators can override shims via `anvil/config/libraries.edn`:
     {:overrides {\"checkoutSCM\" {:type :git-clone
                                    :url \"git@github.com:foo/bar.git\"
                                    :rev \"main\"}}}
   For v1's smoke build, the default `infra.checkoutSCM` is a NO-OP
   because the source tree is already checked out on disk — the
   anvil dispatcher cwd points at the repo."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [anvil.config :as cfg]
            [anvil.compat.jenkins.shared-libs.infra :as infra]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private builtin-handlers
  "Map of (lowercase) method name → handler fn.  Each handler has the
   signature `(handler step ctx)` and returns either:
     - nil                       — no-op; effect already logged
     - {:effect KW :args …}      — single recorded effect tuple
     - {:wrap-body? true
        :body STEPS}             — instruct dispatcher to run STEPS
                                    with the same ctx (the wrapper
                                    pattern; sees withArtifactCachingProxy)"
  {"checkoutscm"                 #'infra/checkout-scm
   "runwithmaven"                #'infra/run-with-maven
   "runmaven"                    #'infra/run-maven
   "publishreports"              #'infra/publish-reports
   "maybepublishincrementals"    #'infra/maybe-publish-incrementals
   "withartifactcachingproxy"    #'infra/with-artifact-caching-proxy
   "retainonlypeerproducedhashes" #'infra/retain-only-peer-produced-hashes
   ;; TX11E: degrade-with-warn shims for retry condition predicates and
   ;; misc plugin calls the Jenkinsfile uses but anvil isn't equipped
   ;; to honor properly. Each one logs that it was shimmed.
   "kubernetesagent"             #'infra/no-op-true-predicate
   "nonresumable"                #'infra/no-op-true-predicate
   "agent"                       #'infra/no-op-true-predicate
   "discovergitreferencebuild"   #'infra/no-op
   "launchableremembercaptured"  #'infra/no-op})

(defn- overrides-config []
  (or (:overrides (cfg/load-edn "libraries" {})) {}))

(defn handler-for
  "Resolve a step-name to a shared-library handler. Case-insensitive
   match — Groovy method names are camelCase in the Jenkinsfile but
   the shim registry normalizes everything to lowercase. Returns the
   handler fn, or nil for no match."
  [^String step-name]
  (when step-name
    (or (when-let [override (get (overrides-config) step-name)]
          ;; Operator override returns a constant effect; wrap as a handler
          (fn [_step _ctx] (assoc override :overridden? true :step-name step-name)))
        (get builtin-handlers (str/lower-case step-name)))))

(defn known-names
  "List of recognized shared-library method names. Used by the admin
   UI 'what shims are loaded' page."
  []
  (vec (sort (keys builtin-handlers))))
