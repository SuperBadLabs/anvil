(ns anvil.compat.jenkins.translator
  "Jenkinsfile source → anvil's Jenkins IR.

   This is the entry point. `parse` returns a fully-translated IR map
   ready for the runtime/dispatcher (TX4) to consume, or for the
   migration UX (TX7) to report on.

   Productized from spike #4's anvil-integration translator. The walk
   uses the :cdata view from `anvil.compat.jenkins.groovy/->cdata` and
   recovers line numbers for `script {}` body source extraction by
   pairing :cdata closure nodes with their originating ClosureExpression
   objects in encounter order."
  (:require [clojure.string :as str]
            [anvil.compat.jenkins.groovy :as g]
            [anvil.compat.jenkins.ir :as ir]
            [anvil.compat.jenkins.jenkinsfile-preamble :as preamble]
            [anvil.compat.jenkins.matrix-declarative :as mx-decl])
  (:import [org.codehaus.groovy.ast ASTNode CodeVisitorSupport GroovyCodeVisitor]
           [org.codehaus.groovy.ast.expr ArgumentListExpression
            ClosureExpression
            ConstantExpression
            DeclarationExpression
            ElvisOperatorExpression
            GStringExpression
            MethodCallExpression
            TupleExpression
            VariableExpression]
           [org.codehaus.groovy.ast.stmt BlockStatement ExpressionStatement]))

;; ---------------------------------------------------------------------------
;; :cdata walking helpers
;; ---------------------------------------------------------------------------

(defn- call? [x]
  (and (map? x) (= :call (:type x))))

(defn- closure? [x]
  (and (map? x) (= :closure (:type x))))

(defn- const-val
  "If x is a :const cdata node, return its value; else nil."
  [x]
  (when (and (map? x) (= :const (:type x))) (:value x)))

(defn- map-arg
  "Detect a single map-shaped arg. In :cdata, named-args like
   `sh(script:'…', returnStdout:true)` come through as :other with the
   text starting with '['. The text contains the raw map source."
  [x]
  (when (and (map? x) (= :other (:type x))
             (str/starts-with? (or (:text x) "") "["))
    {:raw-text (:text x)}))

(defn- closure-arg [call]
  (let [last-arg (last (:args call))]
    (when (closure? last-arg) last-arg)))

(defn- body-calls
  "Calls inside a :closure :cdata node's body."
  [closure-cdata]
  (filter call? (:body closure-cdata)))

(defn- find-call
  "First call in `calls` whose :name is `n`, or nil."
  [calls n]
  (some #(when (= n (:name %)) %) calls))

;; ---------------------------------------------------------------------------
;; Closure source-region recovery for `script {}` bodies
;; ---------------------------------------------------------------------------

(defn- collect-cdata-closures
  "Walk a :cdata node returning every :closure node in encounter order
   (matching the order in `g/collect-closure-expressions`)."
  [cdata]
  (cond
    (closure? cdata)
    (cons cdata (mapcat collect-cdata-closures (:body cdata)))

    (call? cdata)
    (mapcat collect-cdata-closures (:args cdata))

    :else
    nil))

(defn- closure-cdata→expr-map
  "Match :cdata closures to ClosureExpression objects by encounter order
   so we can later recover line numbers."
  [ast-nodes top-cdata]
  (let [exprs (g/collect-closure-expressions ast-nodes)
        cdatas (collect-cdata-closures top-cdata)]
    (zipmap cdatas exprs)))

(defn- closure-body-source
  "Pull the original source text strictly between a ClosureExpression's
   opening { and closing }. Lossy on edge cases (e.g. closures on a
   single line) but accurate for the conventional script-block
   indentation in real Jenkinsfiles."
  [^ClosureExpression closure-expr ^String source]
  (let [start-line (.getLineNumber closure-expr)
        end-line   (.getLastLineNumber closure-expr)
        all-lines  (str/split-lines source)]
    (if (or (neg? start-line) (neg? end-line) (>= start-line end-line))
      ""
      (str/join "\n" (subvec (vec all-lines) start-line (dec end-line))))))

;; ---------------------------------------------------------------------------
;; Step translation
;;
;; Step names dispatch here. Every step we recognize produces a
;; well-formed IR node; unknown names fall through to :jenkins/unknown
;; with the raw args preserved.
;; ---------------------------------------------------------------------------

(declare translate-call translate-steps-body map-arg-kv list-arg-strings
         translate-stages translate-stage)

;; ---------------------------------------------------------------------------
;; v0.6.2 — Top-level Groovy `def NAME = expr` script bindings.
;;
;; Real-world Jenkinsfiles routinely declare file-scope vars above the
;; `pipeline { }` block and reference them inside (label AGENT_LABEL,
;; jdk JDK_NAME, sh "${MAVEN_PARAMS} ..."). The translator previously
;; ignored these defs entirely, so any reference fell through to
;; `<dynamic>` (label/tools) or passed `$NAME` literally to the shell.
;;
;; This pass walks `top-statements` BEFORE pipeline translation, picks
;; out every `def NAME = <expr>` whose RHS is statically resolvable,
;; and exposes the resulting NAME→STRING map via `*script-bindings*`.
;; The dynamic var pattern (rather than threading the map through every
;; translate-* signature) keeps existing arglists stable while making
;; the bindings reachable from translate-agent-block, translate-tools,
;; and translate-sh.
;;
;; Resolvable RHS shapes (v0.6.2):
;;   - String literal:                def X = 'value'
;;   - Elvis env.X ?: 'default':      def X = env.X ?: 'default'  → "default"
;;     (env not known at parse time, so we always take the fallback)
;;
;; Unresolved entries are recorded under `*script-bindings-unresolvable*`
;; so the pipeline IR can carry a diagnostic option entry for operator
;; visibility — they don't override anything and downstream consumers
;; fall through to existing behavior (`<dynamic>` / `<unmapped>` etc.).
;; ---------------------------------------------------------------------------

(def ^:dynamic *script-bindings*
  "Map of {NAME STRING} extracted from top-level `def NAME = expr`
   statements in the Jenkinsfile. Bound by `parse`; nil outside of a
   parse call."
  nil)

(def ^:dynamic *script-bindings-unresolvable*
  "Set of NAME strings whose top-level `def` RHS could not be statically
   resolved (e.g. method calls, dynamic property access)."
  nil)

(defn- resolve-declaration-rhs
  "Try to statically resolve a DeclarationExpression's RHS to a string.
   Returns [:ok STRING] if resolved, [:unresolved] otherwise."
  [rhs]
  (cond
    ;; def X = 'string'
    (instance? ConstantExpression rhs)
    (let [v (.getValue ^ConstantExpression rhs)]
      (if (or (string? v) (number? v) (boolean? v))
        [:ok (str v)]
        [:unresolved]))

    ;; def X = env.X ?: 'default'  → fall back to 'default' (env unknown
    ;; at parse). If the true-expr happens to be a literal ('a' ?: 'b'),
    ;; prefer it.
    (instance? ElvisOperatorExpression rhs)
    (let [^ElvisOperatorExpression el rhs
          true-expr (.getTrueExpression el)
          false-expr (.getFalseExpression el)]
      (cond
        (instance? ConstantExpression true-expr)
        (resolve-declaration-rhs true-expr)

        :else
        (resolve-declaration-rhs false-expr)))

    ;; def X = "literal-gstring..."  — too risky to substitute at parse
    ;; time (env-only GStrings are rare in top-level defs).
    (instance? GStringExpression rhs)
    [:unresolved]

    :else
    [:unresolved]))

(defn extract-top-level-defs
  "Walk top-level statements collecting `def NAME = expr` bindings.
   Returns {:resolved {NAME STRING} :unresolved #{NAME}}.

   Descends through nested BlockStatements once (AstBuilder sometimes
   wraps in extra blocks) but does NOT descend into closures or method
   bodies — only TOP-LEVEL defs count (per Jenkins script scope: the
   pipeline closure inherits its enclosing-script bindings, not
   arbitrary nested ones)."
  [top-statements]
  (let [resolved (volatile! {})
        unresolved (volatile! #{})
        visit-decl (fn [^DeclarationExpression de]
                     (let [lhs (.getLeftExpression de)]
                       (when (instance? VariableExpression lhs)
                         (let [nm (.getName ^VariableExpression lhs)
                               rhs (.getRightExpression de)
                               [tag v] (resolve-declaration-rhs rhs)]
                           (case tag
                             :ok (vswap! resolved assoc nm v)
                             :unresolved (vswap! unresolved conj nm))))))
        visit-stmt (fn visit-stmt [stmt]
                     (cond
                       (instance? ExpressionStatement stmt)
                       (let [e (.getExpression ^ExpressionStatement stmt)]
                         (when (instance? DeclarationExpression e)
                           (visit-decl e)))

                       (instance? BlockStatement stmt)
                       (doseq [s (.getStatements ^BlockStatement stmt)]
                         (visit-stmt s))))]
    (doseq [stmt top-statements] (visit-stmt stmt))
    {:resolved @resolved :unresolved @unresolved}))

(defn- args->plain
  "Reduce :cdata args to plain Clojure values for the :jenkins/unknown
   payload + the migration-UX 'what does this step take?' display."
  [args]
  (mapv (fn [a]
          (case (:type a)
            :const   (:value a)
            :var     (symbol (:name a))
            :call    (list (symbol (:name a)) :...)
            :closure :<closure>
            :other   (:text a)
            :gstring (:text a)
            :list    (mapv (fn [item]
                             (case (:type item)
                               :const (:value item)
                               :other (:text item)
                               (str item)))
                           (:items a))
            :map     (into {}
                           (for [{:keys [key val]} (:entries a)
                                 :when (= :const (:type key))]
                             [(keyword (str (:value key)))
                              (case (:type val)
                                :const (:value val)
                                :other (:text val)
                                (str val))]))
            (str a)))
        args))

(defn- interpolate-script-bindings
  "Substitute every `$NAME` / `${NAME}` reference in `text` with the
   corresponding entry from `*script-bindings*`. References whose NAME
   isn't in the binding map are left untouched (so genuine bash `$VAR`
   refs and unresolved Groovy refs both pass through unchanged).

   ONLY called against GString contexts where `$X` is a Groovy variable
   reference. Single-quoted Groovy strings (the conventional `sh '...'`
   shape) come through as :const and are never touched."
  [text]
  (let [bindings *script-bindings*
        t (str text)]
    (if (seq bindings)
      (-> t
          (str/replace #"\$\{([A-Za-z_]\w*)\}"
                       (fn [[whole nm]] (or (get bindings nm) whole)))
          (str/replace #"\$([A-Za-z_]\w*)"
                       (fn [[whole nm]] (or (get bindings nm) whole))))
      t)))

(defn- translate-sh
  [call _source _closure-objs]
  (let [args (:args call)]
    (cond
      (= 1 (count args))
      (let [a (first args)]
        (case (:type a)
          :const   (ir/step-sh (:value a))
          ;; v0.6.2 — GString sh body may reference top-level `def` vars
          ;; (`sh "./mvnw ${MAVEN_PARAMS} ..."`). Substitute at translator
          ;; time so the dispatcher sees the resolved command.
          :gstring (ir/step-sh (interpolate-script-bindings (:text a)))
          :map     (let [m (map-arg-kv a)]
                     (cond-> (ir/step-sh (str (:script m "")))
                       (:returnStdout m) (assoc :return-stdout? true)
                       (:returnStatus m) (assoc :return-status? true)
                       (:label m)        (assoc :label (str (:label m)))))
          :other   (let [t (:text a "")]
                     (cond-> (ir/step-sh "<from-named-args>")
                       true (assoc :raw-args t)
                       (str/includes? t "returnStdout") (assoc :return-stdout? true)
                       (str/includes? t "returnStatus") (assoc :return-status? true)))
          (ir/step-unknown "sh" (args->plain args))))

      :else
      (ir/step-unknown "sh" (args->plain args)))))

(defn- translate-bat
  [call _ _]
  (if-let [s (const-val (first (:args call)))]
    {:type :jenkins/bat :script s}
    (ir/step-unknown "bat" (args->plain (:args call)))))

(defn- source-region
  "Slice `source` between (line-start, col-start) and (line-end, col-end).
   Groovy line/col are 1-indexed. Returns nil on missing positions or
   out-of-range — caller falls back."
  [source line-start col-start line-end col-end]
  (when (and source line-start col-start line-end col-end
             (every? pos? [line-start col-start line-end col-end]))
    (try
      (let [lines (str/split-lines source)]
        (when (<= line-end (count lines))
          (let [lines-region (subvec (vec lines) (dec line-start) line-end)
                ;; Multi-line: keep first line from col-start, last line
                ;; up to col-end, middle lines verbatim. Cols are
                ;; 1-indexed and inclusive of end.
                first-line (nth lines-region 0)
                last-line  (nth lines-region (dec (count lines-region)))
                trimmed-first (subs first-line (max 0 (dec col-start)))
                trimmed-last  (subs last-line 0 (min (count last-line) (dec col-end)))]
            (if (= 1 (count lines-region))
              (subs first-line (max 0 (dec col-start))
                    (min (count first-line) (dec col-end)))
              (str/join "\n"
                        (concat [trimmed-first]
                                (when (> (count lines-region) 2)
                                  (subvec lines-region 1 (dec (count lines-region))))
                                [trimmed-last]))))))
      (catch Exception _ nil))))

(defn- translate-echo
  [call source _]
  (let [a (first (:args call))]
    (case (:type a)
      :const   (ir/step-echo (str (:value a)))
      :gstring (ir/step-echo (:text a))
      :var     (ir/step-echo (str "$" (:name a)))
      ;; :other — BinaryExpression, PropertyExpression, etc. Emit a
      ;; one-step script {} block carrying the original source span so
      ;; Groovy evaluates `"Building " + env.BRANCH_NAME` against the
      ;; live binding instead of dumping the AST .toString(). Falls
      ;; back to the legacy (broken) text dump if we can't recover
      ;; the source region — same behavior as before for safety.
      (let [{:keys [line-start line-end col-start col-end text]} a
            region (source-region source line-start col-start line-end col-end)]
        (if region
          (ir/step-script (str "echo " region))
          (ir/step-echo (or text "")))))))

(defn- translate-junit
  [call _ _]
  (let [a (first (:args call))]
    (case (:type a)
      :const {:type :jenkins/junit :results (:value a)}
      :map   (let [m (map-arg-kv a)]
               (cond-> {:type :jenkins/junit :results (str (:testResults m ""))}
                 (:allowEmptyResults m) (assoc :allow-empty? true)))
      :other {:type :jenkins/junit
              :results "<from-named-args>"
              :raw-args (:text a)}
      (ir/step-unknown "junit" (args->plain (:args call))))))

(defn- translate-archive
  [call _ _]
  (let [a (first (:args call))]
    (case (:type a)
      :const {:type :jenkins/archive-artifacts :artifacts (:value a)}
      :map   (let [m (map-arg-kv a)]
               (cond-> {:type :jenkins/archive-artifacts
                        :artifacts (str (:artifacts m ""))}
                 (:excludes m)      (assoc :excludes (str (:excludes m)))
                 (:fingerprint m)   (assoc :fingerprint? (boolean (:fingerprint m)))
                 (:allowEmptyArchive m) (assoc :allow-empty? true)))
      :other {:type :jenkins/archive-artifacts
              :artifacts "<from-named-args>"
              :raw-args (:text a)}
      (ir/step-unknown "archiveArtifacts" (args->plain (:args call))))))

(defn- translate-delete-dir
  [_call _ _]
  {:type :jenkins/delete-dir})

(defn- translate-stash
  [call _ _]
  (let [a (first (:args call))]
    (case (:type a)
      :map   (let [m (map-arg-kv a)]
               (cond-> {:type :jenkins/stash}
                 (:name m)     (assoc :name     (str (:name m)))
                 (:includes m) (assoc :includes (str (:includes m)))
                 (:excludes m) (assoc :excludes (str (:excludes m)))))
      :other {:type :jenkins/stash :raw-args (:text a)}
      :const {:type :jenkins/stash :name (str (:value a))}
      {:type :jenkins/stash :raw-args (str a)})))

(defn- translate-unstash
  [call _ _]
  (let [a (first (:args call))]
    (case (:type a)
      :const {:type :jenkins/unstash :name (:value a)}
      (ir/step-unknown "unstash" (args->plain (:args call))))))

(defn- translate-dir
  [call source closure-objs]
  (let [path-arg (first (:args call))
        path (or (const-val path-arg) "<dynamic>")
        body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))]
    {:type :jenkins/dir :path path :body (vec (or body []))}))

(defn- arg-name->str
  "Extract a stage/node name from the first arg of a scripted call.
   Handles constants, GStrings (recording the literal template), and
   falls back to '<dynamic>' for variables or complex expressions."
  [arg]
  (cond
    (and (map? arg) (= :const (:type arg)))   (str (:value arg))
    (and (map? arg) (= :gstring (:type arg))) (:text arg)
    (and (map? arg) (= :var (:type arg)))     (str "$" (:name arg))
    :else                                     "<dynamic>"))

(defn- translate-node
  "Scripted-pipeline `node(label) { body }` — a scope wrapper that
   schedules its body on an agent matching `label`. We extract steps
   from the body so the dispatcher can recursively walk them."
  [call source closure-objs]
  (let [label (arg-name->str (first (:args call)))
        body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))]
    {:type :jenkins/node :label label :body (vec (or body []))}))

