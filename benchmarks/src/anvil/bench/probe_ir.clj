(ns anvil.bench.probe-ir
  "Probe: show what the parser + matrix expander produce for
   Jenkins's own Jenkinsfile."
  (:require [clojure.java.io :as io]
            [anvil.compat.jenkins.translator :as t]
            [anvil.compat.jenkins.matrix-expander :as mx]))

(defn -main [& _]
  (let [source (slurp (io/file "/home/srikanth/projects/jenkins/Jenkinsfile"))
        base (t/parse source "Jenkinsfile")
        expanded (mx/expand-matrices base source)]
    (println "── Base IR (TX11A) ─────────────────────────────────────────")
    (println "  stages:" (count (:stages base)))
    (doseq [[i s] (map-indexed vector (:stages base))]
      (println (format "    %d. %s  (%d steps)" i (pr-str (:name s)) (count (:steps s)))))
    (println)
    (println "── Expanded IR (TX11B) ─────────────────────────────────────")
    (println "  stages:" (count (:stages expanded)))
    (doseq [[i s] (map-indexed vector (:stages expanded))]
      (println (format "    %d. %s  (%d steps)%s"
                       i (pr-str (:name s)) (count (:steps s))
                       (if-let [b (:matrix-binding s)]
                         (str "  binding=" (pr-str (select-keys b ["platform" "jdk"])))
                         ""))))
    (println)
    (println "  Receipt:")
    (let [mx-entry (some #(when (:matrix-expansion %) (:matrix-expansion %))
                         (:options expanded))]
      (doseq [[k v] mx-entry]
        (println (format "    %s = %s" k (pr-str v)))))
    (flush)
    (System/exit 0)))
