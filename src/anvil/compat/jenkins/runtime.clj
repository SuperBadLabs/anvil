(ns anvil.compat.jenkins.runtime
  "Pipeline DSL runtime for scripted `script {}` blocks.

   Embeds Groovy via JSR-223 + a custom Script base class with a
   `methodMissing` catch-all. The Pipeline DSL globals (sh, echo, dir,
   stash, deleteDir, junit, archiveArtifacts, …) are bound under '__name'
   prefixes so trailing-closure call syntax (`dir('foo') { ... }`)
   reaches methodMissing intact rather than being short-circuited by
   binding resolution.

   Each global is a Clojure-backed closure that emits a Jenkins IR step
   node and routes it through the dispatcher's `dispatch` — so side
   effects from scripted code interleave correctly with declarative
   side effects in the surrounding pipeline.

   Productized from `chengis.spike.script-block`. Three design lessons
   were captured in spike testing and are preserved here:
     1. GString ≠ String. Pipeline DSL adapters accept CharSequence and
        coerce with `str`. Groovy interpolation produces GStringImpl.
     2. groovy.lang.Closure has no `call(Object, Object)`. Multi-arg
        invocation goes through call(Object...) via MetaClass. The proxy
        in `anvil.compat.jenkins.groovy/clojure-fn->groovy-closure`
        detects an Object[] in the call(Object) overload and spreads.
     3. Pipeline DSL globals live under '__name' prefixed binding keys
        so methodMissing fires for the bare names (sh, dir, …) and
        trailing-closure syntax reaches it."
  (:require [anvil.compat.jenkins.groovy :as g]
            [chengis.engine.dispatcher :as d])
  (:import [groovy.lang GroovyShell GroovyClassLoader Binding Closure]
           [groovy.util Expando]
           [org.codehaus.groovy.control CompilerConfiguration]))

;; ---------------------------------------------------------------------------
;; JenkinsDSLBase — the Script base class with methodMissing catch-all
;; ---------------------------------------------------------------------------

