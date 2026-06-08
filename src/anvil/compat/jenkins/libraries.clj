(ns anvil.compat.jenkins.libraries
  "Shared library loader — implements `@Library('name@ref') _` resolution.

   Each library lives on the local filesystem at
   <BASE>/<name>/<ref>/vars/*.groovy (and someday src/org/.../*.groovy).
   For every `vars/<step>.groovy` file we register a plugin adapter
   (TX5 phase 1 SDK) whose handler evaluates the library source and
   invokes its `call(...)` function via the anvil runtime.

   v1 limitations (closed in TX5 phase 3 / TX9):
     - vars/*.groovy only — no src/org/foo/Bar.groovy class loading
     - Filesystem-based source resolution — no Git fetching
     - Single-arg or no-arg `call(...)` — `def call(Map params)` and
       `def call(String name)` shapes both work; multi-positional-arg
       libraries (`def call(a, b, c)`) require the runtime to spread,
       which TX5 phase 3 will add.

   Configuration:
     - The base library directory is supplied per-call to load-library!.
       The import CLI defaults to ~/.anvil/libraries; tests override.
     - The `chengis-core` `chengis.dsl.chengisfile` :import resolver
       (CHG-FEAT-005) is the longer-term home for cross-product library
       resolution — when anvil ships, we delegate Git fetching there."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.compat.jenkins.plugins :as plugins]
            [anvil.compat.jenkins.runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; Coordinate parsing
;; ---------------------------------------------------------------------------

(defn parse-coord
  "Parse a library coordinate string like 'foo@main' or 'foo' into
   {:name STRING :ref STRING}. Defaults ref to 'main' when omitted."
  [coord]
  (when (string? coord)
    (let [[name ref] (str/split coord #"@" 2)]
      {:name name :ref (or ref "main")})))

(defn resolve-source-dir
  "Where on disk should a library at (base, name, ref) live?
   Returns a java.io.File regardless of whether it exists yet."
  [base-dir name ref]
  (io/file base-dir name ref))

;; ---------------------------------------------------------------------------
;; vars/*.groovy discovery
;; ---------------------------------------------------------------------------

(defn list-vars
  "List the `vars/*.groovy` files in a resolved library directory.
   Returns a vector of {:step-name STRING :file File :source STRING}.
   Empty if vars/ doesn't exist (the library's contributions are
   class-style and not in scope for v1)."
  [^java.io.File resolved-dir]
  (let [vars-dir (io/file resolved-dir "vars")]
    (if-not (.exists vars-dir)
      []
      (->> (.listFiles vars-dir)
           (filter #(.isFile ^java.io.File %))
           (filter #(str/ends-with? (.getName ^java.io.File %) ".groovy"))
           (mapv (fn [^java.io.File f]
                   (let [filename (.getName f)
                         step-name (subs filename 0 (- (count filename) 7))]  ; strip .groovy
                     {:step-name step-name
                      :file f
                      :source (slurp f)})))))))

;; ---------------------------------------------------------------------------
;; Arg conversion: Clojure → Groovy-compatible
;; ---------------------------------------------------------------------------

(defn ->groovy-value
  "Convert a translator-emitted args->plain value (from a :jenkins/unknown
   step's :args) into something the library's Groovy `call(...)` can
   consume cleanly. Strings stay strings; Clojure maps become
   LinkedHashMap (Groovy iterates named-arg maps that way); vectors
   become ArrayList."
  [v]
  (cond
    (nil? v)     nil
    (string? v)  v
    (number? v)  v
    (boolean? v) v
    (map? v)     (let [m (java.util.LinkedHashMap.)]
                   (doseq [[k val] v] (.put m (name k) (->groovy-value val)))
                   m)
    (sequential? v) (let [a (java.util.ArrayList.)]
                      (doseq [x v] (.add a (->groovy-value x)))
                      a)
    (keyword? v) (name v)
    (symbol? v)  (str v)
    :else        (str v)))

;; ---------------------------------------------------------------------------
;; Library step → plugin adapter
;; ---------------------------------------------------------------------------

(defn- wrap-library-source
  "Wrap the library .groovy source so the call() function gets invoked
   with __libArgs bound. Returns the source text to evaluate.

   If the user's library doesn't define call(), we surface that as a
   Groovy compile error at adapter-run time rather than at registration."
  [library-source]
  (str library-source "\n\n"
       "// — appended by anvil library loader —\n"
       "if (binding.hasVariable('__libArgs') && binding.getVariable('__libArgs') != null) {\n"
       "  call(__libArgs)\n"
       "} else {\n"
       "  call()\n"
       "}\n"))

(defn make-library-adapter
  "Build a StepAdapter that handles ONE library step. When invoked, the
   adapter wraps the .groovy source so `call(__libArgs)` is invoked at
   the bottom, sets up the same DSL bindings the runtime uses for
   `script {}` blocks (so library code can call sh/echo/dir/…), and
   evaluates."
  [{:keys [library-name step-name source]}]
  (plugins/fn-adapter
   (str "library:" library-name)
   #{step-name}
   (fn [step ctx]
     (let [raw-args (:args step)
           single-arg (cond
                        (empty? raw-args)        nil
                        (= 1 (count raw-args))   (->groovy-value (first raw-args))
                        :else                    (->groovy-value (vec raw-args)))
           ctx-atom (atom (cond-> ctx
                            (nil? (:env ctx)) (assoc :env {})
                            (nil? (:cwd ctx)) (assoc :cwd "/workspace")))]
       (try
         (let [bindings (runtime/make-dsl-bindings
                         (:dispatcher ctx) ctx-atom (:env @ctx-atom))
               full-bindings (assoc bindings "__libArgs" single-arg)
               wrapped (wrap-library-source source)]
           (runtime/eval-with-dsl-base wrapped full-bindings)
           {:status :ok
            :output (str "[library " library-name "/" step-name "]")
            :ctx @ctx-atom})
         (catch Exception e
           {:status :failed
            :error :library-step-failed
            :message (str library-name "/" step-name ": " (.getMessage e))
            :ctx @ctx-atom}))))))

;; ---------------------------------------------------------------------------
;; Top-level: load a library and register all its vars
;; ---------------------------------------------------------------------------

(defn load-library!
  "Load a shared library at (base-dir, name, ref) and register each of
   its `vars/*.groovy` files as a plugin adapter.

   Returns {:status :ok    :registered [step-name ...]} on success.
   Returns {:status :error :reason KEYWORD :detail STRING}        on resolution failure.

   Idempotent in the sense that re-loading replaces any previously
   registered step names from the same library (last-wins per the
   plugin registry's semantics)."
  [base-dir name ref]
  (let [resolved (resolve-source-dir base-dir name ref)]
    (cond
      (not (.exists ^java.io.File resolved))
      {:status :error :reason :library-not-found
       :detail (str "No directory at " (.getAbsolutePath resolved))}

      (not (.isDirectory ^java.io.File resolved))
      {:status :error :reason :library-not-a-directory
       :detail (str (.getAbsolutePath resolved) " is not a directory")}

      :else
      (let [vars (list-vars resolved)]
        (if (empty? vars)
          {:status :ok :registered []
           :warning (str "Library " name "@" ref " has no vars/*.groovy files")}
          (do
            (doseq [v vars]
              (plugins/register!
               (make-library-adapter
                {:library-name name
                 :step-name    (:step-name v)
                 :source       (:source v)})))
            {:status :ok
             :registered (mapv :step-name vars)}))))))

;; ---------------------------------------------------------------------------
;; Integration: from pipeline-IR :libraries → load-all!
;; ---------------------------------------------------------------------------

(defn load-libraries-from-ir!
  "Walk a pipeline IR's :libraries vector and call load-library! for each
   entry. Returns a per-library result vector."
  ([pipeline-ir]
   (load-libraries-from-ir!
    pipeline-ir
    (or (System/getenv "ANVIL_LIBRARIES_DIR")
        (str (System/getProperty "user.home") "/.anvil/libraries"))))
  ([pipeline-ir base-dir]
   (mapv (fn [{:keys [name version]}]
           (assoc (load-library! base-dir name (or version "main"))
                  :name name :ref (or version "main")))
         (:libraries pipeline-ir []))))

;; ---------------------------------------------------------------------------
;; AN5-2: surface library load outcomes into the dispatcher's effects atom
;;
;; Before AN5-2 the runner never called load-libraries-from-ir!. The honest
;; classifier (AN5-1, classification.clj) treated every `@Library('X')`
;; coordinate as "unresolved by construction" — correctly conservative
;; for a v0.3 that had no loader wiring. AN5-2 closes that gap: the
;; runner DOES probe each coordinate against ANVIL_LIBRARIES_DIR and
;; pushes a real effect per library, so the classifier reads recorded
;; evidence instead of a synthesized guess.
;;
;; Two effect shapes:
;;   [:library-loaded     {:name X :ref Y :registered [step-names...]}]
;;   [:library-unresolved {:name X :ref Y :reason KW :detail STR}]
;;
;; Both are productive effect tags — their presence proves the runner
;; actually probed the coordinate. `:library-unresolved` additionally
;; classifies the build as :unsupported with rule `library.X-unresolved`
;; through effects->observation in classification.clj.
;; ---------------------------------------------------------------------------

(defn- result->effect
  "Translate a load-library! result map into an effect vector for the
   dispatcher's effects atom. Bridges the libraries.clj return shape
   (`{:status :ok :registered [...]}` or
    `{:status :error :reason KW :detail STR}`) onto the classifier's
   effect-tag vocabulary."
  [{:keys [status reason detail registered name ref]}]
  (let [base {:name name :ref ref}]
    (case status
      :ok    [:library-loaded     (assoc base :registered (or registered []))]
      :error [:library-unresolved (assoc base
                                         :reason (or reason :library-not-found)
                                         :detail (or detail ""))]
      ;; Defensive: a future return shape we don't recognize is itself a
      ;; surface event the classifier should see.
      [:library-unresolved (assoc base
                                  :reason :library-loader-unknown-status
                                  :detail (str "unknown :status " (pr-str status)))])))

(defn load-into-effects!
  "AN5-2: probe every `@Library` coordinate in `pipeline-ir` against
   the configured ANVIL_LIBRARIES_DIR (or the supplied `base-dir`) and
   push one effect per coordinate into `effects-atom`. Returns the
   vector of (effect-shaped) results so the caller can inspect them
   too — but the side-effect on the atom is the load-bearing thing.

   No-op when `:libraries` is empty or missing — never disturbs the
   effects atom unnecessarily.

   The runner calls this immediately AFTER dispatcher construction and
   BEFORE `run-pipeline`, so the classifier reads the recorded results
   alongside whatever the walk itself emits. The classifier already
   recognizes :library-loaded as productive and :library-unresolved as
   an unsupported-construct rule; AN5-1's walk-shape synthesizer skips
   its `library.X-unresolved` synthetic fallback for any X that already
   has a real effect, preventing double-reporting."
  ([pipeline-ir effects-atom]
   (load-into-effects!
    pipeline-ir effects-atom
    (or (System/getenv "ANVIL_LIBRARIES_DIR")
        (str (System/getProperty "user.home") "/.anvil/libraries"))))
  ([pipeline-ir effects-atom base-dir]
   (let [libs (:libraries pipeline-ir)]
     (when (seq libs)
       (let [results (load-libraries-from-ir! pipeline-ir base-dir)
             effects (mapv result->effect results)]
         (swap! effects-atom into effects)
         effects)))))

;; ---------------------------------------------------------------------------
;; AN7-4: remote-aware library loading
;;
;; load-into-effects! (AN5-2) assumes the library is already on disk at
;; ANVIL_LIBRARIES_DIR/<name>/<ref>/. When that directory doesn't exist,
;; it records :library-not-found. AN7-4 extends the path:
;;
;;   1. Ask `anvil.lib.resolver/resolve!` whether a remote URL is
;;      configured for the library name.
;;   2. If yes, clone (or use cache) so the directory exists, then
;;      fall through to the existing load-library! registration.
;;   3. If no remote is configured, fall back to the existing base-dir
;;      lookup — operators who pre-seed libraries manually still work.
;;
;; The resolver is loaded lazily via `requiring-resolve` so that the
;; libraries namespace doesn't hard-depend on git being available at
;; class-load time (test suites that don't exercise remote resolution
;; are unaffected).
;; ---------------------------------------------------------------------------

(defn- load-one-with-remote!
  "AN7-4c: resolve (clone if needed) one library, then register its vars.
   `base-dir` is the ANVIL_LIBRARIES_DIR fallback for pre-seeded libraries.
   Returns a result map compatible with `result->effect`."
  [base-dir name ref]
  ;; 1. Ask the resolver for the local path, cloning if required.
  (let [resolve! (requiring-resolve 'anvil.lib.resolver/resolve!)
        res (resolve! name ref)]
    (cond
      ;; Resolver returned :ok — use the cloned/cached path.
      (= :ok (:status res))
      (assoc (load-library! (.getAbsolutePath (:path res)) name ref)
             :name name :ref ref)

      ;; Resolver returned :remote-not-configured — fall back to the
      ;; local base-dir (operator pre-seeded the library manually).
      (= :remote-not-configured (:reason res))
      (assoc (load-library! base-dir name ref)
             :name name :ref ref)

      ;; Resolver returned a different error (git unavailable, clone failed).
      ;; Surface it as :library-unresolved so the classifier sees it.
      :else
      {:status :error
       :name   name
       :ref    ref
       :reason (:reason res)
       :detail (:detail res)})))

(defn load-with-remote-into-effects!
  "AN7-4: Like `load-into-effects!` but tries remote Git resolution
   (via `anvil.lib.resolver/resolve!`) for each library BEFORE falling
   back to the local base-dir lookup.

   Resolution priority:
     1. `:anvil.libs/remotes` entry in anvil.edn → git clone to
        `~/.anvil/libs/<name>/<ref>/` (cached per R7).
     2. Pre-seeded local directory at `ANVIL_LIBRARIES_DIR/<name>/<ref>/`.
     3. Neither → `:library-unresolved` effect.

   Effects pushed are identical to `load-into-effects!` — the classifier
   and reporter code doesn't need to change.

   Called by the runner instead of `load-into-effects!` when the
   :cost-reporting feature flag is on and the Jenkinsfile has
   `:libraries`. Operators who never configure remote libraries see
   no behavioral change."
  ([pipeline-ir effects-atom]
   (load-with-remote-into-effects!
    pipeline-ir effects-atom
    (or (System/getenv "ANVIL_LIBRARIES_DIR")
        (str (System/getProperty "user.home") "/.anvil/libraries"))))
  ([pipeline-ir effects-atom base-dir]
   (let [libs (:libraries pipeline-ir)]
     (when (seq libs)
       (let [results (mapv (fn [{:keys [name version]}]
                             (load-one-with-remote! base-dir name (or version "main")))
                           libs)
             effects (mapv result->effect results)]
         (swap! effects-atom into effects)
         effects)))))
