(ns anvil.bench.jenkins-self
  "One-shot benchmark: run anvil's parser + matrix expander + dispatcher
   against the *actual* Jenkinsfile that the jenkinsci/jenkins repo uses
   to build itself (256 lines, scripted Pipeline, real-world complex).
   Reports raw distribution + a one-line headline."
  (:require [clojure.java.io :as io]
            [chengis.engine.dispatcher :as d]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.matrix-expander :as mx]
            [anvil.compat.jenkins.dispatcher :as ad]
            [anvil.bench.measure :as m]))

(def ^:private target-path
  "Path to the jenkinsci/jenkins Jenkinsfile to bench against. CI passes
   JENKINS_REPO env var pointing at a shallow clone; local dev uses the
   default for fast iteration."
  (or (some-> (System/getenv "JENKINS_REPO")
              (str "/Jenkinsfile"))
      "/home/srikanth/projects/jenkins/Jenkinsfile"))

(defn- run-dispatch-once [pre-parsed-ir]
  (let [flat {:stages (mapv (fn [s]
                              {:name (:name s)
                               :steps (concat (:steps s)
                                              (get-in s [:post :always] []))})
                            (:stages pre-parsed-ir))}
        dispatcher (ad/make)]
    (d/run-pipeline flat dispatcher {:cwd "/workspace"})
    (count @(:effects dispatcher))))

(defn -main [& _]
  (let [source (slurp (io/file target-path))
        size (count source)
        _ (println (format "Target: %s (%,d bytes)" target-path size))

        ;; Parser: source → IR
        _ (println "\n→ parser (Jenkinsfile source → IR)")
        parse-opts {:iterations 50 :warmup 5}
        parse-summary (m/measure parse-opts #(t/parse source "Jenkinsfile"))
        _ (println (m/format-summary parse-summary))
        base-ir (t/parse source "Jenkinsfile")
        _ (println (format "  stages parsed (TX11A): %d" (count (:stages base-ir))))
        _ (println (format "  throughput:    %,.1f KB/s"
                           (/ size (max 1 (m/ns->ms (:median-ns parse-summary))) 1.024)))

        ;; Matrix expander: collapse templated matrix stages into concrete combos
        _ (println "\n→ matrix expander (TX11B; .combinations { … } → N stages)")
        mx-opts {:iterations 50 :warmup 5}
        mx-summary (m/measure mx-opts #(mx/expand-matrices base-ir source))
        _ (println (m/format-summary mx-summary))
        ir (mx/expand-matrices base-ir source)
        mx-receipt (some :matrix-expansion (or (:options ir) []))
        _ (println (format "  matrices found:        %d" (:matrices-found mx-receipt 0)))
        _ (println (format "  combinations tried:    %d" (:combinations-tried mx-receipt 0)))
        _ (println (format "  combinations surviving:%d" (:combinations-surviving mx-receipt 0)))
        _ (println (format "  stages after expansion: %d (was %d)"
                           (count (:stages ir))
                           (count (:stages base-ir))))

        ;; Dispatcher: IR → recorded effects (orchestration tax only)
        _ (println "\n→ dispatcher (IR → recorded effects)")
        dispatch-opts {:iterations 100 :warmup 10}
        effects-count (run-dispatch-once ir)
        dispatch-summary (m/measure dispatch-opts #(run-dispatch-once ir))
        _ (println (m/format-summary dispatch-summary))
        _ (println (format "  effects recorded: %d  →  %,d effects/sec"
                           effects-count
                           (long (/ (* effects-count 1e9)
                                    (max 1 (:median-ns dispatch-summary))))))

        ;; Headline
        _ (println "\n=========================================================================")
        _ (println "HEADLINE — Jenkins's own 256-line Jenkinsfile through anvil:")
        _ (println (format "  parse:    %.2f ms median   (%,.1f KB/s)"
                           (m/ns->ms (:median-ns parse-summary))
                           (/ size (max 1 (m/ns->ms (:median-ns parse-summary))) 1.024)))
        _ (println (format "  matrix:   %.2f ms median   (%d matrices, %d combos → %d surviving)"
                           (m/ns->ms (:median-ns mx-summary))
                           (:matrices-found mx-receipt 0)
                           (:combinations-tried mx-receipt 0)
                           (:combinations-surviving mx-receipt 0)))
        _ (println (format "  dispatch: %.3f ms median   (%d stages, %d effects, %,d effects/sec)"
                           (m/ns->ms (:median-ns dispatch-summary))
                           (count (:stages ir))
                           effects-count
                           (long (/ (* effects-count 1e9)
                                    (max 1 (:median-ns dispatch-summary))))))
        _ (println "=========================================================================")]
    (flush)
    (System/exit 0)))
