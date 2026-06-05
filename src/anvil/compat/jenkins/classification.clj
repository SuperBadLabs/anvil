(ns anvil.compat.jenkins.classification
  "Honest build-result classification for anvil — wire-up of
   `chengis.engine.result` (CC2-EX2) under anvil's Jenkinsfile runner.

   Why this exists
   ===============
   anvil v0.3 ended every build with:

       (case (:status run-pipeline-result)
         :ok :success
         :failed :failure
         :success)        ; ← anything else → :success

   That `:else :success` is the regression the wild-corpus matrix
   surfaced. A build IR that walks but executes nothing — because
   `agent { docker }` was silently skipped, every `sh` step was a
   no-op in `:execute? false` mode, every `tool()` returned \"\" —
   classified as :success simply because no step threw.

   This namespace replaces the case-fallback with the EX2 classifier.
   It does the opposite of guessing: it inspects what was actually
   recorded during the build (effects, exits, agent-degradations,
   unknown steps) and asks the classifier what that adds up to.

   The receipt: anvil's wild-corpus matrix reclassifies its 7 false
   SUCCESSes as :neutral (empty walks) or :unsupported (silently
   skipped docker/k8s agents)."
  (:require [chengis.engine.result :as result]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Effect → observation rules
;; ---------------------------------------------------------------------------

(def ^:private artifact-effects
  "Effect tags that count as a real recorded effect for classification —
   their presence prevents a vacuous :neutral classification."
  #{:archive
    :junit
    :stash
    :unstash
    :write-file
    :read-file
    :mail
    :emailext
    :build       ; cross-job downstream trigger
    :checkout})  ; SCM checkout side-effect

(def ^:private productive-effect-tags
  "Effect tags that indicate the runner actually DID something — even if
   what it did was 'fail' or 'hit an unsupported construct'. The
   AN5-1 walk-shape synthesizer (below) treats a build whose effects
   vector contains none of these as a silent-failure candidate and
   emits a diagnostic `[:unknown ...]` effect explaining why.

   Includes :sh / :bat (real shell), :unknown / :tool-unresolved /
   :credential-unresolved (recorded gaps), and the artifact-effects set
   (real side effects). Excludes :stdout / :stderr / :echo (informational
   noise) and :stage/enter / :stage/leave / :agent/* (structural markers
   that fire even on empty walks)."
  (into #{:sh :bat :unknown :tool-unresolved :credential-unresolved
          :script-failed :scripted-eval-failed}
        artifact-effects))

(def ^:private unsupported-effects
  "Effect tags that mean the runner couldn't honor a construct. These
   classify the build as :unsupported, NOT silent success."
  #{:unknown})

(def ^:private agent-shapes-this-runner-cannot-honor
  "Agent shapes anvil v0.3 silently degrades. With chengis-core 0.2's
   Docker backend wired (Phase 2 follow-ups), :docker drops off this
   set; with K8s backend (Phase 3), :kubernetes drops off. Until then,
   record-unhonored-agent applies."
  #{:docker :dockerfile :kubernetes})

(defn- record-sh-effect [obs [_tag {:keys [exit]}]]
  ;; Counts as a shell step. exit may be nil for canned/test-mode sh
  ;; entries; treat nil as 0 (the step "succeeded" in that the runner
  ;; didn't see a non-zero exit). Real production runs always set :exit.
  (result/record-shell-step obs {:exit-code (or exit 0)}))

(defn- record-agent-degraded-effect
  [obs [_tag {:keys [requested-agent active-agent]}]]
  (let [shape (or (:type requested-agent)
                  (when (map? requested-agent) (:type requested-agent))
                  active-agent)]
    (if (and shape (contains? agent-shapes-this-runner-cannot-honor
                              (keyword shape)))
      (result/record-unhonored-agent
       obs (str "agent." (name (keyword shape))))
      obs)))

(defn- record-unknown-effect [obs [_tag {:keys [name]}]]
  (result/record-unsupported-construct
   obs (str "step." (or (some-> name str) "unknown"))))

(defn- record-tool-unresolved-effect [obs [_tag {:keys [descriptor]}]]
  (result/record-unresolved-tool obs (or descriptor "unknown-tool")))

(defn- record-credential-unresolved-effect [obs [_tag {:keys [credential-id]}]]
  (result/record-unresolved-credential obs (or credential-id "unknown-credential")))

(defn- record-artifact-effect [obs [tag _]]
  (result/record-effect obs (keyword (str (name tag) "-recorded"))))

(defn effects->observation
  "Fold an effects vector (as captured by
   `anvil.compat.jenkins.dispatcher`'s :effects atom) into an
   `chengis.engine.result` observation map.

   The rules:
     [:sh {:exit N ...}]    → record-shell-step (records non-zero exits
                              into :nonzero-exits)
     [:agent/degraded ...]   → record-unhonored-agent  when the requested
                               shape is docker/dockerfile/kubernetes
     [:unknown {:name ...}]  → record-unsupported-construct
     [:archive ...] / [:junit ...] / [:stash ...] etc → record-effect

   Anything else (e.g. :stdout / :stderr lines, :echo) does not move
   the observation; those are informational."
  [effects]
  (reduce
   (fn [obs effect]
     (let [tag (first effect)]
       (cond
         (= :sh tag)              (record-sh-effect obs effect)
         (= :bat tag)             (record-sh-effect obs effect)
         (= :agent/degraded tag)  (record-agent-degraded-effect obs effect)
         (= :tool-unresolved tag) (record-tool-unresolved-effect obs effect)
         (= :credential-unresolved tag)
                                  (record-credential-unresolved-effect obs effect)
         (contains? unsupported-effects tag)
                                  (record-unknown-effect obs effect)
         (contains? artifact-effects tag)
                                  (record-artifact-effect obs effect)
         :else                    obs)))
   (result/default-observation)
   effects))

;; ---------------------------------------------------------------------------
;; AN5-1 — Silent-failure walk-shape synthesizer
;;
;; The wild-corpus matrix surfaced builds where anvil's runner returned
;; without throwing, the classifier honestly said `:neutral` ("no
;; effects recorded"), but the truth on the ground was a silent
;; failure inside the dispatcher: a scripted @Library that failed to
;; load, a declarative stage whose body was dropped by the translator,
;; a matrix block whose expansion produced zero stages. The classifier
;; can't see those — it only sees the effects vector.
;;
;; This function inspects the *pipeline IR* (what the runner SHOULD
;; have walked) against the effects vector (what it actually did) and
;; emits diagnostic `[:unknown {:name X}]` effects when there's a
;; mismatch. The classifier's existing `:unsupported-construct` rule
;; picks them up, promoting `:neutral` → `:unsupported` with a specific
;; explain string the operator can act on.
;;
;; This is purely diagnostic — it never *causes* a failure that wasn't
;; already silently happening. It only makes silent failures audible.
;; ---------------------------------------------------------------------------

(defn- productive-effect?
  "True if effect's tag is in productive-effect-tags."
  [effect]
  (and (vector? effect)
       (contains? productive-effect-tags (first effect))))

(defn- unresolved-library?
  "Best-effort check: would anvil's shared-libs registry recognize this
   library name? We use anvil.compat.jenkins.shared-libs/handler-for on
   the library's basename as a probe. Real registration happens per-step
   (one library exports many `vars/*.groovy` step shims), so a 'no
   handler' answer is heuristic — but for the wild-corpus cases (e.g.
   `hibernate-jenkins-pipeline-helpers`) it's correct: NONE of the lib's
   intended step names are in our shim registry."
  [lib-name]
  (let [resolve (try (requiring-resolve
                      'anvil.compat.jenkins.shared-libs/handler-for)
                     (catch Throwable _ nil))]
    (cond
      (nil? resolve)            true  ;; can't probe → assume unresolved
      (str/blank? lib-name)     false
      :else (nil? (resolve lib-name)))))

(defn synthesize-shape-effects
  "Given pipeline-ir + observed effects, return diagnostic effects that
   explain a vacuous walk. Empty result vector when nothing to synthesize
   (i.e. the walk did real work).

   Order matters: more-specific causes first, so the classifier's
   :explain reflects the most actionable root cause.

     1. `:libraries [{:name X}, ...]` declared but X isn't in our
        shared-libs registry → one `[:unknown {:name \"library.X-unresolved\"}]`
        per unresolved entry. This is hibernate-orm / hibernate-search's
        shape.

     2. `:stages [...]` non-empty BUT no productive effect recorded →
        one `[:unknown {:name \"translator.body-skipped\"}]` describing
        the gap (stage count, scripted? flag, agent-degraded count).
        This is apache-camel / apache-zookeeper / apache-cxf's shape.

   Returns a vector; empty vector means 'no diagnostic effects to add'."
  [pipeline-ir effects]
  (let [effects (or effects [])
        any-productive? (boolean (some productive-effect? effects))
        stages (vec (:stages pipeline-ir))
        libs (vec (:libraries pipeline-ir))
        scripted? (boolean (some :scripted-pipeline? (:options pipeline-ir)))
        agent-degraded-count (count (filter #(and (vector? %)
                                                  (= :agent/degraded (first %)))
                                            effects))
        lib-effects (when-not any-productive?
                      (for [{:keys [name]} libs
                            :when (unresolved-library? name)]
                        [:unknown {:name (str "library." name "-unresolved")
                                   :anvil/synthetic? true
                                   :anvil/source :an5-1
                                   :anvil/cause :shared-lib}]))
        walk-effect (when (and (not any-productive?)
                               (seq stages)
                               (empty? lib-effects))
                      [[:unknown {:name "translator.body-skipped"
                                  :anvil/synthetic? true
                                  :anvil/source :an5-1
                                  :anvil/cause :translator
                                  :anvil/stage-count (count stages)
                                  :anvil/scripted? scripted?
                                  :anvil/agent-degraded-count
                                  agent-degraded-count}]])]
    (vec (concat lib-effects walk-effect))))

;; ---------------------------------------------------------------------------
;; Runner integration
;; ---------------------------------------------------------------------------

(defn classify-build
  "Top-level entry point for anvil's runner. Inputs:

     run-pipeline-result  — the {:status :ok | :failed :error … :message …}
                              map that `dispatcher/run-pipeline` returned
     effects              — the deref'd value of the dispatcher's :effects atom
     opts                 — optional:
                            {:cancelled? bool — true when the build was
                                                interrupted by operator
                             :pipeline-ir MAP — AN5-1: the parsed Jenkinsfile
                                                IR. When passed, enables the
                                                walk-shape synthesizer that
                                                surfaces silent failures
                                                (empty walks, unresolved
                                                @Library) as honest
                                                :unsupported classifications.}

   Returns
     {:result KW          — one of :success :failure :unstable :aborted
                                  :neutral :unsupported
      :rule KW            — the classifier rule that fired
      :explain STRING     — readable diagnostic for logs + UI
      :observation MAP    — the underlying observation (for tests + debugging)
      :synthetic-effects VEC — AN5-1 diagnostic effects added by the
                                walk-shape synthesizer (empty when none)}

   This is a pure function. The caller persists `:result` and is free
   to render `:rule`/`:explain` in the console / build page."
  [run-pipeline-result effects {:keys [cancelled? pipeline-ir] :as _opts}]
  (let [raw-effects (or effects [])
        ;; AN5-1: synthesize diagnostic effects from the pipeline IR
        ;; shape vs the observed effects vector. Pure no-op when
        ;; pipeline-ir is nil (older callers) or when the walk produced
        ;; real work (the common case).
        synth (synthesize-shape-effects pipeline-ir raw-effects)
        enriched-effects (into raw-effects synth)
        base (effects->observation enriched-effects)
        ;; If run-pipeline returned :failed but no :sh effect captured a
        ;; non-zero exit (e.g. :script-block-failed / :jenkins-error /
        ;; :stash-missing), record a synthetic non-zero so the classifier
        ;; sees the failure rather than ignoring an "unsourced" failure.
        with-pipeline-failure
        (if (and (= :failed (:status run-pipeline-result))
                 (empty? (:nonzero-exits base)))
          (result/record-shell-step
           base {:exit-code (or (:exit run-pipeline-result) 1)})
          base)
        with-cancel (if cancelled?
                      (result/mark-cancelled with-pipeline-failure)
                      with-pipeline-failure)
        c (result/classify with-cancel)]
    (assoc c
           :observation with-cancel
           :synthetic-effects synth)))

;; ---------------------------------------------------------------------------
;; Convenience: just the keyword (for one-line callers)
;; ---------------------------------------------------------------------------

(defn build-result-keyword
  "Convenience: return only the terminal-result keyword. Equivalent to
   (:result (classify-build run-pipeline-result effects opts))."
  ([run-pipeline-result effects]
   (build-result-keyword run-pipeline-result effects {}))
  ([run-pipeline-result effects opts]
   (:result (classify-build run-pipeline-result effects opts))))
