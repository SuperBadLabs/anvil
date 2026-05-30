(ns anvil.compat.jenkins.matrix-expander
  "TX11B — Runtime-deferred matrix expansion for scripted Pipeline.

   Jenkinsfiles like jenkinsci/jenkins's own build their stage matrix
   dynamically at runtime via Groovy collection combinators:

     def axes = [platforms: ['linux', 'windows'], jdks: [21, 25]]
     axes.values().combinations { it ->
       def (platform, jdk) = it
       if (platform == 'windows' && jdk != axes.jdks.last()) {
         return        // skip this combination
       }
       builds[\"${platform}-jdk${jdk}\"] = {
         stage(\"${platform.capitalize()} - JDK ${jdk} - Checkout\") { … }
         stage(\"${platform.capitalize()} - JDK ${jdk} - Build / Test\") { … }
         stage(\"${platform.capitalize()} - JDK ${jdk} - Publish\") { … }
       }
     }
     parallel builds

   TX11A's translator finds the literal `stage(…)` MCEs nested inside
   the combinations closure — but it records each one once, with the
   unresolved GString as the stage name. That's wrong: each templated
   stage should expand to N concrete stages, one per surviving axis
   combination.

   This namespace bolts onto the back of `translator/parse`:

     1. Walk the top-level AST collecting `def X = <map-literal>`
        declarations into a binding map.
     2. Find every `<expr>.values().combinations { closure }` MCE
        anywhere in the script.
     3. For each combinations call:
          a. Resolve `<expr>` against bindings → a list of axis lists.
          b. Cartesian-product the axis lists → all combinations.
          c. For each combination, evaluate the closure with `it`
             bound to the tuple. Honour `def (a, b) = it` destructuring
             and `if (cond) { return }` filter guards.
          d. Walk the surviving closure body for nested `stage(name) {…}`
             calls. Interpolate each stage's GString name against the
             current binding to produce a concrete stage name.
     4. Return an updated IR: stages with unresolved templated names
        (i.e. those whose name still contains `${`) get DROPPED;
        the expanded concrete stages are appended in their place.

   What v1 does NOT yet do (deferred to TX11C/D):
     - Re-interpolating GStrings inside step bodies (sh, echo, etc.)
     - Resolving `builds[gstring-key] = { … }` keys into a `parallel`
       block name (the inner closure body still expands; the key just
       isn't tracked for parallel-name purposes)
     - Handling `for (x in y.combinations())` form
     - Evaluating arbitrary Groovy expression nodes the small evaluator
       below doesn't recognize — those fall through and we keep the
       templated stage unchanged."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [anvil.compat.jenkins.groovy :as g])
  (:import [org.codehaus.groovy.ast ASTNode CodeVisitorSupport GroovyCodeVisitor]
           [org.codehaus.groovy.ast.expr ArgumentListExpression
            BinaryExpression
            BooleanExpression
            ClosureExpression
            ConstantExpression
            DeclarationExpression
            GStringExpression
            ListExpression
            MapExpression
            MapEntryExpression
            MethodCallExpression
            NotExpression
            PropertyExpression
            TupleExpression
            VariableExpression]
           [org.codehaus.groovy.ast.stmt BlockStatement
            EmptyStatement
            ExpressionStatement
            IfStatement
            ReturnStatement
            Statement]
           [org.codehaus.groovy.syntax Token Types]))

;; ---------------------------------------------------------------------------
;; Cartesian product
;; ---------------------------------------------------------------------------

(defn- cartesian
  "Cartesian product of a vector of vectors. Returns a lazy seq of
   vectors, mirroring Groovy's List.combinations() semantics:
     [[1 2] [\"a\" \"b\"]] → [[1 \"a\"] [1 \"b\"] [2 \"a\"] [2 \"b\"]]"
  [colls]
  (if (empty? colls)
    [[]]
    (for [head (first colls)
          tail (cartesian (rest colls))]
      (vec (cons head tail)))))

;; ---------------------------------------------------------------------------
;; AST helpers
;; ---------------------------------------------------------------------------

(defn- variable-expr? [x] (instance? VariableExpression x))
(defn- constant-expr? [x] (instance? ConstantExpression x))
(defn- list-expr?     [x] (instance? ListExpression x))
(defn- map-expr?      [x] (instance? MapExpression x))
(defn- mce?           [x] (instance? MethodCallExpression x))
(defn- gstring-expr?  [x] (instance? GStringExpression x))
(defn- closure-expr?  [x] (instance? ClosureExpression x))
(defn- prop-expr?     [x] (instance? PropertyExpression x))

(defn- mce-args [^MethodCallExpression mc]
  (let [a (.getArguments mc)]
    (cond
      (instance? ArgumentListExpression a) (vec (.getExpressions ^ArgumentListExpression a))
      (instance? TupleExpression a)         (vec (.getExpressions ^TupleExpression a))
      :else                                  [a])))

(defn- mce-method-name [^MethodCallExpression mc]
  (let [m (.getMethod mc)]
    (when (instance? ConstantExpression m)
      (str (.getValue ^ConstantExpression m)))))

(defn- mce-object [^MethodCallExpression mc]
  (.getObjectExpression mc))

(defn- mce-closure-arg [^MethodCallExpression mc]
  (some #(when (closure-expr? %) %) (mce-args mc)))

(defn- closure-statements [^ClosureExpression c]
  (let [code (.getCode c)]
    (cond
      (instance? BlockStatement code) (.getStatements ^BlockStatement code)
      :else                            [code])))

;; ---------------------------------------------------------------------------
;; Resolve an AST literal to a Clojure value
;;
;; This is a tiny, deliberately limited evaluator. It handles only what's
;; needed for matrix axes + filter conditions in real-world Jenkinsfiles:
;; constants, list literals, map literals (with constant keys), property
;; access against the binding, method calls .first()/.last()/.size()/
;; .values()/.keys()/.capitalize(), binary == != && || comparisons, !
;; negation, GString interpolation.
;; ---------------------------------------------------------------------------

(declare groovy-eval)

(defn- eval-method [obj method args]
  (case method
    "first"      (first obj)
    "last"       (last obj)
    "size"       (count obj)
    "isEmpty"    (empty? obj)
    "values"     (vals obj)
    "keys"       (keys obj)
    "keySet"     (set (keys obj))
    "toString"   (str obj)
    "capitalize" (when (string? obj)
                   (if (empty? obj)
                     obj
                     (str (Character/toUpperCase ^char (first obj)) (subs obj 1))))
    "toLowerCase" (when (string? obj) (str/lower-case obj))
    "toUpperCase" (when (string? obj) (str/upper-case obj))
    "trim"       (when (string? obj) (str/trim obj))
    "contains"   (cond
                   (string? obj) (str/includes? obj (str (first args)))
                   (coll?   obj) (contains? (set obj) (first args)))
    "equals"     (= obj (first args))
    ;; Unknown method: signal "can't evaluate" via ::unknown
    ::unknown))

(defn- eval-binary [op left right]
  (case op
    "==" (= left right)
    "!=" (not= left right)
    "<"  (< (compare left right) 0)
    ">"  (> (compare left right) 0)
    "<=" (<= (compare left right) 0)
    ">=" (>= (compare left right) 0)
    "&&" (and left right)
    "||" (or left right)
    "+"  (cond
           (and (number? left) (number? right)) (+ left right)
           (and (string? left) (string? right)) (str left right)
           :else (str left right))
    "-"  (when (and (number? left) (number? right)) (- left right))
    "*"  (when (and (number? left) (number? right)) (* left right))
    ::unknown))

(defn- token-op
  "Try to extract a binary operator symbol from a Groovy Token, falling
   back to ::unknown if we don't recognize it."
  [^Token tok]
  (when tok
    (let [t (.getText tok)]
      (or (#{"==" "!=" "<" ">" "<=" ">=" "&&" "||" "+" "-" "*"} t)
          ::unknown))))

(defn- groovy-eval
  "Evaluate a small subset of Groovy expressions against `bindings`
   (a map of name → Clojure value). Returns the value, or ::unknown if
   we don't understand the expression."
  [expr bindings]
  (cond
    (nil? expr) nil

    (constant-expr? expr)
    (.getValue ^ConstantExpression expr)

    (variable-expr? expr)
    (let [n (.getName ^VariableExpression expr)]
      (cond
        (contains? bindings n) (get bindings n)
        (= n "true")           true
        (= n "false")          false
        (= n "null")           nil
        :else                  ::unknown))

    (list-expr? expr)
    (let [items (mapv #(groovy-eval % bindings)
                      (.getExpressions ^ListExpression expr))]
      (if (some #(= ::unknown %) items) ::unknown items))

    (map-expr? expr)
    (let [pairs (for [^MapEntryExpression me (.getMapEntryExpressions ^MapExpression expr)
                      :let [k (groovy-eval (.getKeyExpression me) bindings)
                            v (groovy-eval (.getValueExpression me) bindings)]]
                  [k v])]
      (if (some (fn [[k v]] (or (= ::unknown k) (= ::unknown v))) pairs)
        ::unknown
        (into {} pairs)))

    (prop-expr? expr)
    (let [obj (groovy-eval (.getObjectExpression ^PropertyExpression expr) bindings)
          prop (.getPropertyAsString ^PropertyExpression expr)]
      (cond
        (= ::unknown obj) ::unknown
        (map? obj)        (or (get obj prop) (get obj (keyword prop)))
        :else             ::unknown))

    (mce? expr)
    (let [^MethodCallExpression mc expr
          obj (groovy-eval (mce-object mc) bindings)
          method (mce-method-name mc)
          args (mapv #(groovy-eval % bindings) (mce-args mc))]
      (cond
        (or (= ::unknown obj) (some #(= ::unknown %) args)) ::unknown
        :else (eval-method obj method args)))

    (gstring-expr? expr)
    (let [^GStringExpression g expr
          strings (mapv #(.getValue ^ConstantExpression %) (.getStrings g))
          values  (mapv #(groovy-eval % bindings) (.getValues g))]
      (if (some #(= ::unknown %) values)
        ::unknown
        (apply str
               (concat
                (interleave strings (concat values (repeat "")))))))

    (instance? BinaryExpression expr)
    (let [^BinaryExpression b expr
          op  (token-op (.getOperation b))
          l   (groovy-eval (.getLeftExpression b)  bindings)
          r   (groovy-eval (.getRightExpression b) bindings)]
      (cond
        (or (= ::unknown op) (= ::unknown l) (= ::unknown r)) ::unknown
        :else (eval-binary op l r)))

    (instance? BooleanExpression expr)
    (groovy-eval (.getExpression ^BooleanExpression expr) bindings)

    (instance? NotExpression expr)
    (let [v (groovy-eval (.getExpression ^NotExpression expr) bindings)]
      (if (= ::unknown v) ::unknown (not v)))

    :else
    ::unknown))

;; ---------------------------------------------------------------------------
;; Script-binding collection: `def X = <expr>` at top level.
;; ---------------------------------------------------------------------------

(defn- collect-script-bindings
  "Scan top-level statements for `def X = <literal>` declarations and
   build a Clojure map { 'X' → value }. Only includes bindings whose
   value evaluates cleanly via `groovy-eval`."
  [top-statements]
  (let [out (volatile! {})]
    (doseq [^Statement stmt top-statements]
      (when (instance? ExpressionStatement stmt)
        (let [e (.getExpression ^ExpressionStatement stmt)]
          (when (instance? DeclarationExpression e)
            (let [^DeclarationExpression d e
                  lhs (.getLeftExpression d)
                  rhs (.getRightExpression d)]
              (when (variable-expr? lhs)
                (let [name (.getName ^VariableExpression lhs)
                      val  (groovy-eval rhs @out)]
                  (when (not= ::unknown val)
                    (vswap! out assoc name val)))))))))
    @out))

;; ---------------------------------------------------------------------------
;; Combinations call discovery
;; ---------------------------------------------------------------------------

(defn- combinations-mce?
  "True iff `mc` looks like `<expr>.combinations { … }`."
  [^MethodCallExpression mc]
  (and (= "combinations" (mce-method-name mc))
       (some closure-expr? (mce-args mc))))

(defn- find-combinations-mces
  "Walk top statements + nested closures, returning all combinations
   MCEs. Returns a vector of {:mce, :closure} maps in source order."
  [top-statements]
  (let [out (volatile! [])
        v (proxy [CodeVisitorSupport] []
            (visitMethodCallExpression [^MethodCallExpression mc]
              (when (combinations-mce? mc)
                (vswap! out conj {:mce mc :closure (mce-closure-arg mc)}))
              (let [^GroovyCodeVisitor self this]
                (.visit (.getObjectExpression mc) self)
                (.visit (.getMethod mc) self)
                (.visit (.getArguments mc) self))))]
    (doseq [stmt top-statements]
      (when (instance? ASTNode stmt)
        (.visit ^ASTNode stmt v)))
    @out))

;; ---------------------------------------------------------------------------
;; Inside a combinations closure: extract destructuring + filter + stages
;; ---------------------------------------------------------------------------

(defn- destructuring-names
  "`def (a, b) = it` parses as DeclarationExpression(lhs=ArgumentList of
   VariableExpression, rhs=VariableExpression('it')). Return the names
   in order, or nil if this stmt isn't a destructure-of-it."
  [^Statement stmt]
  (when (instance? ExpressionStatement stmt)
    (let [e (.getExpression ^ExpressionStatement stmt)]
      (when (instance? DeclarationExpression e)
        (let [^DeclarationExpression d e
              lhs (.getLeftExpression d)
              rhs (.getRightExpression d)]
          (when (and (instance? ArgumentListExpression lhs)
                     (variable-expr? rhs)
                     (= "it" (.getName ^VariableExpression rhs)))
            (mapv #(.getName ^VariableExpression %)
                  (filter variable-expr? (.getExpressions ^ArgumentListExpression lhs)))))))))

(defn- filter-guard?
  "Recognize `if (cond) { return }` (no else, body returns) at a
   statement position. Returns the condition expression, or nil."
  [^Statement stmt]
  (when (instance? IfStatement stmt)
    (let [^IfStatement ifs stmt
          then (.getIfBlock ifs)
          else (.getElseBlock ifs)
          ;; Only handle no-else (or empty-else) for v1. Groovy AST
          ;; uses EmptyStatement (not nil) when the else clause is absent.
          no-else (or (nil? else)
                      (instance? EmptyStatement else)
                      (and (instance? BlockStatement else)
                           (empty? (.getStatements ^BlockStatement else))))
          ;; Then-block must contain only a ReturnStatement
          then-stmts (cond
                       (instance? BlockStatement then) (.getStatements ^BlockStatement then)
                       (instance? Statement then)      [then]
                       :else                            [])
          returns? (and (= 1 (count then-stmts))
                        (instance? ReturnStatement (first then-stmts)))]
      (when (and no-else returns?)
        (.getExpression (.getBooleanExpression ifs))))))

(defn- collect-stage-mces-in
  "Walk `node` (typically a closure body) collecting every
   `stage(name) { body }` MCE, in source order."
  [^ASTNode node]
  (let [out (volatile! [])
        v (proxy [CodeVisitorSupport] []
            (visitMethodCallExpression [^MethodCallExpression mc]
              (when (and (= "stage" (mce-method-name mc))
                         (some closure-expr? (mce-args mc)))
                (vswap! out conj mc))
              (let [^GroovyCodeVisitor self this]
                (.visit (.getObjectExpression mc) self)
                (.visit (.getMethod mc) self)
                (.visit (.getArguments mc) self))))]
    (.visit node v)
    @out))

;; ---------------------------------------------------------------------------
;; Expansion of one combinations call
;; ---------------------------------------------------------------------------

(defn- materialize-stage
  "Given a `stage(name) { body }` MCE + the active binding, produce a
   stage IR map with `:name` resolved against the binding (GString
   interpolation). Body stays as-is — TX11A's translator already pulled
   it out into a flat step list when it first walked the IR; we don't
   re-walk it here. The matrix expander's job is name resolution +
   stage multiplication, not deep body re-interpretation."
  [^MethodCallExpression stage-mce bindings]
  (let [name-expr (first (mce-args stage-mce))
        resolved (groovy-eval name-expr bindings)]
    {:name (cond
             (= ::unknown resolved) (str (or (when (constant-expr? name-expr)
                                               (.getValue ^ConstantExpression name-expr))
                                             (when (gstring-expr? name-expr)
                                               (.getText ^GStringExpression name-expr))
                                             "<dynamic>"))
             :else (str resolved))
     :matrix-binding (into {} (filter (fn [[k v]] (or (string? v) (number? v))) bindings))}))

(defn- expand-one-combinations
  "Expand a single `<expr>.combinations { closure }` call against the
   script bindings. Returns:
     {:matrix-source  <source-fragment shown in receipts>
      :combinations-tried  N
      :combinations-surviving M
      :expanded-stages [{:name … :matrix-binding {…}} ...]
      :template-names #{templated GString texts the closure produced
                        unfiltered — used to drop those from base IR}}"
  [{:keys [^MethodCallExpression mce ^ClosureExpression closure]} bindings]
  (let [;; Resolve the receiver: <expr>.combinations{…}, where
        ;; <expr> is e.g. axes.values() — a method call on a binding.
        recv-expr (mce-object mce)
        axis-lists (groovy-eval recv-expr bindings)]
    (if (or (= ::unknown axis-lists) (not (sequential? axis-lists)))
      {:combinations-tried 0
       :combinations-surviving 0
       :expanded-stages []
       :template-names #{}
       :skip-reason :unresolvable-axes}
      (let [combos (cartesian (mapv vec axis-lists))
            ;; Pull the loop var names from `def (a, b) = it` or fall
            ;; back to ["it_0" "it_1" ...] indexed by tuple position.
            stmts (closure-statements closure)
            destruct (first (keep destructuring-names stmts))
            guard-exprs (vec (keep filter-guard? stmts))
            ;; Collect the template stage MCEs in source order.
            stage-mces (collect-stage-mces-in (.getCode closure))
            template-names (set
                            (keep (fn [^MethodCallExpression smc]
                                    (let [a (first (mce-args smc))]
                                      (cond
                                        (gstring-expr? a) (.getText ^GStringExpression a)
                                        (constant-expr? a) (str (.getValue ^ConstantExpression a))
                                        :else nil)))
                                  stage-mces))]
        (loop [pending combos
               surviving 0
               out []]
          (if (empty? pending)
            {:combinations-tried (count combos)
             :combinations-surviving surviving
             :expanded-stages out
             :template-names template-names}
            (let [tuple (first pending)
                  ;; Build per-combination binding
                  combo-binding
                  (merge bindings
                         {"it" tuple}
                         (cond
                           (and destruct (= (count destruct) (count tuple)))
                           (zipmap destruct tuple)

                           destruct
                           (zipmap destruct (concat tuple (repeat nil)))

                           :else
                           (into {} (map-indexed (fn [i v] [(str "it_" i) v]) tuple))))
                  ;; Evaluate filter guards: if ANY guard evaluates truthy,
                  ;; skip this combo. ::unknown guards are treated as
                  ;; not-skipping (conservative — we'd rather over-expand
                  ;; than silently drop combinations).
                  skip? (boolean
                         (some (fn [g]
                                 (let [v (groovy-eval g combo-binding)]
                                   (and (not= ::unknown v) v)))
                               guard-exprs))]
              (if skip?
                (recur (rest pending) surviving out)
                (let [combo-stages
                      (mapv #(materialize-stage % combo-binding) stage-mces)]
                  (recur (rest pending)
                         (inc surviving)
                         (into out combo-stages)))))))))))

;; ---------------------------------------------------------------------------
;; Public entry: take a (already-parsed) IR + the source, and return
;; an enriched IR where templated matrix stages are replaced with
;; expanded ones. No-op if the source has no .combinations { } calls.
;; ---------------------------------------------------------------------------

(defn- templated-stage?
  "A stage whose name still contains `${` or starts with `$` is a
   matrix template that should be dropped in favor of expanded copies."
  [stage]
  (let [n (str (:name stage))]
    (or (str/includes? n "${")
        (and (str/starts-with? n "$")
             (re-find #"^\$[A-Za-z_]" n)))))

(defn- merge-options [old-options summary]
  (let [base (vec (or old-options []))]
    (conj base {:matrix-expansion summary})))

(defn expand-matrices
  "Take an already-parsed Jenkins IR + the source string, return the IR
   with matrix combinations materialized into concrete stages.

   Currently handles `<X>.values().combinations { … }` and
   `<X>.combinations { … }` where `<X>` is a script-level `def`-bound
   map or list of lists.

   Receipt: the returned IR's `:options` gains an entry
     {:matrix-expansion
       {:matrices-found N
        :combinations-tried K
        :combinations-surviving M
        :stages-removed R
        :stages-added A}}
   so downstream tools (the admin UI, the migration report) can see
   what the expander did."
  [base-ir ^String source]
  (try
    (let [ast (g/parse-groovy-ast source)
          top-statements (g/flatten-top-statements ast)
          bindings (collect-script-bindings top-statements)
          combs (find-combinations-mces top-statements)]
      (if (empty? combs)
        base-ir
        (let [expansions (mapv #(expand-one-combinations % bindings) combs)
              all-template-names (apply set/union
                                        (map :template-names expansions))
              all-expanded-stages (vec (mapcat :expanded-stages expansions))
              ;; Drop templated stages in the base IR that we successfully
              ;; expanded — match by literal stage name (which TX11A
              ;; recorded as the GString template text).
              old-stages (vec (or (:stages base-ir) []))
              kept (filterv #(not (and (templated-stage? %)
                                       (contains? all-template-names (str (:name %)))))
                            old-stages)
              ;; Glue: expanded stages append in source order. Body
              ;; carry-over: for each expanded stage, find the matching
              ;; templated stage in the original IR and inherit its body.
              expanded-with-body
              (mapv (fn [exp]
                      (let [;; Find the template whose GString text
                            ;; appears within the expanded name's
                            ;; binding-resolved string. We approximate
                            ;; by name-prefix match on the longest
                            ;; literal segment of the template.
                            templated-match
                            (some (fn [{old-name :name :as old}]
                                    (when (and (templated-stage? old)
                                               (let [literal-parts
                                                     (->> (str/split old-name #"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")
                                                          (remove str/blank?))]
                                                 (every? #(str/includes? (str (:name exp)) %)
                                                         literal-parts)))
                                      old))
                                  old-stages)]
                        (cond-> exp
                          templated-match (assoc :steps (:steps templated-match)))))
                    all-expanded-stages)
              new-stages (into kept expanded-with-body)
              summary {:matrices-found (count combs)
                       :combinations-tried (reduce + 0 (map :combinations-tried expansions))
                       :combinations-surviving (reduce + 0 (map :combinations-surviving expansions))
                       :stages-removed (- (count old-stages) (count kept))
                       :stages-added (count expanded-with-body)
                       :per-matrix (mapv #(select-keys % [:combinations-tried
                                                          :combinations-surviving
                                                          :skip-reason])
                                         expansions)}]
          (-> base-ir
              (assoc :stages new-stages)
              (update :options merge-options summary)))))
    (catch Exception e
      ;; Matrix expansion never fails the parse — record the error
      ;; on the IR and pass through.
      (update base-ir :options merge-options
              {:matrix-expansion-error (.getMessage e)
               :exception-class (.getName (class e))}))))

(defn parse-with-matrices
  "Convenience wrapper: parse `source` via the regular translator, then
   apply matrix expansion. Same return shape as `translator/parse`."
  [translator-parse-fn ^String source ^String source-path]
  (let [base (translator-parse-fn source source-path)]
    (expand-matrices base source)))
