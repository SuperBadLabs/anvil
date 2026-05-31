(ns anvil.bench.anvil-vs-chengis
  "One-shot benchmark: orchestration tax of the anvil-Jenkins path vs
   chengis-native, on equivalent work.

   The headline number from `anvil.bench.jenkins-self` — 170 ms parse,
   0.1 ms dispatch, 25 effects for the real jenkinsci/jenkins
   Jenkinsfile — answers 'does anvil work on a real-world Jenkinsfile?'
   This bench answers the follow-up: 'how much overhead does the
   Jenkins-compat layer add over writing the same pipeline in
   chengis-native?'

   Workload: the jenkinsci/jenkins Jenkinsfile (scripted Pipeline, 4
   stages, ~25 dispatched effects after anvil walks scope wrappers) vs
   a hand-rolled Chengisfile expressing the same four-stage shape in
   EDN. Same intent, two paths.

   The headline split is:
     - **Parse**: Groovy AST round-trip vs `edn/read-string + validate`.
     - **Dispatch**: anvil's effect-recording walker vs chengis-core's
       reference orchestrator with a recording dispatcher.

   Real subprocess execution is identical on both paths and out of
   scope here (per `benchmarks/STRATEGY.md`: measure the tax, not the
   build work)."
  (:require [clojure.java.io :as io]
            [chengis.dsl.chengisfile :as cdsl]
            [chengis.engine.dispatcher :as cd]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.bench.measure :as m]
            [taoensso.timbre :as log]))

(def ^:private jenkinsfile-path
  "/home/srikanth/projects/jenkins/Jenkinsfile")

(def ^:private chengisfile-path
  "benchmarks/fixtures/jenkins-self-equivalent.chengisfile.edn")

;; ---------------------------------------------------------------------------
;; Path A — anvil-Jenkins
;; ---------------------------------------------------------------------------

(defn- anvil-parse [source]
  (t/parse source "Jenkinsfile"))

(defn- anvil-dispatch [ir]
  (let [;; Flatten post/always into the per-stage step list the way
        ;; anvil.bench.jenkins-self does, so the dispatcher records the
        ;; same effect count.
        flat {:stages (mapv (fn [s]
                              {:name  (:name s)
                               :steps (concat (:steps s)
                                              (get-in s [:post :always] []))})
                            (:stages ir))}
        dispatcher (ad/make)]
    (cd/run-pipeline flat dispatcher {:cwd "/workspace"})
    (count @(:effects dispatcher))))

;; ---------------------------------------------------------------------------
;; Path B — chengis-native
;;
;; `chengis.dsl.chengisfile/parse-chengisfile` reads from disk, validates,
;; and converts to the internal `{:stages [{:stage-name … :steps [{:type
;; :shell :command …}]}]}` shape. The reference orchestrator
;; (`chengis.engine.dispatcher/run-pipeline`) walks it given a
;; StepDispatcher; we use a recording one that just counts.
;; ---------------------------------------------------------------------------

