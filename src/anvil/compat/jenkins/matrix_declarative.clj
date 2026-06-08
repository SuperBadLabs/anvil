(ns anvil.compat.jenkins.matrix-declarative
  "Declarative-matrix support (T4 of the v0.3 board).

   Distinct from `anvil.compat.jenkins.matrix-expander` (TX11B) which
   handles scripted-Pipeline Groovy `combinations()` calls — this
   module handles the *declarative* syntax:

     stage('Build & Test') {
       matrix {
         axes {
           axis { name 'JDK'; values 17, 21 }
           axis { name 'OS'; values 'linux', 'windows' }
         }
         excludes {
           exclude {
             axis { name 'OS'; values 'windows' }
             axis { name 'JDK'; values 17 }
           }
         }
         stages {
           stage('Build') { steps { sh 'mvn -B install' } }
         }
       }
     }

   Per AV3-5 of the v0.3 board, dynamic-matrix from scripted Pipeline
   is OUT OF SCOPE at v0.3.0 (the TX11B path handles that).

   ## IR contract

   The translator (in v0.3.1) emits this for a stage containing a
   matrix:

     {:type :jenkins/matrix
      :name <parent-stage-name>
      :axes [{:name 'JDK' :values ['17' '21']}
             {:name 'OS'  :values ['linux' 'windows']}]
      :excludes [{'JDK' '17' 'OS' 'windows'}]
      :stages [<inner stage IR> ...]}

   At trigger time, `expand-matrix` walks this and produces N child
   build specs — one per surviving axis combination — each with the
   axis values injected as env (`JDK=17`, `OS=linux`) so the inner
   stages just see them as variables.

   ## Anti-goals

   - Re-implementing what TX11B already does for scripted matrix.
   - Custom axis types (Jenkins has `dynamicAxis` etc. plug-ins);
     v0.3.0 ships explicit-value axes only.
   - Per-axis `when` filtering inside the matrix block; AV3-5 says
     declarative-only at v0.3.0, no Groovy `when {}` evaluation."
  (:require [clojure.string :as str]
            [anvil.config :as config]
            [taoensso.timbre :as log]))

(def default-max-cells
  "Per AV3-5/R4: matrix size cap before fail-fast. Configurable per
   anvil.edn :anvil.matrix/max-cells."
  100)

(defn max-cells []
  (or (:anvil.matrix/max-cells (config/load-edn "anvil" {}))
      default-max-cells))

;; ---------------------------------------------------------------------------
;; Cartesian product of axes (T4.2 a)
;; ---------------------------------------------------------------------------

(defn cartesian
  "Cartesian product of N collections. ([:a :b] [:x :y]) →
   ([:a :x] [:a :y] [:b :x] [:b :y])."
  [colls]
  (if (empty? colls)
    [[]]
    (for [v (first colls)
          rest (cartesian (rest colls))]
      (cons v rest))))

(defn- combo->env-map
  "Convert a [val val ...] tuple into a {name val ...} map, paired
   with the axes vector in order."
  [axes combo]
  (into {} (map (fn [{:keys [name]} v] [name v]) axes combo)))

;; ---------------------------------------------------------------------------
;; Exclude evaluation (T4.2 b)
;; ---------------------------------------------------------------------------

(defn- combo-excluded?
  "True iff `combo-map` matches any exclude clause. Each exclude is
   a map of {axis-name value}; a clause matches when EVERY axis it
   names equals the combo's value for that axis. Axes not named in
   an exclude clause act as 'wildcards'."
  [combo-map excludes]
  (some (fn [excl]
          (every? (fn [[axis-name val]]
                    (= val (get combo-map axis-name)))
                  excl))
        excludes))

;; ---------------------------------------------------------------------------
;; Public expander (T4.2)
;; ---------------------------------------------------------------------------