(defn- translate-script
  [call source closure-objs]
  (let [body-closure (closure-arg call)
        closure-expr (get closure-objs body-closure)
        body-source (if closure-expr
                      (closure-body-source closure-expr source)
                      "")
        ;; Top-level helper-function defs surrounding the pipeline {}
        ;; block — `boolean isDeployedBranch() { ... }`, `def mavenBuild
        ;; (jdk, args) { ... }` etc. Wild-corpus found 3+ Jenkinsfiles
        ;; that crash in `script {}` blocks because Groovy's
        ;; MissingMethodException fires on the helper before the body
        ;; runs. Prepending the preamble lets Groovy see the defs at
        ;; compile time without changing anvil's pipeline semantics.
        pre (preamble/extract source)]
    (cond-> (ir/step-script body-source)
      (and pre (seq pre)) (assoc :preamble pre))))

;; ---------------------------------------------------------------------------
;; Scope wrappers — declarative IR form. Body steps live inside :body, which
;; the dispatcher recursively walks with modified context.
;; ---------------------------------------------------------------------------

(defn- list-arg-strings
  "Pull a list-of-strings out of a :list :cdata arg, or fall back to the raw
   text of an :other arg (legacy AST shapes)."
  [arg]
  (cond
    (and (map? arg) (= :list (:type arg)))
    (->> (:items arg)
         (keep #(when (= :const (:type %)) (:value %))))

    (and (map? arg) (= :other (:type arg)))
    [(:text arg)]

    :else
    []))

(defn- map-arg-kv
  "Pull a Clojure map of keyword → const-value out of a :map :cdata arg,
   if possible. Used for `timeout(time: 5, unit: 'MINUTES')` etc."
  [arg]
  (cond
    (and (map? arg) (= :map (:type arg)))
    (into {}
          (keep (fn [{:keys [key val]}]
                  (when-let [k (and (= :const (:type key)) (:value key))]
                    [(keyword (str k))
                     (case (:type val)
                       :const (:value val)
                       :other (:text val)
                       (str val))])))
          (:entries arg))

    (and (map? arg) (= :other (:type arg)))
    ;; Legacy text form like "[time: 5, unit: 'MINUTES']".
    ;; re-seq returns 4-element vectors [whole key value-or-quoted
    ;; quoted-inner]; `into {}` needs 2-element pairs. Map first or
    ;; the whole Jenkinsfile lands in the empty parse-error IR
    ;; (Codex P2, PR #164).
    (->> (re-seq #"([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(\d+|['\"]([^'\"]+)['\"])"
                 (:text arg ""))
         (map (fn [[_ k raw-val quoted-inner]]
                [(keyword k)
                 (cond
                   (some? quoted-inner)
                   quoted-inner

                   (re-matches #"\d+" raw-val)
                   (try (Long/parseLong raw-val) (catch Exception _ raw-val))

                   :else
                   raw-val)]))
         (into {}))

    :else
    {}))

(defn- translate-with-env
  [call source closure-objs]
  (let [args (:args call)
        envs (list-arg-strings (first args))
        body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))]
    {:type :jenkins/with-env
     :env-strings (vec envs)
     :body (vec (or body []))}))

(defn- translate-container
  "v0.4 T2.1 — `container('image') { … }` from Jenkins-Pipeline.

   Routes its body's sh steps through chengis-core's DockerBackend
   (handled in the dispatcher per AV4-2 — no new container abstraction).

   The first arg is the image string; a single closure provides the
   body.  Both forms `container('img') { … }` and
   `container image: 'img' { … }` are accepted: the latter shows up
   as a `:map`-form first arg.  Unknown images at translation time
   are still emitted — the dispatcher's docker hook reports a
   pull-failure honestly at run-time per the AN5-* effects pattern."
  [call source closure-objs]
  (let [args (:args call)
        first-arg (first args)
        image (cond
                (string? first-arg) first-arg
                (and (map? first-arg) (= :const (:type first-arg)))
                (:value first-arg)
                (and (map? first-arg) (= :map (:type first-arg)))
                ;; #243 — map-arg-kv returns keyword keys; the old
                ;; `(get … "image")` string-key lookup always missed
                ;; and yielded `:image nil` for the
                ;; `container(image: 'X') { … }` form.  Surfaced by
                ;; the v0.4 T2.6 fixture-writing dogfood.
                (:image (map-arg-kv first-arg))
                :else nil)
        body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))]
    {:type :jenkins/container
     :image image
     :body (vec (or body []))}))