(defn- chengis-recording-dispatcher
  "Counts every step dispatched, supports :shell / :docker step types."
  [counter]
  (reify cd/StepDispatcher
    (supports? [_ step] (contains? #{:shell :docker} (:type step)))
    (dispatch  [_ _step ctx]
      (swap! counter inc)
      {:status :ok :ctx ctx})
    (describe  [_] "bench-recording")))

(defn- chengis-parse [path]
  (let [r (cdsl/parse-chengisfile path)]
    (when-let [err (:error r)]
      (throw (ex-info "Chengisfile parse failed" {:error err})))
    (:pipeline r)))

(defn- chengis-dispatch
  "Run the parsed Chengisfile pipeline through the reference orchestrator
   with a counting dispatcher. Returns the number of steps dispatched.

   The reference orchestrator keys on `:name` for stages, but the EDN
   loader produces `:stage-name`; that does not affect step dispatching
   (run-pipeline walks `:steps` regardless), and post-actions live at
   the pipeline level under `:post-actions`. To make the dispatch tax
   include post/always work (mirroring the anvil-Jenkins side's
   flattening of `:post :always` into each stage's steps), we append
   `:post-actions :always` steps onto the last stage before running."
  [pipeline]
  (let [counter (atom 0)
        always (get-in pipeline [:post-actions :always] [])
        stages (vec (:stages pipeline))
        flattened {:stages (if (seq stages)
                             (update-in stages [(dec (count stages)) :steps]
                                        (fnil into []) always)
                             stages)}
        dispatcher (chengis-recording-dispatcher counter)]
    (cd/run-pipeline flattened dispatcher {})
    @counter))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- ratio [x y]
  (if (and y (pos? y)) (/ (double x) (double y)) 0.0))

(defn -main [& _]
  ;; Both anvil's dispatcher and chengis-core log :debug per-step; the
  ;; benchmark hot loop drowns the stdout headline in log lines. Quiet
  ;; everything below :warn for the duration of the bench.
  (log/set-min-level! :warn)
  (let [jenkinsfile-source (slurp (io/file jenkinsfile-path))
        jenkinsfile-size   (count jenkinsfile-source)
        chengisfile-source (slurp (io/file chengisfile-path))
        chengisfile-size   (count chengisfile-source)
        _ (println (format "Targets:"))
        _ (println (format "  Jenkinsfile  : %s (%,d bytes)" jenkinsfile-path jenkinsfile-size))
        _ (println (format "  Chengisfile  : %s (%,d bytes)" chengisfile-path chengisfile-size))

        ;; ---- Parse ----
        _ (println "\n→ parse (source → IR)")
        parse-opts {:iterations 50 :warmup 5}

        anvil-parse-summary (m/measure parse-opts #(anvil-parse jenkinsfile-source))
        anvil-ir            (anvil-parse jenkinsfile-source)
        _ (println "  anvil (Jenkinsfile):")
        _ (println (str "    " (m/format-summary anvil-parse-summary)))
        _ (println (format "    stages parsed: %d" (count (:stages anvil-ir))))

        chengis-parse-summary (m/measure parse-opts #(chengis-parse chengisfile-path))
        chengis-pipeline      (chengis-parse chengisfile-path)
        _ (println "  chengis (Chengisfile):")
        _ (println (str "    " (m/format-summary chengis-parse-summary)))
        _ (println (format "    stages parsed: %d" (count (:stages chengis-pipeline))))

        parse-ratio (ratio (:median-ns anvil-parse-summary)
                           (:median-ns chengis-parse-summary))

        ;; ---- Dispatch ----
        _ (println "\n→ dispatch (IR → recorded effects)")
        dispatch-opts {:iterations 200 :warmup 20}

        anvil-effects   (anvil-dispatch anvil-ir)
        anvil-dispatch-summary (m/measure dispatch-opts #(anvil-dispatch anvil-ir))
        _ (println "  anvil:")
        _ (println (str "    " (m/format-summary anvil-dispatch-summary)))
        _ (println (format "    effects recorded: %d" anvil-effects))

        chengis-effects (chengis-dispatch chengis-pipeline)
        chengis-dispatch-summary (m/measure dispatch-opts #(chengis-dispatch chengis-pipeline))
        _ (println "  chengis:")
        _ (println (str "    " (m/format-summary chengis-dispatch-summary)))
        _ (println (format "    effects recorded: %d" chengis-effects))

        dispatch-ratio (ratio (:median-ns anvil-dispatch-summary)
                              (:median-ns chengis-dispatch-summary))

        ;; ---- Headline ----
        _ (println "\n=========================================================================")
        _ (println "HEADLINE — anvil-Jenkins path vs chengis-native, comparable workload:")
        _ (println (format "  parse:    anvil %7.2f ms  vs  chengis %7.3f ms   → %5.1f× tax"
                           (m/ns->ms (:median-ns anvil-parse-summary))
                           (m/ns->ms (:median-ns chengis-parse-summary))
                           parse-ratio))
        _ (println (format "  dispatch: anvil %7.3f ms  vs  chengis %7.3f ms   → %5.1f× tax"
                           (m/ns->ms (:median-ns anvil-dispatch-summary))
                           (m/ns->ms (:median-ns chengis-dispatch-summary))
                           dispatch-ratio))
        _ (println (format "  effects:  anvil %d        vs  chengis %d        "
                           anvil-effects chengis-effects))
        _ (println "")
        _ (println "  Read: 'going through the Jenkins-compat layer costs you ~Nx in parse,")
        _ (println "        ~Mx in dispatch — for identical orchestration intent. The win is")
        _ (println "        execution unchanged + zero migration; chengis-native is the floor.'")
        _ (println "=========================================================================")]
    (flush)
    (System/exit 0)))
