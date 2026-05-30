(ns anvil.compat.jenkins.ir
  "Jenkins IR shape — predicates, constructors, and a brief schema.

   The IR is plain Clojure data. The translator (this directory) produces
   it; the dispatcher (TX4) consumes it. Both products and tooling should
   treat the keys here as the public surface.

   ## Pipeline

     {:source     :jenkins
      :source-path STRING?              ; relative path of the Jenkinsfile
      :agent      AGENT?
      :tools      [TOOL-CALL ...]?
      :options    [OPTION-CALL ...]?
      :triggers   [TRIGGER-CALL ...]?
      :parameters [PARAMETER-CALL ...]?
      :environment {STRING STRING ...}?
      :libraries  [LIBRARY-IMPORT ...]?  ; @Library(...) annotations, rewritten
      :stages     [STAGE ...]
      :post       POST-ACTIONS?}

   ## Agent

     {:type :any}                                 ; agent any
     {:type :none}                                ; agent none
     {:label STRING}                              ; agent { label '…' }
     {:label STRING :type :node-label}            ; agent { node { label '…' } }
     {:docker {:image STRING :args STRING?}}      ; agent { docker { … } }
     {:dockerfile {:filename STRING :dir STRING?}} ; agent { dockerfile { … } }
     {:type :kubernetes :raw ...}                 ; (deferred to a later wave)
     {:type :unknown :raw ...}                    ; fallback

   ## Stage

     {:name STRING
      :agent AGENT?                                ; stage-level override
      :when WHEN-CONDITION?                        ; (free-form for now)
      :environment {STRING STRING}?
      :tools [...]?
      :options [...]?
      :steps [STEP ...]
      :parallel-branches {STRING [STEP ...] ...}?  ; for `parallel(map)` shape
      :post POST-ACTIONS?}

   ## Post actions

     {:always [STEP ...]?
      :on-success [STEP ...]?      ; declarative key is `success` in Jenkins
      :on-failure [STEP ...]?      ; `failure`
      :on-changed [STEP ...]?      ; `changed`
      :on-unstable [STEP ...]?     ; `unstable`
      :on-aborted [STEP ...]?      ; `aborted`
      :cleanup [STEP ...]?}        ; `cleanup`

   ## Step

   Each step is a map with a `:type` of the form `:jenkins/NAME`, plus
   the step-specific payload. Examples:

     {:type :jenkins/sh        :script STRING :return-stdout? BOOL? :return-status? BOOL?}
     {:type :jenkins/bat       :script STRING}
     {:type :jenkins/echo      :message STRING}
     {:type :jenkins/dir       :path STRING :body [STEP ...]}
     {:type :jenkins/with-env  :env [STRING ...] :body [STEP ...]}
     {:type :jenkins/with-credentials :credentials [...] :body [STEP ...]}
     {:type :jenkins/parallel  :branches {STRING [STEP ...]} :fail-fast? BOOL?}
     {:type :jenkins/timeout   :time NUMBER :unit STRING :body [STEP ...]}
     {:type :jenkins/retry     :n NUMBER :body [STEP ...]}
     {:type :jenkins/junit     :results STRING :allow-empty? BOOL?}
     {:type :jenkins/archive-artifacts :artifacts STRING :excludes STRING?}
     {:type :jenkins/stash     :name STRING :includes STRING}
     {:type :jenkins/unstash   :name STRING}
     {:type :jenkins/delete-dir}
     {:type :jenkins/script    :body-source STRING}  ; Groovy source of script {}
     {:type :jenkins/unknown   :name STRING :args [VALUE ...]}  ; long-tail

   All steps may carry :jenkins-line NUMBER for diagnostics."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn pipeline
  "Construct a top-level pipeline IR with sensible defaults."
  [{:keys [agent stages post tools options triggers parameters
           environment libraries source-path]}]
  (cond-> {:source :jenkins
           :stages (vec (or stages []))}
    source-path  (assoc :source-path source-path)
    agent        (assoc :agent agent)
    tools        (assoc :tools tools)
    options      (assoc :options options)
    triggers     (assoc :triggers triggers)
    parameters   (assoc :parameters parameters)
    environment  (assoc :environment environment)
    libraries    (assoc :libraries libraries)
    post         (assoc :post post)))

