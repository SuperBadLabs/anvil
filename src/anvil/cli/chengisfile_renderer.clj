(ns anvil.cli.chengisfile-renderer
  "Jenkins IR → Chengisfile EDN renderer.

   The output is a Chengisfile-shaped data structure that anvil's
   native-mode parser (chengis.dsl.chengisfile, now in chengis-core)
   will consume directly. Where a Jenkins step has no Chengis-native
   equivalent, we emit a tagged step plus a `:FIXME` annotation so a
   reader can locate the manual-review points.

   The renderer is pure: pipeline IR in, EDN data + report metadata out."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Step-by-step translation: :jenkins/<name> → Chengis-native shape.
;; Unknown / partially-supported types get a :FIXME tag that the report
;; surfaces.
;; ---------------------------------------------------------------------------

(declare render-steps)

(defn- attach-line
  "Carry the original Jenkinsfile line number through to the rendered step
   so the report can map it back."
  [m line]
  (cond-> m line (assoc :jenkins-line line)))

(defmulti render-step :type)

(defmethod render-step :jenkins/sh [s]
  (attach-line
   (cond-> {:run (or (:script s) "")}
     (:label s)         (assoc :name (:label s))
     (:return-stdout?  s) (assoc :capture-stdout? true)
     (:return-status? s) (assoc :capture-status? true))
   (:jenkins-line s)))

(defmethod render-step :jenkins/bat [s]
  (attach-line {:run (or (:script s) "") :shell :bat} (:jenkins-line s)))

(defmethod render-step :jenkins/echo [s]
  ;; Chengisfile doesn't have a native echo; use sh-equivalent for fidelity.
  (attach-line {:run (str "echo " (pr-str (or (:message s) "")))}
               (:jenkins-line s)))

(defmethod render-step :jenkins/junit [s]
  (attach-line
   (cond-> {:type :publish-junit :results (or (:results s) "")}
     (:allow-empty? s) (assoc :allow-empty? true))
   (:jenkins-line s)))

(defmethod render-step :jenkins/archive-artifacts [s]
  (attach-line
   (cond-> {:type :archive-artifacts :artifacts (or (:artifacts s) "")}
     (:excludes s)     (assoc :excludes (:excludes s))
     (:fingerprint? s) (assoc :fingerprint? true))
   (:jenkins-line s)))

(defmethod render-step :jenkins/stash [s]
  (attach-line
   (cond-> {:type :stash}
     (:name s)     (assoc :name (:name s))
     (:includes s) (assoc :includes (:includes s))
     (:excludes s) (assoc :excludes (:excludes s)))
   (:jenkins-line s)))

(defmethod render-step :jenkins/unstash [s]
  (attach-line {:type :unstash :name (or (:name s) "")} (:jenkins-line s)))

(defmethod render-step :jenkins/delete-dir [s]
  (attach-line {:type :delete-dir} (:jenkins-line s)))

(defmethod render-step :jenkins/checkout [s]
  (attach-line
   (cond-> {:type :checkout}
     (:spec s)      (assoc :spec (:spec s))
     (:ref s)       (assoc :ref (:ref s))
     (:raw-args s)  (assoc :raw-args (:raw-args s)))
   (:jenkins-line s)))

(defmethod render-step :jenkins/mail [s]
  (attach-line {:type :notify-email :raw-args (:raw-args s)} (:jenkins-line s)))

(defmethod render-step :jenkins/emailext [s]
  (attach-line {:type :notify-email :provider :emailext :raw-args (:raw-args s)}
               (:jenkins-line s)))

(defmethod render-step :jenkins/write-file [s]
  (attach-line {:type :write-file :raw-args (:raw-args s)} (:jenkins-line s)))

(defmethod render-step :jenkins/read-file [s]
  (attach-line {:type :read-file :raw-args (:raw-args s) :capture-stdout? true}
               (:jenkins-line s)))

(defmethod render-step :jenkins/build [s]
  (attach-line {:type :trigger-pipeline :raw-args (:raw-args s)} (:jenkins-line s)))

(defmethod render-step :jenkins/error [s]
  (attach-line {:type :fail :message (or (:message s) "")} (:jenkins-line s)))

(defmethod render-step :jenkins/sleep [s]
  (attach-line {:type :sleep :raw-args (:raw-args s)} (:jenkins-line s)))

;; --- scope wrappers ---------------------------------------------------------

(defmethod render-step :jenkins/dir [s]
  (attach-line
   {:type :dir
    :path (or (:path s) "")
    :body (render-steps (:body s []))}
   (:jenkins-line s)))

(defmethod render-step :jenkins/with-env [s]
  (attach-line
   {:type :with-env
    :env-strings (:env-strings s [])
    :body (render-steps (:body s []))}
   (:jenkins-line s)))

(defmethod render-step :jenkins/with-credentials [s]
  (attach-line
   {:type :with-credentials
    :credentials (:credentials s [])
    :body (render-steps (:body s []))
    :FIXME "Credential bindings need to be re-pointed at the anvil/Chengis credential store. Verify each credential id maps."}
   (:jenkins-line s)))

(defmethod render-step :jenkins/timeout [s]
  (attach-line
   (cond-> {:type :timeout :body (render-steps (:body s []))}
     (:time s) (assoc :time (:time s))
     (:unit s) (assoc :unit (:unit s)))
   (:jenkins-line s)))

(defmethod render-step :jenkins/retry [s]
  (attach-line
   (cond-> {:type :retry :body (render-steps (:body s []))}
     (:count s) (assoc :count (:count s)))
   (:jenkins-line s)))

(defmethod render-step :jenkins/parallel [s]
  (attach-line
   {:type :parallel
    :branches (into {} (for [[name steps] (:branches s {})]
                         [name (render-steps steps)]))}
   (:jenkins-line s)))

;; --- escape hatches ---------------------------------------------------------

(defmethod render-step :jenkins/script [s]
  (attach-line
   {:type :jenkins-script-block
    :body-source (:body-source s "")
    :FIXME "Scripted Pipeline `script {}` block. Best path forward:
              1. If it's <20 lines, hand-translate to native steps.
              2. If complex, keep as :jenkins-script-block — the runtime
                 will evaluate it. (Performance + isolation are slightly
                 lower for scripted vs. declarative.)"}
   (:jenkins-line s)))

(defmethod render-step :jenkins/unknown [s]
  (attach-line
   {:type :jenkins-unsupported
    :name (:name s "<unknown>")
    :raw-args (:args s [])
    :FIXME (str "Step `" (:name s) "` is not yet adapted. "
                "Most plugin steps fall into this bucket — anvil's plugin "
                "SDK (TX5) can add an adapter, or you can replace with a "
                "Chengis-native equivalent.")}
   (:jenkins-line s)))

;; --- catch-all for plugin-step IR types (they all behave the same way) ------

(defmethod render-step :default [s]
  ;; Plugin step types like :jenkins/record-issues, :jenkins/slack-send, …
  ;; The dispatcher records them; for native-mode we mark them out.
  (attach-line
   {:type :jenkins-plugin-step
    :original-type (:type s)
    :raw-args (:raw-args s)
    :FIXME (str "Plugin step `" (some-> s :type name) "`. Logged but not "
                "actively executed. Migrate to a Chengis-native equivalent "
                "when one exists.")}
   (:jenkins-line s)))

(defn render-steps [steps]
  (mapv render-step steps))

;; ---------------------------------------------------------------------------
;; Stage, post, agent, environment rendering
;; ---------------------------------------------------------------------------

(defn- render-post-actions [post]
  (when (seq post)
    (into {} (for [[action steps] post]
               [action (render-steps steps)]))))

(defn- render-stage [stage]
  (cond-> {:name (:name stage)
           :steps (render-steps (:steps stage []))}
    (:agent stage)       (assoc :agent (:agent stage))
    (:environment stage) (assoc :env (:environment stage))
    (:post stage)        (assoc :post (render-post-actions (:post stage)))))

(defn- render-agent [agent]
  agent)  ; agent shapes are already Chengis-clean

;; ---------------------------------------------------------------------------
;; Top-level entry
;; ---------------------------------------------------------------------------

(defn render
  "Pure transformation: Jenkins IR map → Chengisfile-shaped data."
  [pipeline-ir]
  (cond-> {}
    (:agent       pipeline-ir) (assoc :agent (render-agent (:agent pipeline-ir)))
    (:environment pipeline-ir) (assoc :env (:environment pipeline-ir))
    (:libraries   pipeline-ir) (assoc :import (:libraries pipeline-ir))
    true                       (assoc :stages (mapv render-stage (:stages pipeline-ir)))
    (:post pipeline-ir)        (assoc :post (render-post-actions (:post pipeline-ir)))))

;; ---------------------------------------------------------------------------
;; FIXME counting for the coverage report
;; ---------------------------------------------------------------------------

(defn- walk-steps-fixme
  "Argument order is [acc steps] so it composes via `->` threading."
  [acc steps]
  (reduce
   (fn [acc step]
     (let [acc (cond-> acc
                 (:FIXME step)
                 (update :fixmes conj
                         {:type     (:type step)
                          :line     (:jenkins-line step)
                          :name     (:name step)
                          :reason   (:FIXME step)})

                 (= :jenkins-unsupported (:type step))
                 (update :unsupported conj (:name step)))]
       (if-let [body (:body step)]
         (walk-steps-fixme acc body)
         acc)))
   acc
   steps))

(defn fixme-report
  "Walk a rendered Chengisfile and collect a structured report of FIXMEs and
   unsupported step names. Used by the import CLI's text-report output."
  [chengisfile]
  (let [base {:fixmes [] :unsupported []}
        from-stages (reduce (fn [acc stage]
                              (-> acc
                                  (walk-steps-fixme (:steps stage []))
                                  (walk-steps-fixme (mapcat val (:post stage {})))))
                            base
                            (:stages chengisfile))
        from-post (walk-steps-fixme from-stages
                                    (mapcat val (:post chengisfile {})))]
    (-> from-post
        (update :unsupported #(frequencies (filter some? %))))))
