(ns anvil.compat.jenkins.groovy
  "Groovy AST + JSR-223 ScriptEngine helpers.

   Provides the parsing layer for Jenkinsfile sources. The translator
   namespace walks the AST via the :cdata view produced here.

   The script {} block runtime (TX4) reuses `script-engine` and
   `clojure-fn->groovy-closure` to evaluate scripted-pipeline code with
   Pipeline DSL globals pre-bound as Clojure-backed closures.

   Productized from `chengis.spike.groovy-embed` (4 spikes, 24 tests / 90
   assertions verified the embedding works end-to-end before this lift)."
  (:import [javax.script ScriptEngineManager ScriptEngine SimpleBindings]
           [org.codehaus.groovy.ast.builder AstBuilder]
           [org.codehaus.groovy.control CompilePhase]
           [org.codehaus.groovy.ast ASTNode ModuleNode ClassNode MethodNode]
           [org.codehaus.groovy.ast.stmt BlockStatement Statement ExpressionStatement]
           [org.codehaus.groovy.ast.expr MethodCallExpression ConstantExpression
            ClosureExpression ArgumentListExpression
            TupleExpression VariableExpression
            ListExpression MapExpression MapEntryExpression
            GStringExpression]))

;; ---------------------------------------------------------------------------
;; JSR-223 ScriptEngine — used by the runtime (TX4) to eval script {} blocks.
;; ---------------------------------------------------------------------------

