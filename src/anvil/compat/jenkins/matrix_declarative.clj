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