(defn- translate-with-credentials
  [call source closure-objs]
  (let [args (:args call)
        creds-arg (first args)
        body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))
        ;; For TX4 we capture credentials raw — masking attaches to ctx by
        ;; credential-id binding. Detailed parsing waits for TX5.
        creds-raw (cond
                    (and (map? creds-arg) (= :list (:type creds-arg)))
                    (mapv (fn [item]
                            (cond
                              (= :call (:type item)) {:kind (:name item)
                                                      :raw-args (args->plain (:args item))}
                              :else (str item)))
                          (:items creds-arg))

                    (and (map? creds-arg) (= :other (:type creds-arg)))
                    [{:raw-text (:text creds-arg)}]

                    :else
                    [])]
    {:type :jenkins/with-credentials
     :credentials creds-raw
     :body (vec (or body []))}))

(defn- translate-timeout
  [call source closure-objs]
  (let [args (:args call)
        first-arg (first args)
        kv (map-arg-kv first-arg)
        time-val (or (:time kv)
                     (when (= :const (:type first-arg)) (:value first-arg)))
        unit (or (:unit kv) "MINUTES")
        body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))]
    (cond-> {:type :jenkins/timeout :unit unit :body (vec (or body []))}
      time-val (assoc :time time-val))))

(defn- translate-retry
  [call source closure-objs]
  (let [args (:args call)
        first-arg (first args)
        n (cond
            (and (map? first-arg) (= :const (:type first-arg))) (:value first-arg)
            :else nil)
        body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))]
    (cond-> {:type :jenkins/retry :body (vec (or body []))}
      n (assoc :count n))))

(defn- translate-properties
  "`properties([buildDiscarder(...), disableConcurrentBuilds(...)])` —
   sets job-level config. v1 records the call (so the admin UI can see
   what was set) but enforces nothing. disableConcurrentBuilds is
   already covered by anvil's per-job concurrency cap (TX9 phase 5)."
  [call _source _closure-objs]
  {:type :jenkins/properties
   :raw-args (args->plain (:args call))})

(defn- translate-with-checks
  "`withChecks(name: 'Tests', includeStage: true) { body }` — GitHub
   Checks-API integration wrapper. Body runs unchanged."
  [call source closure-objs]
  (let [body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))]
    {:type :jenkins/with-checks
     :raw-args (args->plain (vec (butlast (:args call))))
     :body (vec (or body []))}))