(defn script-engine
  "Return a Groovy ScriptEngine instance. Throws if Groovy isn't registered."
  ^ScriptEngine []
  (let [mgr (ScriptEngineManager.)
        engine (.getEngineByName mgr "groovy")]
    (when-not engine
      (throw (ex-info "Groovy ScriptEngine not registered"
                      {:available-engines
                       (mapv #(into [] (.getNames %)) (.getEngineFactories mgr))})))
    engine))

(defn eval-groovy
  "Evaluate a Groovy source string with a binding map of String → Object."
  ([src] (eval-groovy src {}))
  ([src bindings]
   (let [engine (script-engine)
         b (SimpleBindings.)]
     (doseq [[k v] bindings] (.put b (str k) v))
     (.eval engine ^String src b))))

;; ---------------------------------------------------------------------------
;; Clojure → Groovy closure bridge.
;;
;; Groovy's Closure has only call(), call(Object), and call(Object...).
;; The varargs form routes through MetaClass; multi-arg invocations end up
;; arriving here as a single Object[]. We detect-and-spread in call(Object)
;; so the wrapped Clojure fn sees the right arity.
;;
;; Design lesson preserved from spike #3:
;;   - GString ≠ String; Pipeline DSL adapters must accept CharSequence
;;     and coerce with `str`.
;;   - Method-style DSL globals (sh, dir, …) get stored under '__name'
;;     prefixed binding keys so trailing-closure syntax `dir('foo') { ... }`
;;     reaches methodMissing intact.
;; ---------------------------------------------------------------------------

(defn clojure-fn->groovy-closure
  "Wrap a Clojure IFn in a groovy.lang.Closure so Groovy can call it."
  [f]
  (proxy [groovy.lang.Closure] [nil]
    (call
      ([]
       (f))
      ([a]
       (cond
         (nil? a)              (f)
         (.isArray (class a))  (apply f (vec a))
         :else                 (f a)))
      ([a b] (f a b))
      ([a b c] (f a b c))
      ([a b c d] (f a b c d)))))

;; ---------------------------------------------------------------------------
;; AST parsing
;; ---------------------------------------------------------------------------

(defn parse-groovy-ast
  "Parse Groovy source into a sequence of top-level AST nodes (typically
   one BlockStatement). Uses CompilePhase/CONVERSION — after syntactic
   parsing but before type inference, the right level for declarative-
   pipeline structure extraction."
  [^String src]
  (let [b (AstBuilder.)]
    (.buildFromString b CompilePhase/CONVERSION true src)))

;; ---------------------------------------------------------------------------
;; AST → :cdata view
;;
;; Translates the Groovy AST node graph into Clojure data so subsequent
;; passes can use sequence/map operations. The shape:
;;
;;   {:type :call    :name STRING  :args [cdata ...]}
;;   {:type :closure :body [cdata ...]}
;;   {:type :const   :value VAL}
;;   {:type :var     :name STRING}
;;   {:type :other   :class STRING :text STRING}
;;
;; ClosureExpression instances are preserved in a separate side-channel
;; (closure-cdata→ClosureExpression map) so the translator can recover
;; line numbers later for source-region extraction of `script {}` bodies.
;; ---------------------------------------------------------------------------

(declare ->cdata)

(defn- closure-body-statements [^ClosureExpression closure]
  (let [code (.getCode closure)]
    (cond
      (instance? BlockStatement code) (.getStatements ^BlockStatement code)
      :else                            [code])))

(defn- statement->call [^Statement stmt]
  (when (instance? ExpressionStatement stmt)
    (let [expr (.getExpression ^ExpressionStatement stmt)]
      (when (instance? MethodCallExpression expr)
        expr))))

(defn- method-name [^MethodCallExpression call]
  (let [m (.getMethod call)]
    (cond
      (instance? ConstantExpression m) (str (.getValue ^ConstantExpression m))
      :else (str m))))

(defn- arg-summary [^MethodCallExpression call]
  (let [args (.getArguments call)]
    (cond
      (instance? ArgumentListExpression args)
      (mapv ->cdata (.getExpressions ^ArgumentListExpression args))

      (instance? TupleExpression args)
      (mapv ->cdata (.getExpressions ^TupleExpression args))

      :else
      [(->cdata args)])))

(defn ->cdata
  "Translate one Groovy AST node into the Clojure :cdata view."
  [node]
  (cond
    (instance? MethodCallExpression node)
    (let [^MethodCallExpression mc node]
      {:type :call
       :name (method-name mc)
       :args (arg-summary mc)})

    (instance? ClosureExpression node)
    {:type :closure
     :body (mapv (fn [^Statement s]
                   (if-let [call (statement->call s)]
                     (->cdata call)
                     {:type :statement
                      :class (.getName (class s))
                      :text (str s)}))
                 (closure-body-statements node))}

    (instance? ConstantExpression node)
    {:type :const :value (.getValue ^ConstantExpression node)}

    (instance? VariableExpression node)
    {:type :var :name (.getName ^VariableExpression node)}

    (instance? ListExpression node)
    {:type :list
     :items (mapv ->cdata (.getExpressions ^ListExpression node))}

    (instance? MapExpression node)
    {:type :map
     :entries (mapv (fn [^MapEntryExpression e]
                      {:key (->cdata (.getKeyExpression e))
                       :val (->cdata (.getValueExpression e))})
                    (.getMapEntryExpressions ^MapExpression node))}

    (instance? GStringExpression node)
    {:type :gstring :text (str (.getText ^GStringExpression node))}

    :else
    ;; :line-start / :line-end / :col-start / :col-end let consumers
    ;; (the echo translator most importantly) extract the original
    ;; source span for nodes like BinaryExpression / PropertyExpression /
    ;; GStringExpression-with-property-access that we don't reduce to a
    ;; literal in cdata. Without these positions, `(:text node)` is the
    ;; Java AST .toString() which is decidedly NOT what should land in
    ;; a build console.
    (let [line-s (when (instance? org.codehaus.groovy.ast.ASTNode node)
                   (.getLineNumber ^org.codehaus.groovy.ast.ASTNode node))
          line-e (when (instance? org.codehaus.groovy.ast.ASTNode node)
                   (.getLastLineNumber ^org.codehaus.groovy.ast.ASTNode node))
          col-s  (when (instance? org.codehaus.groovy.ast.ASTNode node)
                   (.getColumnNumber ^org.codehaus.groovy.ast.ASTNode node))
          col-e  (when (instance? org.codehaus.groovy.ast.ASTNode node)
                   (.getLastColumnNumber ^org.codehaus.groovy.ast.ASTNode node))]
      (cond-> {:type :other :class (.getName (class node)) :text (str node)}
        (and line-s (pos? line-s)) (assoc :line-start line-s :line-end line-e
                                          :col-start col-s :col-end col-e)))))

(defn statement->call-public
  "Public wrapper around `statement->call` for use from translator.clj.
   Returns the inner MethodCallExpression or nil."
  [stmt]
  (statement->call stmt))

(defn method-name-public
  "Public wrapper for method-name."
  [call]
  (method-name call))

;; ---------------------------------------------------------------------------
;; Closure source-region recovery for `script {}` body extraction.
;; ---------------------------------------------------------------------------

(defn collect-closure-expressions
  "Walk a sequence of AST nodes and return a flat vector of every
   ClosureExpression encountered, in source order. Pairs with
   `collect-cdata-closures` (in translator) to map :cdata closure nodes
   back to their originating ClosureExpression — which lets us pull
   line numbers for script-block body extraction."
  [ast-nodes]
  (let [out (volatile! [])]
    (letfn [(walk [node]
              (when (instance? ClosureExpression node)
                (vswap! out conj node))
              (cond
                (instance? BlockStatement node)
                (doseq [s (.getStatements ^BlockStatement node)] (walk s))

                (instance? ExpressionStatement node)
                (walk (.getExpression ^ExpressionStatement node))

                (instance? MethodCallExpression node)
                (walk (.getArguments ^MethodCallExpression node))

                (instance? ArgumentListExpression node)
                (doseq [e (.getExpressions ^ArgumentListExpression node)] (walk e))

                (instance? TupleExpression node)
                (doseq [e (.getExpressions ^TupleExpression node)] (walk e))

                (instance? ClosureExpression node)
                (walk (.getCode ^ClosureExpression node))))]
      (doseq [n ast-nodes] (walk n))
      @out)))

(defn flatten-top-statements
  "AstBuilder wraps the program in a top-level BlockStatement. Flatten
   one level to get back the top-level statements (which include the
   `pipeline {}` MethodCallExpression and any helper defs)."
  [ast-nodes]
  (mapcat (fn [n]
            (if (instance? BlockStatement n)
              (.getStatements ^BlockStatement n)
              [n]))
          ast-nodes))