(defn expand-matrix
  "Take a matrix IR node and return a vector of child build specs.
   Each child carries the axis values + the matrix's body stages.
   `:env` is the convenient form for the dispatcher to inject (each
   axis becomes an env var named after the axis).

   Throws `ex-info` with `:type :matrix/cap-exceeded` when the
   un-excluded cross-product would exceed `(max-cells)`.

   Output shape:
     [{:axes {'JDK' '17' 'OS' 'linux'}
       :env  {'JDK' '17' 'OS' 'linux'}
       :tools [<matrix-level tool spec> ...]?
       :stages [<inner stages copied from :stages>]}
      ...]

   AN8-3: when the matrix IR carries `:tools` (a `tools { … }` block
   declared inside the matrix block, sibling to `axes`/`stages`), every
   cell inherits it. Per-axis `${VAR}` interpolation happens at
   dispatch time against the cell's `:axes` map — the translator only
   surfaces the template text; the dispatcher resolves it before the
   AN8-1 tool-mapping lookup."
  [{:keys [axes excludes stages name tools]}]
  (let [axis-values (mapv :values axes)
        total (reduce * 1 (map count axis-values))
        cap (max-cells)]
    (when (> total cap)
      (throw (ex-info (str "matrix axes cross-product (" total
                           " cells) exceeds cap (" cap "); "
                           "raise via :anvil.matrix/max-cells")
                      {:type :matrix/cap-exceeded
                       :total total
                       :cap cap
                       :parent-stage name})))
    (->> (cartesian axis-values)
         (map #(combo->env-map axes %))
         (remove #(combo-excluded? % excludes))
         (mapv (fn [combo-map]
                 (cond-> {:axes combo-map
                          :env (zipmap (keys combo-map)
                                       (map str (vals combo-map)))
                          :stages stages}
                   (seq tools) (assoc :tools tools)))))))

(defn cell-name
  "Render a child cell's display name, e.g. 'JDK=17, OS=linux'.
   Used by the grid view + storage."
  [axis-map]
  (->> axis-map
       (sort-by key)
       (map (fn [[k v]] (str k "=" v)))
       (str/join ", ")))

;; ---------------------------------------------------------------------------
;; AN8-3b — `${AXIS}` / `$AXIS` substitution in step bodies
;;
;; Real Jenkinsfiles (apache-camel + the wild corpus) reference matrix axis
;; variables deep inside step bodies — `echo "Build for ${PLATFORM}-${JDK_NAME}"`,
;; `sh "./mvnw $MAVEN_PARAMS ..."`, `script { if ("${PLATFORM}" == ...) }`.
;; The Groovy script evaluator that runs those bodies sees the axis names
;; as unbound variables → MissingPropertyException, build dies before
;; reaching mvn.
;;
;; This is the translator-time substitution layer of the fix: walk every
;; step body during matrix expansion and substitute axis refs at
;; translator time. This mirrors the substitution strategy PR #113
;; introduced for top-level `def NAME =` bindings, extended here to also
;; cover matrix axes — and applied recursively over scope-wrapper bodies
;; (timeout, dir, retry, with-env, script {}), parallel branches, and the
;; GString contexts each step's payload carries.
;;
;; Substitution is OPT-IN per step shape and never touches:
;;   - :raw-args (preserves unknown-step diagnostic surface)
;;   - bash refs to variables that don't name an axis (`$HOME`,
;;     `$BRANCH_NAME` when not axis-named) — left as-is so the runner's
;;     environment can resolve them
;;   - Single-quoted Groovy strings still flow through here when their
;;     literal text happens to contain an axis name, because the
;;     translator records :const arg values directly into the same
;;     :script / :message keys. In real Jenkinsfiles axis refs are
;;     always written in GString form (double-quoted, with $ refs); the
;;     literal-`$X`-inside-single-quotes false-positive surface is empty
;;     in the wild corpus.
;; ---------------------------------------------------------------------------

(defn- axis-pattern
  "Compile a regex matching `${NAME}` or `$NAME(?!\\w)` for a single axis."
  [^String axis-name]
  (java.util.regex.Pattern/compile
   (str "\\$\\{" (java.util.regex.Pattern/quote axis-name) "\\}"
        "|\\$" (java.util.regex.Pattern/quote axis-name) "(?!\\w)")))

(defn interpolate-axis-refs
  "Replace `${AXIS}` and `$AXIS` references in `s` from `axes`
   (a {name → value} map). Leaves unrecognized refs (`$HOME`, etc.) alone.

   Returns the substituted string, or `s` unchanged if there's nothing
   to substitute (empty axes, no `$` in s, blank input)."
  [s axes]
  (let [t (str s)]
    (if (or (str/blank? t) (empty? axes) (not (str/includes? t "$")))
      t
      (reduce-kv
       (fn [acc k v]
         (let [name-str (str k)
               pat (axis-pattern name-str)]
           (str/replace acc pat
                        (java.util.regex.Matcher/quoteReplacement (str v)))))
       t
       axes))))

(declare interpolate-step)

(defn- interpolate-steps [steps axes]
  (mapv #(interpolate-step % axes) steps))

(defn- interpolate-branches
  "Walk a `:branches {name [step …]}` map (parallel step shape)."
  [branches axes]
  (into {} (for [[k v] branches] [k (interpolate-steps v axes)])))

(defn interpolate-step
  "Substitute axis refs in a single step IR. Recurses through scope-wrapper
   bodies (`:body`) and parallel branches (`:branches`). Substitutes the
   string-valued payload keys most likely to carry GString axis refs:

     :script       — sh + bat command text
     :message      — echo text
     :body-source  — Groovy source of script {} blocks (apache-camel's
                     `script { if (\"${PLATFORM}\" == ...) }` shape)
     :artifacts    — archive-artifacts glob
     :results      — junit testResults glob
     :path         — dir path
     :label        — sh label

   Other keys pass through untouched. When `axes` is empty, returns the
   step unchanged with no allocation."
  [step axes]
  (if (or (empty? axes) (not (map? step)))
    step
    (let [sub #(interpolate-axis-refs % axes)
          step (cond-> step
                 (string? (:script step))      (update :script sub)
                 (string? (:message step))     (update :message sub)
                 (string? (:body-source step)) (update :body-source sub)
                 (string? (:artifacts step))   (update :artifacts sub)
                 (string? (:results step))     (update :results sub)
                 (string? (:path step))        (update :path sub)
                 (string? (:label step))       (update :label sub))]
      (cond-> step
        (vector? (:body step))     (update :body interpolate-steps axes)
        (map?    (:branches step)) (update :branches interpolate-branches axes)))))

(defn interpolate-stage-steps
  "Top-level helper for translator/expand-matrix-stage. Walk every step
   inside `stages` (a vector of {:name … :steps [...]} maps) and return
   a new stages vector with axis refs substituted. Pure; no I/O.

   When `axes` is empty, returns `stages` unchanged."
  [stages axes]
  (if (empty? axes)
    stages
    (mapv (fn [stage]
            (cond-> stage
              (vector? (:steps stage))
              (update :steps interpolate-steps axes)))
          stages)))

;; ---------------------------------------------------------------------------
;; Parsing (T4.1) — extract IR from translator cdata
;; ---------------------------------------------------------------------------

(defn- consts-of-call
  "Return the list of constant arg values from a cdata :call node.
   `axis { name 'JDK'; values 17, 21 }` is itself a call with a body
   that contains `name(...)` and `values(...)` calls."
  [call]
  (->> (:args call)
       (keep (fn [a]
               (cond
                 (and (map? a) (= :const (:type a))) (:value a)
                 ;; values 17, 21 may show up as separate args
                 :else nil)))
       vec))

(defn- closure-body-calls [call]
  (when-let [c (last (:args call))]
    (when (and (map? c) (= :closure (:type c)))
      (filter #(and (map? %) (= :call (:type %))) (:body c)))))

(defn- parse-axis-call
  "Parse `axis { name 'JDK'; values 17, 21 }` from a cdata :call."
  [axis-call]
  (let [inner (closure-body-calls axis-call)
        name-call (some #(when (= "name" (:name %)) %) inner)
        values-call (some #(when (= "values" (:name %)) %) inner)]
    (when (and name-call values-call)
      {:name (first (consts-of-call name-call))
       :values (mapv str (consts-of-call values-call))})))

(defn- parse-axes-block
  "Parse `axes { axis {...} axis {...} }` cdata :call into a vector."
  [axes-call]
  (->> (closure-body-calls axes-call)
       (filter #(= "axis" (:name %)))
       (keep parse-axis-call)
       vec))

(defn- parse-exclude-call
  "Each `exclude` block contains 1+ `axis` calls. Each axis names a
   value; the exclude matches a combo when EVERY axis matches."
  [exc-call]
  (->> (closure-body-calls exc-call)
       (filter #(= "axis" (:name %)))
       (keep parse-axis-call)
       ;; A single-value axis means \"this axis must equal this value
       ;; for the exclude to trigger\". Convert to {name val} map.
       (mapcat (fn [{:keys [name values]}]
                 (when (= 1 (count values))
                   [[name (first values)]])))
       (into {})))

(defn- parse-excludes-block
  [excludes-call]
  (->> (closure-body-calls excludes-call)
       (filter #(= "exclude" (:name %)))
       (mapv parse-exclude-call)))

(defn parse-matrix-call
  "Parse a `matrix { axes {} excludes? {} tools? {} stages {} }` cdata
   :call into IR. `parent-stage-name` is the surrounding stage's name.

   Returns a matrix IR map or nil if the structure isn't recognizable.

   AN8-3: a matrix block can declare its own `tools { … }` block sibling
   to `axes`/`stages` (zookeeper's real Jenkinsfile shape). The caller
   passes `tools-parser` so we don't introduce a circular dep on
   `translator/translate-tools`; `tools-parser` receives the raw
   `tools` :call cdata and returns the normalized vector of tool specs.
   When absent or nil, matrix-level tools aren't surfaced (back-compat
   with pre-AN8-3 callers)."
  ([matrix-call parent-stage-name inner-stage-parser]
   (parse-matrix-call matrix-call parent-stage-name inner-stage-parser nil))
  ([matrix-call parent-stage-name inner-stage-parser tools-parser]
   (let [inner (closure-body-calls matrix-call)
         axes-call (some #(when (= "axes" (:name %)) %) inner)
         excludes-call (some #(when (= "excludes" (:name %)) %) inner)
         stages-call (some #(when (= "stages" (:name %)) %) inner)
         tools-call (some #(when (= "tools" (:name %)) %) inner)
         axes (when axes-call (parse-axes-block axes-call))
         excludes (when excludes-call (parse-excludes-block excludes-call))
         ;; Inner stages parsed by the caller's stage-translator (we
         ;; can't reach back into translator.clj from here without a
         ;; circular dep; the caller passes its translator fn).
         stages (when (and stages-call inner-stage-parser)
                  (inner-stage-parser stages-call))
         tools (when (and tools-call tools-parser)
                 (tools-parser tools-call))]
     (when (seq axes)
       (cond-> {:type :jenkins/matrix
                :name parent-stage-name
                :axes axes
                :excludes (or excludes [])
                :stages (vec stages)}
         (seq tools) (assoc :tools tools))))))

(defn matrix-child-call?
  "Predicate: is this stage-body :call a `matrix { }` block?"
  [c]
  (and (map? c) (= :call (:type c)) (= "matrix" (:name c))))