(defn- translate-with-maven
  "`withMaven(maven: 'maven-3.9', jdk: 'jdk-21') { body }` — Maven
   Integration plugin wrapper. v1 runs body unchanged (PATH/JAVA_HOME
   are managed via the agent registry's label env)."
  [call source closure-objs]
  (let [body-closure (closure-arg call)
        body (when body-closure
               (mapv #(translate-call % source closure-objs)
                     (body-calls body-closure)))]
    {:type :jenkins/with-maven
     :raw-args (args->plain (vec (butlast (:args call))))
     :body (vec (or body []))}))

(defn- translate-parallel
  "`parallel(a: { … }, b: { … })` — named-args form maps each name to a
   block of steps. Each block becomes its own [step ...] vector."
  [call source closure-objs]
  (let [args (:args call)
        first-arg (first args)]
    (cond
      ;; Map-style: `parallel(a: { ... }, b: { ... })` (a single MapExpression arg)
      (and (map? first-arg) (= :map (:type first-arg)))
      {:type :jenkins/parallel
       :branches
       (into {}
             (for [{:keys [key val]} (:entries first-arg)
                   :when (and (= :const (:type key))
                              (= :closure (:type val)))]
               [(str (:value key))
                (mapv #(translate-call % source closure-objs) (body-calls val))]))}

      :else
      (ir/step-unknown "parallel" (args->plain args)))))

;; ---------------------------------------------------------------------------
;; Plugin step adapters — leaf handlers for common plugin-step names. These
;; produce :jenkins/<NAME> IR nodes the dispatcher handles as leaf records.
;; ---------------------------------------------------------------------------

(defn- leaf-plugin-step
  "Helper that produces a fixed-type leaf step preserving the raw args."
  [step-kw]
  (fn [call _ _]
    {:type step-kw
     :raw-args (args->plain (:args call))}))

;; Additional leaf-step adapters — common steps the corpus survey flagged as
;; unknown. These produce minimal IR nodes; richer payloads + scope-wrapping
;; (timeout, retry, withEnv, withCredentials, parallel) belong to TX4.

(defn- translate-checkout
  [call _ _]
  (let [a (first (:args call))]
    (case (:type a)
      :var   {:type :jenkins/checkout :ref (str "$" (:name a))}  ; checkout scm
      :const {:type :jenkins/checkout :spec (:value a)}
      {:type :jenkins/checkout :raw-args (args->plain (:args call))})))

(defn- translate-clean-ws
  [_ _ _]
  ;; Workspace Cleanup plugin step — same semantics as deleteDir for our purposes.
  {:type :jenkins/delete-dir})

(defn- translate-mail
  [call _ _]
  {:type :jenkins/mail :raw-args (args->plain (:args call))})

(defn- translate-emailext
  [call _ _]
  {:type :jenkins/emailext :raw-args (args->plain (:args call))})

(defn- translate-write-file
  [call _ _]
  {:type :jenkins/write-file :raw-args (args->plain (:args call))})

(defn- translate-read-file
  [call _ _]
  {:type :jenkins/read-file :raw-args (args->plain (:args call))})

(defn- translate-build-downstream
  [call _ _]
  {:type :jenkins/build :raw-args (args->plain (:args call))})

(defn- translate-error
  [call _ _]
  (let [a (first (:args call))]
    (case (:type a)
      :const {:type :jenkins/error :message (:value a)}
      {:type :jenkins/error :raw-args (args->plain (:args call))})))

(defn- translate-sleep
  [call _ _]
  {:type :jenkins/sleep :raw-args (args->plain (:args call))})

(def ^:private step-translators
  {"sh"               translate-sh
   "bat"              translate-bat
   "echo"             translate-echo
   "junit"            translate-junit
   "archiveArtifacts" translate-archive
   "deleteDir"        translate-delete-dir
   "stash"            translate-stash
   "unstash"          translate-unstash
   "dir"              translate-dir
   "node"             translate-node
   "script"           translate-script
   ;; Leaf-step additions (no scope-wrapping; TX4 adds timeout/retry/withEnv/
   ;; withCredentials/parallel which need body-closure handling).
   "checkout"         translate-checkout
   "cleanWs"          translate-clean-ws
   "mail"             translate-mail
   "emailext"         translate-emailext
   "writeFile"        translate-write-file
   "readFile"         translate-read-file
   "build"            translate-build-downstream
   "error"            translate-error
   "sleep"            translate-sleep
   ;; Scope wrappers (declarative IR form)
   "withEnv"          translate-with-env
   "withCredentials"  translate-with-credentials
   ;; v0.4 T2.1 — container('image') { sh … } — route body through
   ;; chengis-core's DockerBackend (AV4-2). Behaves like withEnv from
   ;; the translator's POV: wraps a closure, no container infra here.
   "container"        translate-container
   "timeout"          translate-timeout
   "retry"            translate-retry
   "parallel"         translate-parallel
   ;; TX11E: Pipeline-level config + Jenkins-plugin wrappers
   "properties"       translate-properties
   "withChecks"       translate-with-checks
   "withMaven"        translate-with-maven
   ;; Plugin step adapters — leaf handlers (the runtime + dispatcher record
   ;; them as side effects; full per-plugin behavior comes in TX5 if needed).
   "recordIssues"           (leaf-plugin-step :jenkins/record-issues)
   "slackSend"              (leaf-plugin-step :jenkins/slack-send)
   "milestone"              (leaf-plugin-step :jenkins/milestone)
   "withSonarQubeEnv"       (leaf-plugin-step :jenkins/with-sonarqube-env)
   "publishCoverage"        (leaf-plugin-step :jenkins/publish-coverage)
   "publishHTML"            (leaf-plugin-step :jenkins/publish-html)
   "lock"                   (leaf-plugin-step :jenkins/lock)
   "sshagent"               (leaf-plugin-step :jenkins/ssh-agent)
   "sshPublisher"           (leaf-plugin-step :jenkins/ssh-publisher)
   "nexusArtifactUploader"  (leaf-plugin-step :jenkins/nexus-upload)
   "waitForQualityGate"     (leaf-plugin-step :jenkins/wait-quality-gate)
   "addFailedStage"         (leaf-plugin-step :jenkins/add-failed-stage)
   "sendNotifications"      (leaf-plugin-step :jenkins/send-notifications)
   "sendErrorNotification"  (leaf-plugin-step :jenkins/send-error-notification)
   "sendSuccessNotification" (leaf-plugin-step :jenkins/send-success-notification)
   "notifySlack"            (leaf-plugin-step :jenkins/notify-slack)
   "discoverGitReferenceBuild" (leaf-plugin-step :jenkins/discover-git-ref-build)
   "pipelineHelpers"        (leaf-plugin-step :jenkins/pipeline-helpers)
   "reportPortal"           (leaf-plugin-step :jenkins/report-portal)})

(defn- translate-call
  "Dispatch a single call to its translator; fall through to :jenkins/unknown.

   If the unknown call's last arg is a `:closure` (i.e. it looks like a
   block step — `realtimeJUnit(...) { ... }`, `withChecks(...) { ... }`,
   etc.), translate the closure body too and attach as `:body` on the
   unknown IR. The dispatcher's h-unknown will run the body even though
   the outer call is shimmed as a no-op — so nested KNOWN steps inside
   unknown block steps still execute. This is what makes the unmodified
   ci.jenkins.io Jenkinsfile reach its `infra.runMaven` call buried
   inside `withChecks { realtimeJUnit { … } }`."
  [call source closure-objs]
  (let [n (:name call)
        tr (get step-translators n)
        last-arg (last (:args call))
        closure-arg (when (and (map? last-arg) (= :closure (:type last-arg)))
                      last-arg)
        body (when closure-arg
               (->> (body-calls closure-arg)
                    (mapv #(translate-call % source closure-objs))))]
    (if tr
      (tr call source closure-objs)
      (cond-> (ir/step-unknown n (args->plain (:args call)))
        (seq body) (assoc :body body)))))

(defn- translate-steps-body
  [steps-closure source closure-objs]
  (mapv #(translate-call % source closure-objs) (body-calls steps-closure)))

;; ---------------------------------------------------------------------------
;; Structural translation: stages, post, agent, environment, options
;; ---------------------------------------------------------------------------

(defn- translate-environment
  "environment { K = 'V'; K2 = 'V2' } → {STRING STRING}.

   Assignments aren't method calls in the AST — they're BinaryExpression
   ExpressionStatements. The :cdata view doesn't carry their source text
   intelligibly, so we recover the closure's source region (via the
   ClosureExpression line numbers held in closure-objs) and regex over
   the original Jenkinsfile source. Sloppy but correct for the common
   case of `K = 'V'` and `K = \"V\"` (interpolation, `credentials(...)`
   wrappers and similar are out of scope here — captured raw for now)."
  [env-closure source closure-objs]
  (if-let [^ClosureExpression closure-expr (get closure-objs env-closure)]
    (let [body-text (closure-body-source closure-expr source)
          ;; Pattern: KEY = 'value' or KEY = "value"
          quoted (re-seq #"([A-Za-z_][A-Za-z0-9_]*)\s*=\s*['\"](.*?)['\"]" body-text)
          ;; Pattern: KEY = identifier-ish (no quotes)
          bare   (re-seq #"([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)\s*(?:\n|$|;)" body-text)]
      (into {} (concat
                (for [m quoted] [(nth m 1) (nth m 2)])
                (for [m bare]   [(nth m 1) (str "<" (nth m 2) ">")]))))
    {}))

(defn- translate-post-body
  "post { always { … } success { … } failure { … } cleanup { … } } →
   a map of action-keyword → [step ...]."
  [post-closure source closure-objs]
  (->> (body-calls post-closure)
       (reduce
        (fn [acc call]
          (let [action-key (case (:name call)
                             "always"   :always
                             "success"  :on-success
                             "failure"  :on-failure
                             "changed"  :on-changed
                             "unstable" :on-unstable
                             "aborted"  :on-aborted
                             "cleanup"  :cleanup
                             (keyword (:name call)))
                inner-closure (closure-arg call)
                steps (when inner-closure
                        (mapv #(translate-call % source closure-objs)
                              (body-calls inner-closure)))]
            (assoc acc action-key (vec (or steps [])))))
        {})))

(defn- translate-parameters
  "v0.4 AN6-1 — extract a parameters block into a vector of param specs.

   Recognized at v0.4.0: `choice { name 'X' choices ['a','b'] defaultValue 'a' }`
   and the equivalent named-arg form `choice(name: 'X', choices: ['a','b'])`.
   Other parameter kinds (string, booleanParam, password) parse as
   `:kind` + raw text so we don't lose them, but `agent { label { label
   params.X } }` resolution only consults :choice entries.

   Returns `[{:kind :choice :name <str> :choices [<str>] :default-value <str>?} …]`."
  [params-call]
  (when params-call
    (when-let [closure (closure-arg params-call)]
      (let [body (body-calls closure)]
        (vec
         (for [c body
               :let [nm (:name c)
                     args (:args c)
                     mform-arg (first args)
                     ;; map-arg-kv returns keyword keys.  Works for both
                     ;; structured :map cdata (clean keyword keys) and
                     ;; legacy :other text (regex-parsed scalars).
                     mform-kv (when (and (map? mform-arg)
                                         (#{:map :other} (:type mform-arg)))
                                (map-arg-kv mform-arg))
                     ;; Closure form: choice { name 'X' choices [...] }
                     cform (closure-arg c)
                     cbody (when cform (body-calls cform))
                     cl-name   (or (when cbody
                                     (some-> (find-call cbody "name")
                                             :args first const-val))
                                   (:name mform-kv))
                     cl-default (or (when cbody
                                      (or (some-> (find-call cbody "defaultValue")
                                                  :args first const-val)
                                          (some-> (find-call cbody "defaultValue")
                                                  :args first :text)))
                                    (:defaultValue mform-kv))
                     ;; choices list — try in priority order:
                     ;;   (a) closure form `choices [...]` call;
                     ;;   (b) structured :map :entries lookup whose val
                     ;;       is itself a :list — map-arg-kv stringifies
                     ;;       lists, so we peek the raw :entries here;
                     ;;   (c) vector value from map-arg-kv (covers the
                     ;;       :other text path when the regex catches);
                     ;;   (d) regex over raw :other text — fallback for
                     ;;       the `choices: ['a','b']` named-arg shape
                     ;;       the scalar regex can't reach.
                     cl-choices (or (when cbody
                                      (let [choices-call (find-call cbody "choices")
                                            first-arg (some-> choices-call :args first)]
                                        (cond
                                          (and first-arg (= :list (:type first-arg)))
                                          (mapv #(or (const-val %) (:text %))
                                                (:items first-arg))

                                          first-arg
                                          [(or (const-val first-arg) (:text first-arg))])))
                                    (when (and (map? mform-arg)
                                               (= :map (:type mform-arg)))
                                      (some (fn [{:keys [key val]}]
                                              (when (and (= :const (:type key))
                                                         (= "choices" (str (:value key)))
                                                         (= :list (:type val)))
                                                (mapv #(or (const-val %) (:text %))
                                                      (:items val))))
                                            (:entries mform-arg)))
                                    (let [v (:choices mform-kv)]
                                      (when (vector? v) v))
                                    (when (and (map? mform-arg)
                                               (= :other (:type mform-arg)))
                                      (let [t (:text mform-arg "")
                                            list-match (re-find
                                                        #"choices\s*:\s*\[([^\]]*)\]" t)]
                                        (when list-match
                                          (->> (re-seq #"['\"]([^'\"]+)['\"]"
                                                       (nth list-match 1))
                                               (mapv second))))))]]
           (cond-> {:kind (keyword nm)
                    :raw-args (args->plain args)}
             cl-name        (assoc :name cl-name)
             cl-default     (assoc :default-value cl-default)
             (seq cl-choices) (assoc :choices (filterv some? cl-choices)))))))))

;; ---------------------------------------------------------------------------
;; AN8-1 — `tools { maven 'X' jdk 'Y' }` directive
;;
;; The declarative tools block names tool installations Jenkins's
;; "Manage Tools" UI knows how to provision (Maven, JDK, Gradle, etc).
;; anvil never provisions toolchains itself (per the v0.6 anti-goal:
;; "no anvil-managed JDK installer"). Instead the operator maps a
;; tool-spec to a pre-baked docker image via :anvil.tools/images in
;; anvil.edn; the dispatcher substitutes that image into the stage's
;; active-agent. Without a mapping, the dispatcher emits a
;; :tools/unmapped effect and the stage runs on the original agent.
;;
;; This translator step normalizes the tools block into a vector of
;; {:type :maven|:jdk|:gradle|:nodejs|... :version "X"} maps. The
;; dispatcher computes the image-lookup key from this shape.
;; ---------------------------------------------------------------------------

(defn- tool-version-from-call
  "Extract the version string from a single tools-block child call.
   The version can arrive as:
     (a) :const cdata — `maven 'maven_3_latest'`
     (b) :gstring cdata — `jdk \"${JAVA_VERSION}\"` (GString)
     (c) :var cdata — `jdk JDK_NAME` (bare identifier)
   For (b) and (c) we return the raw template / identifier text so
   the dispatcher's tools/unmapped effect carries diagnostic info.
   Operator mappings that explicitly include the template text (e.g.
   `\"jdk_${JAVA_VERSION}\"` → image) still match — the translator's
   contract is to expose the surface, not to interpret the runtime
   variable."
  [call]
  (let [a (first (:args call))]
    (cond
      (nil? a) nil
      (= :const (:type a)) (some-> (const-val a) str)
      (= :gstring (:type a)) (str (:text a))
      (= :var (:type a))
      ;; v0.6.2 — bare identifier may name a top-level `def`. Substitute
      ;; the resolved string when available; otherwise keep the legacy
      ;; behavior (return the var name verbatim so the operator can see
      ;; what was referenced in the :tools/unmapped diagnostic).
      (or (get *script-bindings* (:name a))
          (str (:name a)))
      :else (str (:text a)))))

(defn- translate-tools
  "Parse a `tools { … }` block into structured IR.

   Recognized at AN8-1:
     `maven 'X'`    → {:type :maven  :version \"X\"}
     `jdk 'Y'`      → {:type :jdk    :version \"Y\"}
     `gradle 'Z'`   → {:type :gradle :version \"Z\"}
     `nodejs 'N'`   → {:type :nodejs :version \"N\"}
   Any other named tool falls through as {:type (keyword name) :version ...}
   so the operator can map for tools we don't pre-register.

   Returns a vector of tool maps in declaration order. nil when the
   block is empty or has no recognizable children."
  [tools-call]
  (when tools-call
    (when-let [closure (closure-arg tools-call)]
      (let [body (body-calls closure)
            tools (->> body
                       (keep (fn [c]
                               (when-let [nm (:name c)]
                                 (let [v (tool-version-from-call c)]
                                   (cond-> {:type (keyword nm)}
                                     v (assoc :version v))))))
                       vec)]
        (when (seq tools) tools)))))

;; ---------------------------------------------------------------------------
;; AN7-2 — ${X} GString interpolation in declarative-pipeline string contexts
;;
;; Per R5 (board): scope strictly to declarative-pipeline string contexts:
;;   - agent { label "${X}" }
;;   - environment { KEY = "${X}..." } (via translate-environment regex path)
;;   - parameters { string(defaultValue: "${X}") } — recursive; not in scope
;;     for T1 (too rare)
;;
;; DO NOT touch sh '...' bodies — Groovy ${X} ≠ bash ${X}. The translator
;; must NEVER substitute inside a sh command string.
;;
;; Resolution logic:
;;   1. Extract every ${X} variable name from the GString template
;;   2. For each X, look up in the pipeline's parameters vector:
;;      - :string param with matching name + a non-blank :default-value
;;      - :choice param with matching name + a non-blank :default-value
;;        (or first choice as fallback)
;;   3. If ALL references resolved: substitute and return the static string
;;   4. If ANY reference unresolvable: return {:unresolved [X1 X2 ...]}
;;      so the caller can emit an honest :translator/unresolved-interpolation
;;      effect (AV5-6) rather than silently falling through to "<dynamic>".
;; ---------------------------------------------------------------------------

(defn- extract-gstring-vars
  "Extract the variable names from a GString cdata text.

   Groovy's GStringExpression.getText() normalizes variable references to
   the bare `$VARNAME` form (without curly braces), so the cdata text for
   `\"${PLATFORM}-${ARCH}\"` arrives as `\"$PLATFORM-$ARCH\"`.

   This function extracts the variable names from both forms:
     - `${VARNAME}` (curly-brace form, as written by the developer)
     - `$VARNAME`   (bare form, as emitted by Groovy's getText())

   Returns a vector of distinct variable name strings.
   Examples:
     '$PLATFORM'         → ['PLATFORM']
     '$PLATFORM-$ARCH'   → ['PLATFORM' 'ARCH']
     '${PLATFORM}${JDK}' → ['PLATFORM' 'JDK']   (curly-brace if present)
     '${1}'              → []                    (numeric not a valid Groovy ident)
     'linux-build'       → []                    (no variables)"
  [text]
  (let [t (str text)
        ;; Match both ${VARNAME} (with braces) and $VARNAME (bare form).
        ;; The alternation lists the braced form first so it takes priority
        ;; when both could match at the same position.
        ;;
        ;; The braced form uses `[A-Za-z_]\w*` (not `\w+`) so numeric tokens
        ;; like `${1}` aren't treated as variable names — Groovy doesn't
        ;; either. Copilot review on #83 flagged the earlier `\w+`.
        matches (re-seq #"\$\{([A-Za-z_]\w*)\}|\$([A-Za-z_]\w*)" t)]
    (->> matches
         (mapv (fn [[_ braced bare]] (or braced bare)))
         distinct
         vec)))

(defn- resolve-param-value
  "Look up parameter `param-name` in `parameters` vector. Returns the
   static string value to substitute, or nil if not resolvable.

   Resolution order:
     1. :string param → :default-value (if non-blank)
     2. :choice param → :default-value (if non-blank)
     3. :choice param → first of :choices (if present)
     4. nil (unresolvable)"
  [param-name parameters]
  (when (seq parameters)
    (some (fn [{:keys [kind name default-value choices]}]
            (when (= name param-name)
              (cond
                (and (#{:string :choice} kind)
                     (string? default-value)
                     (not (str/blank? default-value)))
                default-value

                (and (= kind :choice) (seq choices))
                (first choices)

                :else nil)))
          parameters)))

(defn- interpolate-gstring
  "AN7-2 — substitute `${X}` references in a GString template using the
   pipeline's parsed parameters.

   `gstring-text` — the text from a :gstring cdata node
                    (e.g. '${PLATFORM}' or 'linux-${ARCH}-java${JDK}')
   `parameters`   — vector of parsed param specs from translate-parameters

   Returns:
     {:resolved <static-string>}
       — all references substituted
     {:unresolved [<name> ...]}
       — at least one reference could not be resolved; the named variables
         are listed so the dispatcher can emit an honest effect"
  [gstring-text parameters]
  (let [vars (extract-gstring-vars gstring-text)]
    (if (empty? vars)
      ;; No ${X} refs — treat as a literal string (bare GString without
      ;; variable references, which is unusual but valid Groovy)
      {:resolved gstring-text}
      (let [resolutions (reduce (fn [acc var-name]
                                  (let [v (resolve-param-value var-name parameters)]
                                    (assoc acc var-name v)))
                                {}
                                vars)
            unresolved (filterv #(nil? (get resolutions %)) vars)]
        (if (seq unresolved)
          {:unresolved unresolved}
          ;; All resolved — do the substitution.
          ;; The GString text uses the bare `$VARNAME` form (no curly braces)
          ;; from Groovy's getText(). Replace $VARNAME with the resolved value.
          ;; Use a word-boundary pattern to avoid substituting `$PLATFORM` in
          ;; `$PLATFORM_X` as if it were `$PLATFORM`.
          ;;
          ;; The replacement string MUST be quoted via Matcher/quoteReplacement
          ;; because Java's regex-replace treats `$` and `\` specially in
          ;; replacements (`$1` is a group ref, `\$` is an escaped literal).
          ;; A parameter default that happens to contain `$` (e.g. shell
          ;; snippet, file path with version like `lib-1.0$RC`) would
          ;; otherwise throw `IllegalArgumentException` at runtime or
          ;; silently mis-substitute. Copilot review on #83 flagged this.
          {:resolved (reduce
                      (fn [s var-name]
                        (str/replace s
                                     (java.util.regex.Pattern/compile
                                      (str "\\$\\{" var-name "\\}|\\$" var-name "(?!\\w)"))
                                     (java.util.regex.Matcher/quoteReplacement
                                      (str (get resolutions var-name)))))
                      gstring-text
                      vars)})))))

(defn- resolve-param-driven-label
  "AN6-1 — given a nested label closure (the inner `label { … }` whose
   body re-calls `label params.X`) and the pipeline's parsed
   parameters, return the static label name the build will run on.

   The board says: prefer the choice's defaultValue; otherwise fall
   back to the first listed choice. If neither is parseable (or
   there's no matching choice param), return nil so the caller
   degrades honestly.

   Returns `{:chosen <str> :source :default-value | :first-choice
   :param-name <str>}` or nil."
  [inner-label-call parameters]
  (let [arg (first (:args inner-label-call))
        ;; `params.nodeLabel` is a Groovy PropertyExpression. The cdata
        ;; either has it as :var with name "params.X" (some paths) or
        ;; falls into the :other catch-all whose :text is the AST
        ;; .toString(). Both surface "params.X" as a substring; a
        ;; regex over either form is the lowest-risk extractor.
        ref-text (cond
                   (= :var (:type arg))   (or (:name arg) "")
                   (= :other (:type arg)) (or (:text arg) "")
                   :else "")
        ;; First try the simple `params.X` shape (some cdata paths
        ;; surface the property reference directly). Fall back to the
        ;; Groovy PropertyExpression .toString() form which carries
        ;; `variable: params … property: …ConstantExpression@HH[X]`.
        param-ref (or (some-> (re-find #"params\.(\w+)" ref-text) second)
                      (when (re-find #"variable:\s*params\b" ref-text)
                        (some-> (re-find #"property:\s*org\.codehaus\.groovy\.ast\.expr\.ConstantExpression@\w+\[([^\]]+)\]"
                                         ref-text)
                                second)))
        match (when param-ref
                (some (fn [{:keys [kind name] :as p}]
                        ;; AN6-1 looked at :choice only; AN7-2 extends to :string
                        (when (and (#{:choice :string} kind) (= name param-ref))
                          p))
                      parameters))
        choices (:choices match)
        default-value (:default-value match)]
    (cond
      (and match default-value)
      {:chosen default-value :source :default-value :param-name param-ref}

      (and match (seq choices))
      {:chosen (first choices) :source :first-choice :param-name param-ref}

      :else nil)))

;; ---------------------------------------------------------------------------
;; v0.6 T1 — kubernetes agent helpers
;; ---------------------------------------------------------------------------

(defn- parse-memory-mb
  "Parse a Jenkins-style memory string (\"512Mi\", \"1Gi\", \"4G\", \"512M\",
   bare digits = MB) into a Long count of MB. Returns nil on unparseable
   input — the dispatcher's degrade path catches that and continues
   without a resource-limit hint rather than crashing the build."
  [s]
  (when-let [s (and s (str/trim (str s)))]
    (when-not (str/blank? s)
      (let [m (re-matches #"(?i)(\d+)([gmki]+)?" s)]
        (when m
          (let [n (Long/parseLong (nth m 1))
                unit (str/lower-case (or (nth m 2) ""))]
            (case unit
              ("" "m" "mi")   n
              ("g" "gi")      (* n 1024)
              ("k" "ki")      (long (Math/ceil (/ n 1024.0)))
              nil)))))))

(defn- parse-cpus
  "Parse a Jenkins-style CPU string (\"500m\" = 0.5 cores, \"2\" = 2 cores,
   \"1.5\" = 1.5 cores) into a Double count of cores. Returns nil on
   unparseable input."
  [s]
  (when-let [s (and s (str/trim (str s)))]
    (when-not (str/blank? s)
      (try
        (cond
          (str/ends-with? s "m")
          (/ (Double/parseDouble (subs s 0 (- (count s) 1))) 1000.0)

          :else
          (Double/parseDouble s))
        (catch Exception _ nil)))))

(defn- extract-k8s-yaml-hints
  "Light-weight regex extraction of operator-relevant hints from a
   Jenkins KubernetesPipeline YAML payload. Pulls out:

     - the first container image (most yaml shapes declare a single
       primary container — operators with multi-container pods are on
       a path we don't promise to honor in T1.3)
     - the namespace (if explicitly declared in metadata)
     - resource limits (memory + cpu) from the first container's
       resources.limits map

   Pure regex — we don't take a clj-yaml dep just for the agent path,
   and YAML's whitespace shape is stable enough across Jenkins examples
   for this to land 95% of the wild-corpus cases. When regex misses, the
   dispatcher's degrade reason explains the miss instead of pretending.

   Returns a map (possibly empty); callers `merge` it onto the
   :kubernetes IR."
  [yaml-text]
  (let [img (some->> yaml-text
                     (re-find #"(?m)^\s*image:\s*['\"]?([^\s'\"]+)['\"]?")
                     second)
        ns  (some->> yaml-text
                     (re-find #"(?m)^\s*namespace:\s*['\"]?([^\s'\"]+)['\"]?")
                     second)
        ;; Match `resources:` then `limits:` then the next two indented
        ;; entries (memory + cpu). We don't try to disambiguate
        ;; requests-vs-limits — limits is what chengis-core's k8s
        ;; backend gets a cap from, and Jenkins's KubernetesPipeline
        ;; almost universally uses `limits:` over `requests:`.
        limits-section (second (re-find #"(?ms)limits:\s*\n((?:\s+\w+:.*\n?){1,8})"
                                        (or yaml-text "")))
        memory (when limits-section
                 (second (re-find #"(?m)^\s*memory:\s*['\"]?([^\s'\"]+)['\"]?"
                                  limits-section)))
        cpu    (when limits-section
                 (second (re-find #"(?m)^\s*cpu:\s*['\"]?([^\s'\"]+)['\"]?"
                                  limits-section)))
        memory-mb (parse-memory-mb memory)
        cpus      (parse-cpus cpu)]
    (cond-> {}
      img        (assoc :image img)
      ns         (assoc :namespace ns)
      (or memory-mb cpus)
      (assoc :resource-limits
             (cond-> {}
               memory-mb (assoc :memory-mb memory-mb)
               cpus      (assoc :cpus cpus))))))

(defn- translate-agent-block
  "agent { label '…' }, agent { docker { image '…' } }, etc.

   With `parameters` non-nil (v0.4 AN6-1), the nested-label-with-
   params form

     agent {
       label {
         label params.nodeLabel
       }
     }

   is resolved to the parameter's defaultValue (or first choice) so
   the agent is honored statically instead of falling through to
   LocalShell with `:label \"<dynamic>\"`.  Activemq + every other
   build that uses the node-label-parameter plugin shape benefits.

   When resolution can't happen (no parameters block, no matching
   choice param, no choices) we emit
   `{:label \"<dynamic>\" :degrade-reason :param-driven-label}`
   so the dispatcher records a clearer effect than the generic
   no-agents.edn-entry warn."
  ([agent-call] (translate-agent-block agent-call nil))
  ([agent-call parameters]
  (let [closure (closure-arg agent-call)]
    (if-not closure
      ;; agent any | agent none — the symbol comes as a :var
      (let [v (first (:args agent-call))]
        {:type (keyword (or (some-> v const-val str)
                            (:name v)
                            "any"))})
      ;; agent { … }
      (let [body (body-calls closure)
            label-call     (find-call body "label")
            node-call      (find-call body "node")
            docker-call    (find-call body "docker")
            dockerfile-call (find-call body "dockerfile")
            k8s-call       (find-call body "kubernetes")]
        (cond
          label-call
          (let [first-arg (first (:args label-call))
                static (const-val first-arg)
                ;; v0.4 AN6-1 — nested-label form: the first arg is a
                ;; :closure containing `label params.X`. Resolve via
                ;; the pipeline's parameters when possible.
                nested-inner (when (and (nil? static) (closure? first-arg))
                               (let [inner-body (body-calls first-arg)]
                                 (find-call inner-body "label")))
                inferred (when nested-inner
                           (resolve-param-driven-label nested-inner parameters))
                ;; AN7-2 — GString interpolation form:
                ;;   agent { label "${PLATFORM}" }
                ;; The first arg is a :gstring node (Groovy double-quoted
                ;; string with ${X} variable references). Scope-limited
                ;; to the declarative agent { label } context per R5.
                gstring-text (when (and (nil? static) (nil? nested-inner)
                                        (map? first-arg)
                                        (= :gstring (:type first-arg)))
                               (:text first-arg))
                gstring-result (when gstring-text
                                 (interpolate-gstring gstring-text parameters))
                ;; v0.6.2 — bare-var form: `agent { label AGENT_LABEL }`
                ;; where AGENT_LABEL is a top-level `def`. If extract-top-
                ;; level-defs resolved the var, use that value; else fall
                ;; through to the existing `<dynamic>` behavior.
                var-name (when (and (nil? static) (nil? nested-inner)
                                    (nil? gstring-text)
                                    (map? first-arg)
                                    (= :var (:type first-arg)))
                           (:name first-arg))
                var-binding (when var-name (get *script-bindings* var-name))]
            (cond
              static
              {:label static}

              inferred
              {:label (:chosen inferred)
               :inferred-from {:param-name (:param-name inferred)
                               :source (:source inferred)}}

              nested-inner
              {:label "<dynamic>"
               :degrade-reason :param-driven-label}

              ;; AN7-2 — GString resolved: all ${X} refs had param defaults
              (and gstring-result (:resolved gstring-result))
              {:label (:resolved gstring-result)
               :interpolated-from gstring-text}

              ;; AN7-2 — GString unresolvable: honest fallback per AV5-6
              ;; The dispatcher emits :translator/unresolved-interpolation
              ;; when it sees this annotation.
              (and gstring-result (:unresolved gstring-result))
              {:label "<unresolved-interpolation>"
               :gstring-template gstring-text
               :unresolved-vars (:unresolved gstring-result)
               :degrade-reason :unresolved-interpolation}

              ;; v0.6.2 — bare-var resolved via top-level `def` binding.
              var-binding
              {:label var-binding
               :resolved-from {:script-binding var-name}}

              :else
              {:label "<dynamic>"}))

          node-call
          (let [n-body (body-calls (closure-arg node-call))
                lbl (some-> (find-call n-body "label") :args first const-val)]
            {:type :node-label :label (or lbl "<dynamic>")})

          docker-call
          (let [d-body (body-calls (closure-arg docker-call))
                image (some-> (find-call d-body "image") :args first const-val)
                args  (some-> (find-call d-body "args") :args first const-val)]
            (cond-> {:docker {:image (or image "<dynamic>")}}
              args (assoc-in [:docker :args] args)))

          dockerfile-call
          ;; v0.6 T3 — multi-stage dockerfile support.
          ;; agent { dockerfile { filename '...' dir '...' args '--target prod' } }
          ;;
          ;; Behind :anvil.features/dockerfile-multistage. When the args
          ;; string carries `--target X` (or `--target=X`) we lift the
          ;; stage name into the IR so the dispatcher can forward it to
          ;; `docker build --target X` and incorporate it into the image
          ;; cache key. Any other tokens in args ride through verbatim.
          (let [df-body (body-calls (closure-arg dockerfile-call))
                filename (or (some-> (find-call df-body "filename")
                                     :args first const-val)
                             "Dockerfile")
                dir      (some-> (find-call df-body "dir")
                                 :args first const-val)
                args-str (some-> (find-call df-body "args")
                                 :args first const-val)
                ;; additionalBuildArgs '...' is the Jenkins Dockerfile
                ;; Pipeline name for the same idea; accept either spelling.
                addl-str (some-> (find-call df-body "additionalBuildArgs")
                                 :args first const-val)
                combined-args (cond
                                (and args-str addl-str) (str args-str " " addl-str)
                                args-str args-str
                                addl-str addl-str)
                target (when combined-args
                         (or (second (re-find #"--target=([^\s]+)" combined-args))
                             (second (re-find #"--target\s+([^\s]+)" combined-args))))]
            {:dockerfile (cond-> {:filename filename}
                           dir           (assoc :dir dir)
                           combined-args (assoc :args combined-args)
                           target        (assoc :target target))})

          k8s-call
          ;; v0.6 T1.3/T1.4 — `agent { kubernetes { yaml '...' } }` and
          ;; `agent { kubernetes { containerTemplate(...) } }`. Both
          ;; forms feed chengis-core 0.4's K8sBackend; the dispatcher
          ;; (via backend-wiring) extracts the chosen image + resource
          ;; hints and constructs the backend at step-execution time.
          ;;
          ;; Shape returned (matching :docker / :dockerfile keys):
          ;;   {:kubernetes {:yaml STRING?
          ;;                 :container-template {:image …
          ;;                                      :name …
          ;;                                      :resource-limits {…}}
          ;;                 :image STRING?     ; extracted convenience
          ;;                 :namespace STRING? ; from yaml/template
          ;;                 :raw-form :yaml | :container-template}}
          ;;
          ;; When neither form is present (just `kubernetes { }` with
          ;; no body), we emit a degrade-friendly marker so the
          ;; dispatcher records :runtime-unsupported rather than
          ;; failing opaquely.
          (let [k-body (when (closure-arg k8s-call)
                         (body-calls (closure-arg k8s-call)))
                yaml-call (find-call k-body "yaml")
                ct-call   (find-call k-body "containerTemplate")]
            (cond
              yaml-call
              (let [yaml-text (some-> (first (:args yaml-call)) const-val)]
                {:kubernetes (cond-> {:raw-form :yaml}
                               yaml-text (assoc :yaml yaml-text)
                               yaml-text (merge (extract-k8s-yaml-hints yaml-text)))})

              ct-call
              (let [kv (map-arg-kv (first (:args ct-call)))
                    image (some-> (:image kv) str)
                    name- (some-> (:name kv) str)
                    memory-mb (parse-memory-mb (:resourceLimitMemory kv))
                    cpus (parse-cpus (:resourceLimitCpu kv))]
                {:kubernetes
                 (cond-> {:raw-form :container-template
                          :container-template (into {} (filter val
                                                        {:image image
                                                         :name name-
                                                         :memory-request (:resourceRequestMemory kv)
                                                         :cpu-request (:resourceRequestCpu kv)
                                                         :memory-limit (:resourceLimitMemory kv)
                                                         :cpu-limit (:resourceLimitCpu kv)}))}
                   image (assoc :image image)
                   (or memory-mb cpus)
                   (assoc :resource-limits
                          (cond-> {}
                            memory-mb (assoc :memory-mb memory-mb)
                            cpus      (assoc :cpus cpus))))})

              :else
              ;; Empty `kubernetes { }` body — honest degrade marker.
              {:kubernetes {:raw-form :unknown}
               :degrade-reason :k8s-empty-block}))

          :else
          {:type :unknown :raw "<unrecognized agent block>"}))))))

(defn- translate-stage
  [stage-call source closure-objs]
  (let [stage-name (or (const-val (first (:args stage-call))) "<dynamic>")
        body-closure (closure-arg stage-call)
        body (when body-closure (body-calls body-closure))
        steps-call (find-call body "steps")
        post-call  (find-call body "post")
        agent-call (find-call body "agent")
        env-call   (find-call body "environment")
        ;; AN8-1: stage-level tools { … } directive, overriding the
        ;; pipeline-level tools for this stage. The dispatcher merges
        ;; stage > pipeline at stage-enter time.
        tools-call (find-call body "tools")
        ;; AN5-6: declarative matrix-block-inside-stage. apache-camel and
        ;; apache-cxf put `matrix { axes {} stages {} }` directly inside
        ;; a stage body (no top-level `steps` call). Before AN5-6 this
        ;; produced `{:name X :steps []}` which the classifier read as
        ;; `:unsupported/:body-skipped`. We detect the matrix child and
        ;; attach the parsed matrix IR; `translate-stages` (below)
        ;; expands cells into one materialized stage per cell.
        matrix-call (find-call body "matrix")
        ;; v0.4 AN6-2: nested `stages { }` inside a stage body.
        ;; eclipse-epsilon uses `stages { stage('Main') { stages { … } } }`
        ;; for grouping; apache-cxf uses matrix → stages → stage →
        ;; nested stages for tooling-scope. Before AN6-2 both
        ;; classified as :unsupported/:body-skipped because the
        ;; wrapper stage carried `[]` for :steps. We detect the
        ;; nested stages child and attach its parsed children;
        ;; `translate-stages` flattens, prefixing each child name
        ;; with the wrapper name and propagating the wrapper's
        ;; agent/env/post (Jenkins's declarative-pipeline semantics).
        nested-stages-call (find-call body "stages")
        steps (when steps-call
                (translate-steps-body (closure-arg steps-call) source closure-objs))
        post (when post-call
               (translate-post-body (closure-arg post-call) source closure-objs))
        matrix-ir (when matrix-call
                    (mx-decl/parse-matrix-call
                     matrix-call stage-name
                     ;; Inner-stage parser closes over our translator
                     ;; bindings so matrix.stages bodies get the same
                     ;; treatment as top-level stages (including
                     ;; recursive matrix expansion if anyone nests).
                     (fn [stages-call]
                       (translate-stages stages-call source closure-objs))
                     ;; AN8-3: matrix-level `tools { … }` block (sibling
                     ;; of axes / stages — zookeeper's real shape). The
                     ;; parser hands back the raw `tools` :call cdata;
                     ;; translate-tools normalizes it to the same IR
                     ;; shape pipeline/stage tools use.
                     translate-tools))
        nested-children (when nested-stages-call
                          (translate-stages nested-stages-call source closure-objs))]
    (cond-> {:name stage-name :steps (vec (or steps []))}
      agent-call (assoc :agent (translate-agent-block agent-call))
      env-call   (assoc :environment (translate-environment (closure-arg env-call) source closure-objs))
      post       (assoc :post post)
      matrix-ir  (assoc :matrix matrix-ir)
      tools-call (assoc :tools (translate-tools tools-call))
      nested-children (assoc :children nested-children))))

(defn- expand-matrix-stage
  "AN5-6: a stage carrying a `:matrix` IR becomes N materialized
   cell-stages — one per axis combination. Each cell's display name
   includes the axis tuple; each cell's steps are the concatenation
   of the inner matrix.stages' steps; each cell's `:environment`
   merges the parent stage's environment with the cell's axis values
   (axis values lose to explicit parent env to match Jenkins's
   precedence). The parent stage's `:agent` and `:post` are
   propagated to every cell.

   AN8-3: matrix-level `tools { … }` (sibling of axes/stages) rides
   along on each cell as `:tools`; the parent stage's `:tools` (if
   any) provides the BASE — matrix tools win on collision per the
   composition rule (more-specific declaration overrides less). The
   cell's `:axes` map rides along so the dispatcher can interpolate
   `${JAVA_VERSION}` etc. in tool-version templates before AN8-1's
   image-lookup runs.

   This is the v0.3.3 first cut: cells run serially in IR order, no
   per-cell agent override. v0.4 may add per-cell agent and parallel
   execution; the IR shape stays stable."
  [parent-stage matrix-ir]
  (let [cells (mx-decl/expand-matrix matrix-ir)
        parent-env (or (:environment parent-stage) {})
        parent-agent (:agent parent-stage)
        parent-post (:post parent-stage)
        parent-tools (:tools parent-stage)]
    (mapv
     (fn [{:keys [axes env stages tools]}]
       (let [cell-label (mx-decl/cell-name axes)
             cell-name (str (:name parent-stage) " [" cell-label "]")
             ;; AN8-3b — substitute `${AXIS}` / `$AXIS` refs in every step
             ;; body BEFORE flattening to all-steps. Without this, real
             ;; Jenkinsfiles (apache-camel) hit MissingPropertyException
             ;; in the Groovy script evaluator because PLATFORM / JDK_NAME
             ;; aren't bound in the runtime. Substitution is a per-cell
             ;; rewrite of the IR — each cell sees its own axis values.
             interpolated-stages (mx-decl/interpolate-stage-steps stages axes)
             all-steps (vec (mapcat :steps interpolated-stages))
             ;; AN8-3 composition: parent-stage tools provide the base;
             ;; matrix-level tools (the more-specific declaration) win on
             ;; collision. Tools are vectors of {:type :version} maps —
             ;; merge by :type so `parent {maven X} + matrix {jdk Y}` →
             ;; `[maven X, jdk Y]`, but `parent {jdk OLD} + matrix
             ;; {jdk NEW}` → `[jdk NEW]`.
             effective-tools (when (or (seq parent-tools) (seq tools))
                               (let [by-type (reduce (fn [m t]
                                                       (assoc m (:type t) t))
                                                     {}
                                                     (concat (or parent-tools [])
                                                             (or tools [])))]
                                 (vec (vals by-type))))]
         (cond-> {:name cell-name
                  :steps all-steps
                  :environment (merge env parent-env)}
           parent-agent (assoc :agent parent-agent)
           parent-post (assoc :post parent-post)
           (seq effective-tools) (assoc :tools effective-tools)
           ;; The cell's axes ride along so agent.clj can pass them
           ;; through to the synthetic :jenkins/agent-stage-enter step.
           (seq axes) (assoc :matrix-axes axes))))
     cells)))

(defn- expand-nested-stages-stage
  "v0.4 AN6-2: a stage carrying `:children` from a nested `stages { }`
   block becomes N materialized sibling stages, one per inner child.
   The wrapper's name prefixes each child's display name
   (\"Main / Build\", \"Main / Test\") so the build console + classifier
   keep the grouping visible.

   The wrapper's agent / environment / post propagate to every child
   (Jenkins declarative-pipeline semantics: a wrapper agent applies
   to every nested stage; a wrapper post runs once per child). If
   a child already declares the same field, the child wins —
   matching Jenkins's stage-level override precedence.

   Children that themselves carry `:matrix` or `:children` recurse
   through `translate-stages` already, so deeper nesting (cxf's
   matrix→stage→stages→stage shape) flattens correctly in one
   pass."
  [parent-stage]
  (let [children (:children parent-stage)
        parent-name (:name parent-stage)
        parent-agent (:agent parent-stage)
        parent-env (or (:environment parent-stage) {})
        parent-post (:post parent-stage)
        parent-tools (:tools parent-stage)]
    (mapv
     (fn [child]
       (let [child-name (str parent-name " / " (:name child))]
         (cond-> (assoc child :name child-name)
           (and parent-agent (not (:agent child)))
           (assoc :agent parent-agent)

           (seq parent-env)
           (update :environment #(merge parent-env (or % {})))

           (and parent-post (not (:post child)))
           (assoc :post parent-post)

           ;; AN8-1: parent's :tools propagate to children unless the
           ;; child declares its own. Mirrors Jenkins's wrapper-stage
           ;; semantics — apache-struts' `JDK 21 { tools { … } stages
           ;; { stage('Test') { … } } }` wants `Test` to inherit the
           ;; jdk_21 tools spec without re-declaring it.
           (and (seq parent-tools) (not (:tools child)))
           (assoc :tools parent-tools))))
     children)))

(defn- translate-stages
  [stages-call source closure-objs]
  (let [closure (closure-arg stages-call)
        body (body-calls closure)]
    (->> body
         (filter #(= "stage" (:name %)))
         (mapcat (fn [stage-call]
                   (let [stage (translate-stage stage-call source closure-objs)]
                     (cond
                       (:matrix stage)
                       (expand-matrix-stage stage (:matrix stage))

                       ;; v0.4 AN6-2 — nested stages flatten before
                       ;; matrix expansion (cxf's matrix-stage-stages
                       ;; chain: the matrix's inner stage may itself
                       ;; have nested stages, which we flatten here
                       ;; via the recursive translate-stages call in
                       ;; translate-stage).
                       (:children stage)
                       (expand-nested-stages-stage stage)

                       :else
                       [stage]))))
         vec)))

;; ---------------------------------------------------------------------------
;; Scripted Pipeline support
;;
;; Scripted Jenkinsfiles call `stage('name') { … }` directly at script top
;; level — usually inside `node(...) { … }`, `parallel { … }`, an axes
;; combinations closure, or freestanding. There is no `pipeline {}` wrapper.
;;
;; We walk the raw Groovy AST with CodeVisitorSupport, which descends into
;; every nested expression (BinaryExpression for `builds[k] = { … }`,
;; closures inside list/map literals, etc.), and collect every
;; MethodCallExpression that looks like a stage call. Each one becomes a
;; stage IR node; its body closure is translated as a sequence of steps
;; the same way declarative steps {} bodies are.
;; ---------------------------------------------------------------------------

(defn- mce-args-list
  "Return the arg expressions of an MCE as a Clojure vector regardless of
   whether the argument node is an ArgumentListExpression or a bare
   TupleExpression."
  [^MethodCallExpression mc]
  (let [args (.getArguments mc)]
    (cond
      (instance? ArgumentListExpression args)
      (vec (.getExpressions ^ArgumentListExpression args))

      (instance? TupleExpression args)
      (vec (.getExpressions ^TupleExpression args))

      :else
      [args])))

(defn- mce-method-name [^MethodCallExpression mc]
  (let [m (.getMethod mc)]
    (when (instance? ConstantExpression m)
      (str (.getValue ^ConstantExpression m)))))

(defn- stage-mce?
  "True iff `mc` looks like `stage(<name>) { <closure> }`: method name is
   the literal 'stage' and the arg list contains at least one
   ClosureExpression (the body)."
  [^MethodCallExpression mc]
  (and (= "stage" (mce-method-name mc))
       (boolean (some #(instance? ClosureExpression %) (mce-args-list mc)))))

(defn- collect-stage-mces
  "Walk every node reachable from `top-statements` and return
   `MethodCallExpression`s that match `stage-mce?`, in source order.

   Uses CodeVisitorSupport so we descend through BinaryExpressions
   (`builds[k] = { … }`), closures nested inside list/map literals, if
   branches, and so on — places the :cdata view doesn't reach.

   We descend manually instead of calling `proxy-super`: Clojure's
   `proxy-super` flips a thread-local that disables the override during
   the super call, which would silently skip nested method-call
   expressions and lose any stage() calls beneath the first one."
  [top-statements]
  (let [out (volatile! [])
        v (proxy [CodeVisitorSupport] []
            (visitMethodCallExpression [^MethodCallExpression mc]
              (when (stage-mce? mc)
                (vswap! out conj mc))
              (let [^GroovyCodeVisitor self this]
                (.visit (.getObjectExpression mc) self)
                (.visit (.getMethod mc) self)
                (.visit (.getArguments mc) self))))]
    (doseq [stmt top-statements]
      (when (instance? ASTNode stmt)
        (.visit ^ASTNode stmt v)))
    @out))

(defn- translate-scripted-stage
  "Translate a single scripted `stage(name) { body }` MCE into a stage IR.

   The body closure is treated as a flat sequence of steps (no
   declarative `steps {} / agent {} / environment {}` wrapper). Each
   method call in the body goes through the normal step dispatcher, so
   scope wrappers like node/dir/withCredentials/timeout/retry recurse
   correctly. Non-method-call statements (def, if, =) are not surfaced —
   their nested stage(...) calls are caught separately by the visitor."
  [^MethodCallExpression stage-mce source closure-objs]
  (let [cdata (g/->cdata stage-mce)
        name (arg-name->str (first (:args cdata)))
        body-closure (closure-arg cdata)
        steps (when body-closure
                (translate-steps-body body-closure source closure-objs))]
    {:name name :steps (vec (or steps []))}))

;; ---------------------------------------------------------------------------
;; @Library annotation detection
;;
;; `@Library('foo@bar') _` parses as an annotation on a top-level
;; declaration. For TX3 we just detect its presence and capture the
;; coordinate; rewriting to chengis-core :import happens in TX5.
;; ---------------------------------------------------------------------------

(defn- detect-libraries
  "Quick regex scan of the source for @Library('coord') or @Library(['…','…']).
   AST-level extraction is more correct but this is honest for v1."
  [source]
  (let [single (re-seq #"@Library\s*\(\s*['\"]([^'\"]+)['\"]" source)
        list-r (re-seq #"@Library\s*\(\s*\[([^\]]+)\]" source)
        coords (concat (map second single)
                       (mapcat (fn [m]
                                 (->> (str/split (second m) #",")
                                      (map #(-> % (str/replace #"['\"\s]" "")))
                                      (remove str/blank?)))
                               list-r))]
    (mapv (fn [c]
            (let [[name version] (str/split c #"@" 2)]
              (cond-> {:name name}
                version (assoc :version version))))
          coords)))

;; ---------------------------------------------------------------------------
;; Top-level entry
;; ---------------------------------------------------------------------------

(defn parse
  "Parse a Jenkinsfile source string into a Jenkins IR map.

   Returns an IR matching the schema in `anvil.compat.jenkins.ir`. On a
   total parse failure (the source isn't valid Groovy / has no
   `pipeline {}` block), returns an IR with an empty :stages vector and
   a :parse-error entry. The caller (importer / runtime) decides what to
   do with it."
  ([^String source] (parse source nil))
  ([^String source source-path]
   (try
     (let [ast (g/parse-groovy-ast source)
           top-statements (g/flatten-top-statements ast)
           top-calls (keep #(when-let [c (g/statement->call-public %)] c) top-statements)
           pipeline-mc (first (filter #(= "pipeline" (g/method-name-public %)) top-calls))
           ;; v0.6.2 — extract top-level `def NAME = expr` script bindings
           ;; so label / tools / sh references inside the pipeline can
           ;; resolve statically.
           {:keys [resolved unresolved]} (extract-top-level-defs top-statements)]
       (binding [*script-bindings* resolved
                 *script-bindings-unresolvable* unresolved]
       (if-not pipeline-mc
         ;; No declarative `pipeline {}` block — try scripted-Pipeline
         ;; extraction. Walks the AST collecting `stage('name') { body }`
         ;; calls regardless of nesting depth. If none are found, fall
         ;; back to the historical empty/tagged IR so the importer can
         ;; still distinguish "we found nothing" from "we parsed it but
         ;; it has no stages".
         (let [stage-mces (collect-stage-mces top-statements)
               libs       (detect-libraries source)
               ;; Tier-3 path: when :anvil.features/scripted-eval is on,
               ;; bypass the static-IR translation and emit a single
               ;; scripted-eval step carrying the whole source. The
               ;; dispatcher runs it through Groovy + anvil's expanded
               ;; Pipeline DSL bindings so GStrings, combinations,
               ;; destructuring, etc. all work natively.
               scripted-eval? (try ((requiring-resolve 'anvil.features/enabled?)
                                    :scripted-eval)
                                   (catch Throwable _ false))]
           (cond
             ;; Tier-3 fires for any scripted Jenkinsfile with executable
             ;; content (non-blank source) — not just ones with literal
             ;; `stage('x')` calls. Real-world Jenkinsfiles often delegate
             ;; everything to a shared library (`buildPlugin(...)`,
             ;; `mavenBuild(...)`) so there are zero literal stages in
             ;; the source — but the call IS the pipeline. Routing them
             ;; to scripted-eval lets methodMissing record the shared-lib
             ;; call as `:jenkins/shared-lib-unresolved` instead of
             ;; silently returning a vacuous 0-stage SUCCESS.
             (and scripted-eval? (some? (some #(re-find #"\S" %)
                                              (clojure.string/split-lines
                                               (or source "")))))
             (ir/pipeline (cond-> {:source-path source-path
                                   :stages [{:name "(scripted-eval)"
                                             :steps [{:type :jenkins/scripted-eval
                                                      :source source}]}]
                                   :options [{:scripted-pipeline? true
                                              :scripted-eval? true}]}
                            (seq libs) (assoc :libraries libs)))

             :else
             (let [closure-objs
                   (into {}
                         (mapcat (fn [^MethodCallExpression smc]
                                   (let [cdata (g/->cdata smc)
                                         cdata-closures (collect-cdata-closures cdata)
                                         stage-exprs (g/collect-closure-expressions [smc])]
                                     (map vector cdata-closures stage-exprs)))
                                 stage-mces))
                   stages (mapv #(translate-scripted-stage
                                  % source closure-objs)
                                stage-mces)]
               (if (seq stages)
                 (ir/pipeline (cond-> {:source-path source-path
                                       :stages stages
                                       :options [{:scripted-pipeline? true}]}
                                (seq libs) (assoc :libraries libs)))
                 (ir/pipeline {:source-path source-path
                               :stages []
                               :options [{:parse-error :no-pipeline-block}]})))))

         (let [pipeline-cdata (g/->cdata pipeline-mc)
               pipeline-body (-> pipeline-cdata :args first :body)
               body-call-list (filter call? pipeline-body)
               closure-objs (closure-cdata→expr-map ast pipeline-cdata)

               agent-call   (find-call body-call-list "agent")
               stages-call  (find-call body-call-list "stages")
               post-call    (find-call body-call-list "post")
               env-call     (find-call body-call-list "environment")
               tools-call   (find-call body-call-list "tools")
               options-call (find-call body-call-list "options")
               triggers-call (find-call body-call-list "triggers")
               params-call  (find-call body-call-list "parameters")
               libs         (detect-libraries source)]
           (ir/pipeline
            {:source-path source-path
             ;; v0.4 AN6-1 — parse parameters BEFORE the agent so the
             ;; nested-label-with-params form (apache-activemq) can
             ;; resolve its label statically.
             :parameters (translate-parameters params-call)
             :agent (when agent-call
                      (translate-agent-block agent-call
                                             (translate-parameters params-call)))
             :stages (when stages-call (translate-stages stages-call source closure-objs))
             :post   (when post-call (translate-post-body (closure-arg post-call) source closure-objs))
             :environment (when env-call (translate-environment (closure-arg env-call) source closure-objs))
             :tools   (when tools-call    (translate-tools tools-call))
             :options (let [base (vec (when options-call [{:raw "<options block>"}]))
                            ;; v0.6.2 — surface top-level script bindings on
                            ;; the IR so operators see what was resolved (and
                            ;; what wasn't). Consumers that ignore unknown
                            ;; option entries are unaffected.
                            with-bindings (cond-> base
                                            (seq *script-bindings*)
                                            (conj {:script-bindings *script-bindings*})
                                            (seq *script-bindings-unresolvable*)
                                            (conj {:script-bindings-unresolvable
                                                   (vec (sort *script-bindings-unresolvable*))}))]
                        (when (seq with-bindings) with-bindings))
             :triggers (when triggers-call [{:raw "<triggers block>"}])
             :libraries (when (seq libs) libs)})))))
     (catch Exception e
       ;; Total parse failure — usually means the file is fully scripted
       ;; (no `pipeline {}` at all) or has invalid Groovy syntax we can't
       ;; recover from. Return an empty-but-tagged IR.
       (ir/pipeline
        {:source-path source-path
         :stages []
         :options [{:parse-error (.getMessage e) :exception-class (.getName (class e))}]})))))
