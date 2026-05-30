(ns anvil.compat.jenkins.plugins
  "Plugin SDK — extension point for community / user-contributed step adapters.

   Anvil ships a fixed set of built-in step adapters (TX4) and a small set
   of plugin-step placeholders (TX4 second half). Real Jenkins has a
   1,800-plugin long tail; anvil can't ship adapters for all of them, but
   it CAN expose an extension point so the long tail is reachable.

   Two consumers of this registry:
     1. Users who want to add an adapter for a Jenkins plugin step their
        Jenkinsfiles depend on, without modifying anvil source.
     2. The shared-library system (also TX5) — when a `@Library` import
        resolves, its `vars/*.groovy` files each become a callable
        Jenkins step. We register each one as a plugin adapter.

   Lifecycle: adapters register at startup (or anytime before dispatch).
   The dispatcher consults this registry FIRST when it sees a step type
   it doesn't natively handle, falling through to :jenkins/unsupported
   only if no adapter is registered.

   Idempotency: registering the same step name twice replaces the earlier
   adapter. (Last-wins; users who care can wrap.)

   Thread safety: the registry is an atom holding a plain map. Reads are
   lock-free. Writes are CAS via swap!. Intended for boot-time
   registration with occasional hot-reloading."
  (:require [clojure.string :as str]))

(defprotocol StepAdapter
  "A plugin step adapter. Implementations must be idempotent on registration
   and pure-ish during execution (side effects go through the dispatcher
   passed in ctx, not via global state)."

  (adapter-name [this]
    "Short human-readable name used in logs/diagnostics, e.g. \"warnings-ng\".")

  (step-names [this]
    "Set of Jenkins step names this adapter handles, e.g.
     #{\"recordIssues\" \"discoverGitReferenceBuild\"}.")

  (execute-step [this step ctx]
    "Execute the step. `step` is the IR node (will be of type :jenkins/unknown
     with `:name` matching one of `step-names`). `ctx` is the execution
     context map. Return shape matches the StepDispatcher dispatch return:
       {:status :ok|:failed :output STRING? :ctx CTX :error KW?}"))

;; ---------------------------------------------------------------------------
;; The registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn register!
  "Register a StepAdapter. Each step name it claims gets routed to it.

   Returns the adapter for chaining."
  [^anvil.compat.jenkins.plugins.StepAdapter adapter]
  (let [names (step-names adapter)]
    (assert (set? names) "step-names must return a set")
    (assert (every? string? names) "step-names entries must be strings")
    (swap! registry
           (fn [m]
             (reduce (fn [acc nm] (assoc acc nm adapter)) m names)))
    adapter))

(defn unregister!
  "Remove a step-name registration. Useful for tests and hot-reload."
  [step-name]
  (swap! registry dissoc step-name)
  nil)

(defn clear-registry!
  "Remove every registration. Useful for tests."
  []
  (reset! registry {})
  nil)

(defn registered-step-names
  "Set of step names currently routable to a plugin adapter."
  []
  (set (keys @registry)))

(defn find-adapter
  "Return the StepAdapter registered to handle `step-name`, or nil."
  [step-name]
  (get @registry step-name))

(defn dispatch-step
  "Look up an adapter for the step's `:name` and call it. Returns nil if
   no adapter is registered (caller falls back to its default
   :jenkins/unsupported handler).

   Step is expected to be :jenkins/unknown with :name set."
  [step ctx]
  (when-let [adapter (find-adapter (:name step))]
    (execute-step adapter step ctx)))

;; ---------------------------------------------------------------------------
;; A convenience: build an adapter from a fn
;; ---------------------------------------------------------------------------

(defn ^:no-doc fn-adapter*
  [name names-set f]
  (reify StepAdapter
    (adapter-name [_] name)
    (step-names   [_] names-set)
    (execute-step [_ step ctx] (f step ctx))))

(defn fn-adapter
  "Build a StepAdapter from `(fn [step ctx])`. Convenient for one-off
   adapters or to wrap a closure that captures plugin configuration.

     (plugins/register! (plugins/fn-adapter \"slack-x\"
                                             #{\"slackSend\"}
                                             my-slack-handler))"
  [adapter-name step-names handler]
  (assert (string? adapter-name))
  (assert (set? step-names))
  (assert (fn? handler))
  (fn-adapter* adapter-name step-names handler))

;; ---------------------------------------------------------------------------
;; Diagnostics
;; ---------------------------------------------------------------------------

(defn describe-registry
  "Human-readable summary of the current registry. Used by the CLI's
   `anvil plugins list` (when that lands) and by error messages."
  []
  (let [m @registry
        by-adapter (group-by #(adapter-name (val %)) m)]
    (with-out-str
      (println (str "Plugin registry — " (count m) " step name(s) registered"))
      (doseq [[adapter-nm entries] (sort-by key by-adapter)]
        (println (str "  " adapter-nm))
        (doseq [[step-nm _] (sort-by key entries)]
          (println (str "    - " step-nm)))))))