(def ^:private base-class-source
  "package anvil.compat.jenkins.gen
   abstract class JenkinsDSLBase extends Script {
     Object methodMissing(String name, Object args) {
       def vars = binding.variables
       def closure = vars['__' + name]
       if (closure instanceof Closure) {
         // Pass args Object[] as a single arg; the Clojure-backed proxy
         // detects-and-spreads. (See spike #3 for why call(Object,Object)
         // isn't a real Closure method.)
         return closure.call((Object[]) args)
       }
       throw new MissingMethodException(name, this.getClass(), args as Object[])
     }
   }")

(def ^:private base-class
  ;; Compile once, cache for the lifetime of the JVM.
  (delay
    (let [gcl (GroovyClassLoader.)]
      (.parseClass gcl ^String base-class-source))))

(defn eval-with-dsl-base
  "Evaluate Groovy source with JenkinsDSLBase as the script base class.
   `bindings` is a map of String → Object; method-style entries should be
   keyed with the '__' prefix so methodMissing finds them."
  [^String src bindings]
  (let [base ^Class @base-class
        cc (doto (CompilerConfiguration.)
             (.setScriptBaseClass (.getName base)))
        gcl (GroovyClassLoader. (.getClassLoader base) cc)
        binding-obj (Binding.)
        _ (doseq [[k v] bindings] (.setVariable binding-obj (str k) v))
        shell (GroovyShell. gcl binding-obj cc)]
    (.evaluate shell src "JenkinsDSLScript.groovy")))

;; ---------------------------------------------------------------------------
;; Argument coercion helpers
;; ---------------------------------------------------------------------------

(defn coerce-map
  "Groovy named-arg calls (`sh(script:'foo', returnStdout:true)`) deliver a
   java.util.LinkedHashMap. Coerce to a Clojure map with keyword keys."
  [x]
  (when (instance? java.util.Map x)
    (into {} (for [[k v] x] [(keyword (str k)) v]))))

(defn coerce-string
  "Groovy GString (interpolated 'foo ${bar}') is GStringImpl, not String —
   `string?` rejects it. Use this in every adapter that accepts a 'string'
   argument."
  [x]
  (cond
    (nil? x)                       nil
    (instance? CharSequence x)     (str x)
    :else                          (str x)))

;; ---------------------------------------------------------------------------
;; Pipeline DSL globals
;;
;; Each global is a closure that takes the call arguments, builds an IR
;; step node, and dispatches through the supplied dispatcher. The dispatcher's
;; `:ctx` return is threaded back via the side-effects atom; the closure
;; itself returns whatever the IR-step result map carried (e.g. stdout for
;; returnStdout-style sh calls).
;; ---------------------------------------------------------------------------

(defn- emit
  "Build a step, dispatch it, update the ctx-atom from the result, return
   the value the script should see (stdout, nil, etc.).

   On step failure (`:status :failed` in the dispatch result), throw
   an ex-info so the exception bubbles up out of the Groovy script
   evaluator and h-script's outer try-catch can convert it to a
   :status :failed dispatcher result. Without this, a `script {
   sh 'exit 1' }` block was reported to run-pipeline as a successful
   wrapper step and failed builds finished green (Codex P1, PR #164).

   Steps that opted into non-throwing semantics (`:return-status?`,
   which models Jenkins's `sh(script:'…', returnStatus:true)`) DO NOT
   throw — the user has explicitly asked for the exit code regardless."
  [dispatcher ctx-atom step return-key]
  ;; Before dispatching, sync any scripted-side mutations to the Groovy
  ;; `env` Expando into ctx :env so subprocess execution sees them.
  ;; Codex P2 (PR #164): scripts that do `env.FOO = 'bar'` before
  ;; invoking `sh` would have those assignments ignored because the
  ;; Expando wasn't tied to ctx-atom.
  (when-let [^Expando env-obj (:env-expando @ctx-atom)]
    (let [scripted-env (into {} (for [[k v] (.getProperties env-obj)]
                                  [(str k) (str v)]))]
      (swap! ctx-atom update :env merge scripted-env)))
  (let [result (d/dispatch dispatcher step @ctx-atom)]
    (when-let [new-ctx (:ctx result)] (reset! ctx-atom new-ctx))
    (when (and (= :failed (:status result))
               (not (:return-status? step)))
      (throw (ex-info (or (:message result)
                          (str "anvil script-block step failed: "
                               (or (:error result) "<no error>")))
                      {:type :anvil/script-step-failure
                       :step (select-keys step [:type :script :name :args])
                       :error (:error result)
                       :exit (:exit result)})))
    (get result return-key)))

(defn- make-sh-closure [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [arg]
     (cond
       (instance? CharSequence arg)
       (emit dispatcher ctx-atom
             {:type :jenkins/sh :script (str arg)}
             :stdout)

       (instance? java.util.Map arg)
       (let [m (coerce-map arg)]
         (emit dispatcher ctx-atom
               (cond-> {:type :jenkins/sh
                        :script (coerce-string (:script m))}
                 (:returnStdout m) (assoc :return-stdout? true)
                 (:returnStatus m) (assoc :return-status? true)
                 (:label m)        (assoc :label (coerce-string (:label m))))
               (cond (:returnStatus m) :status-code
                     :else             :stdout)))

       :else
       (throw (ex-info "sh expected CharSequence or Map"
                       {:arg arg :class (some-> arg class .getName)}))))))

(defn- make-echo-closure [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [msg]
     (emit dispatcher ctx-atom
           {:type :jenkins/echo :message (coerce-string msg)}
           :_no-return))))

(defn- make-bat-closure [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [arg]
     (emit dispatcher ctx-atom
           {:type :jenkins/bat :script (coerce-string arg)}
           :stdout))))

(defn- make-leaf-closure
  "Helper: a closure that always emits a fixed step type with no return."
  [dispatcher ctx-atom step-type]
  (g/clojure-fn->groovy-closure
   (fn
     ([]       (emit dispatcher ctx-atom {:type step-type} :_no-return))
     ([arg]    (emit dispatcher ctx-atom
                     (let [m (coerce-map arg)]
                       (cond-> {:type step-type}
                         m       (assoc :args m)
                         (not m) (assoc :arg (coerce-string arg))))
                     :_no-return)))))

(defn- make-dir-closure
  "`dir(path) { body }` — open a scope wrapper, evaluate the body Groovy
   closure in the new cwd, restore. We push the cwd into ctx via a
   :jenkins/dir-enter pseudo-step so the dispatcher / observers see it."
  [dispatcher ctx-atom]
  (g/clojure-fn->groovy-closure
   (fn [path ^Closure body-closure]
     (let [old-cwd (:cwd @ctx-atom "/workspace")
           new-cwd (str old-cwd "/" (coerce-string path))]
       (emit dispatcher ctx-atom
             {:type :jenkins/dir-enter :path new-cwd}
             :_no-return)
       (swap! ctx-atom assoc :cwd new-cwd)
       (try
         (.call body-closure)
         (finally
           (emit dispatcher ctx-atom
                 {:type :jenkins/dir-leave :path new-cwd}
                 :_no-return)
           (swap! ctx-atom assoc :cwd old-cwd)))))))

;; ---------------------------------------------------------------------------
;; env Expando — Jenkins's magic env object with property get/set
;; ---------------------------------------------------------------------------

(defn make-env ^Expando []
  (Expando.))

(defn populate-env-with
  "Set the given key/value pairs on a Groovy Expando. Strings only."
  [^Expando env kv-map]
  (doseq [[k v] kv-map]
    (.setProperty env (name k) (str v)))
  env)

(defn env->clj
  "Snapshot an Expando into a Clojure map for inspection."
  [^Expando env]
  (into {}
        (for [[k v] (.getProperties env)]
          [(keyword (str k)) v])))

;; ---------------------------------------------------------------------------
;; The DSL binding map — assembled per script-block invocation
;; ---------------------------------------------------------------------------

(defn make-dsl-bindings
  "Build the Groovy binding map. `dispatcher` is the StepDispatcher
   instance that will receive all step calls; `ctx-atom` is a Clojure
   atom that carries pipeline context (env, cwd, build metadata) between
   step calls; `env-vars` is an initial env-var seed map for the Expando."
  [dispatcher ctx-atom env-vars]
  (let [env (populate-env-with (make-env) env-vars)
        ;; Tie the Expando back to ctx-atom so emit can pick up scripted
        ;; `env.FOO = 'bar'` mutations before each step dispatch
        ;; (Codex P2, PR #164).
        _ (swap! ctx-atom assoc :env-expando env)
        leaf (fn [t] (make-leaf-closure dispatcher ctx-atom t))]
    {;; Core shell + IO
     "__sh"        (make-sh-closure dispatcher ctx-atom)
     "__bat"       (make-bat-closure dispatcher ctx-atom)
     "__echo"      (make-echo-closure dispatcher ctx-atom)
     ;; Scope wrappers
     "__dir"       (make-dir-closure dispatcher ctx-atom)
     ;; Leaf steps
     "__deleteDir" (leaf :jenkins/delete-dir)
     "__cleanWs"   (leaf :jenkins/delete-dir)        ; cleanWs is an alias
     "__junit"     (leaf :jenkins/junit)
     "__archiveArtifacts" (leaf :jenkins/archive-artifacts)
     "__stash"     (leaf :jenkins/stash)
     "__unstash"   (leaf :jenkins/unstash)
     "__checkout"  (leaf :jenkins/checkout)
     "__mail"      (leaf :jenkins/mail)
     "__emailext"  (leaf :jenkins/emailext)
     "__writeFile" (leaf :jenkins/write-file)
     "__readFile"  (leaf :jenkins/read-file)
     "__build"     (leaf :jenkins/build)
     "__error"     (leaf :jenkins/error)
     "__sleep"     (leaf :jenkins/sleep)
     ;; Property-access object
     "env"         env}))

;; ---------------------------------------------------------------------------
;; Public entry: run a script {} body
;; ---------------------------------------------------------------------------

(defn run-script-block
  "Execute Groovy source `body-source` (the body of a `script {}` block)
   with Pipeline DSL globals pre-bound to dispatch through `dispatcher`.

   ctx-atom is updated in place by the closures; on return it holds the
   final pipeline context. Returns the Groovy script's final expression
   value (commonly nil)."
  [body-source dispatcher ctx-atom]
  (let [env-vars (get @ctx-atom :env {})
        bindings (make-dsl-bindings dispatcher ctx-atom env-vars)]
    (eval-with-dsl-base body-source bindings)))