(defn step-sh
  "Construct a :jenkins/sh step IR node."
  ([script] (step-sh script {}))
  ([script {:keys [return-stdout? return-status? line]}]
   (cond-> {:type :jenkins/sh :script script}
     return-stdout? (assoc :return-stdout? true)
     return-status? (assoc :return-status? true)
     line           (assoc :jenkins-line line))))

(defn step-echo
  ([message]      {:type :jenkins/echo :message message})
  ([message line] {:type :jenkins/echo :message message :jenkins-line line}))

(defn step-script
  ([body-source]      {:type :jenkins/script :body-source body-source})
  ([body-source line] {:type :jenkins/script :body-source body-source :jenkins-line line}))

(defn step-unknown
  "Catch-all for step names we don't have a translator for yet. The runtime
   may still execute them via the plugin SDK (TX5)."
  ([name args]      {:type :jenkins/unknown :name name :args args})
  ([name args line] {:type :jenkins/unknown :name name :args args :jenkins-line line}))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(defn pipeline?
  "True iff `x` is a valid pipeline IR (has :source :jenkins and :stages)."
  [x]
  (and (map? x)
       (= :jenkins (:source x))
       (vector? (:stages x))))

(defn step?
  "True iff `x` looks like a step IR node (has a :jenkins/ keyword type)."
  [x]
  (and (map? x)
       (keyword? (:type x))
       (= "jenkins" (namespace (:type x)))))

(defn stage?
  "True iff `x` looks like a stage IR node."
  [x]
  (and (map? x)
       (string? (:name x))
       (vector? (:steps x))))

(defn script-step?
  "True iff `x` is a :jenkins/script step."
  [x]
  (and (map? x) (= :jenkins/script (:type x))))

;; ---------------------------------------------------------------------------
;; Convenience walkers
;; ---------------------------------------------------------------------------

(defn all-steps
  "Return a lazy seq of every step IR node anywhere in the pipeline,
   including those inside :post and inside nested scope wrappers
   AND inside parallel-branch bodies.

   The original walker only recursed into :body, so steps under
   :branches (the shape `translate-parallel` produces:
   `{:branches {\"branchA\" [step …], \"branchB\" [step …]}}`) were
   ignored. ir/summarize and the import coverage report consequently
   silently inflated coverage when unsupported / script-heavy work
   lived inside a parallel block (Codex P2, PR #164)."
  [pipeline-ir]
  (letfn [(walk-steps [steps]
            (mapcat (fn [s]
                      (concat
                       [s]
                       (when-let [body (:body s)]
                         (walk-steps body))
                       ;; :branches is a map of branch-name → [step …]
                       (when-let [branches (:branches s)]
                         (mapcat walk-steps (vals branches)))))
                    steps))]
    (concat
     (mapcat (fn [stage]
               (concat (walk-steps (:steps stage []))
                       (mapcat walk-steps (vals (:post stage {})))))
             (:stages pipeline-ir))
     (mapcat walk-steps (vals (:post pipeline-ir {}))))))

(defn step-name-frequency
  "Map of step keyword → count across the entire pipeline IR. Useful for
   the coverage metric and corpus regression reporting."
  [pipeline-ir]
  (frequencies (map :type (all-steps pipeline-ir))))

(defn unknown-step-names
  "Set of `name` values from any :jenkins/unknown steps in the pipeline.
   These drive the 'we don't yet adapt this Jenkins step' migration UX."
  [pipeline-ir]
  (->> (all-steps pipeline-ir)
       (filter #(= :jenkins/unknown (:type %)))
       (map :name)
       (remove nil?)
       set))

(defn summarize
  "Compact summary suitable for tests / coverage reports."
  [pipeline-ir]
  (let [stages (:stages pipeline-ir)
        step-types (step-name-frequency pipeline-ir)
        unknowns (unknown-step-names pipeline-ir)
        total-steps (reduce + (vals step-types))
        known-steps (reduce + (vals (dissoc step-types :jenkins/unknown)))]
    {:stage-count (count stages)
     :stage-names (mapv :name stages)
     :total-steps total-steps
     :known-steps known-steps
     :unknown-steps (get step-types :jenkins/unknown 0)
     :script-blocks (get step-types :jenkins/script 0)
     :step-types step-types
     :unknown-names unknowns
     :has-post? (boolean (:post pipeline-ir))
     :coverage (if (zero? total-steps)
                 100.0
                 (* 100.0 (/ known-steps total-steps)))}))
