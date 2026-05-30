#!/usr/bin/env bb

;; clone-jenkinsfile-corpus.bb — TX14 helper.
;;
;; Shallow-clones a curated list of OSS repos that ship a Jenkinsfile
;; into a single tree under /tmp/jenkinsfile-survey/, ready for the
;; anvil.bench.corpus-survey namespace to walk.
;;
;; The curated list mixes:
;;   - Apache projects (their CI runs on builds.apache.org / Jenkins)
;;   - Spring projects (they use their own Jenkins infra)
;;   - jenkinsci plugins (Jenkins itself eats its own dogfood)
;;   - other prominent OSS projects with Jenkins
;;
;; Most repos have a single top-level `Jenkinsfile`; a few have
;; multiple variants (e.g. `Jenkinsfile.daily`, `Jenkinsfile-integ`)
;; which the survey picks up automatically.
;;
;; Usage:
;;   ./anvil/test-integration/clone-jenkinsfile-corpus.bb
;;     [--out /tmp/jenkinsfile-survey]
;;     [--no-clean]   ; reuse existing clones instead of rm-rf'ing

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def ^:private repos
  "The curated set. Pinning to default branches; we don't pin commits
   here since the survey is descriptive (this is the state of OSS
   Jenkinsfiles right now), not regression-test material. The TX11
   self-host workflow has its own pinned commit."
  [;; Apache
   "apache/camel"
   "apache/camel-kafka-connector"
   "apache/cxf"
   "apache/zookeeper"
   "apache/ambari"
   "apache/struts"
   "apache/hop"
   "apache/maven"
   "apache/phoenix"
   "apache/dubbo"
   "apache/cassandra-java-driver"
   ;; Spring
   "spring-projects/spring-hateoas-examples"
   "spring-projects/spring-boot-data-geode"
   "spring-projects/spring-plugin"
   ;; jenkinsci (Jenkins dogfood)
   "jenkinsci/jenkins"
   "jenkinsci/configuration-as-code-plugin"
   "jenkinsci/kubernetes-plugin"
   "jenkinsci/docker-plugin"
   "jenkinsci/git-plugin"
   "jenkinsci/job-dsl-plugin"
   "jenkinsci/pipeline-model-definition-plugin"
   "jenkinsci/analysis-model"
   "jenkinsci/docker-inbound-agent"
   "jenkinsci/p4-plugin"
   ;; OptaPlanner / KIE
   "apache/incubator-kie-optaplanner"])

(defn- clone! [repo target-dir]
  (let [[owner repo-name] (str/split repo #"/")
        slug (str owner "__" repo-name)
        dest (io/file target-dir slug)]
    (cond
      (.isDirectory dest)
      (do (println (str "  ↺ " repo " (already cloned)"))
          {:slug slug :dest dest :status :reused})

      :else
      (let [r (p/shell {:out :string :err :string :continue true}
                       "git" "clone" "--depth" "1"
                       "--filter=blob:none"
                       (str "https://github.com/" repo ".git")
                       (.getPath dest))]
        (if (zero? (:exit r))
          (do (println (str "  ✓ " repo))
              {:slug slug :dest dest :status :cloned})
          (do (println (str "  ✗ " repo "  — " (str/trim (or (:err r) ""))))
              {:slug slug :dest dest :status :failed
               :err (:err r)}))))))

(defn -main [& args]
  (let [out-dir (or (when-let [i (some #(when (= "--out" %) %) args)]
                      (second (drop-while #(not= "--out" %) args)))
                    "/tmp/jenkinsfile-survey")
        no-clean? (some #{"--no-clean"} args)]
    (when-not no-clean?
      (when (.isDirectory (io/file out-dir))
        (println (str "Cleaning " out-dir " ..."))
        (doseq [^java.io.File c (reverse (file-seq (io/file out-dir)))]
          (.delete c))))
    (.mkdirs (io/file out-dir))
    (println (str "Cloning " (count repos) " repos into " out-dir " ..."))
    (let [results (mapv #(clone! % out-dir) repos)
          ok (count (filter #(#{:cloned :reused} (:status %)) results))
          fail (count (filter #(= :failed (:status %)) results))]
      (println)
      (println (format "RESULT: %d cloned, %d failed" ok fail))
      (println (str "Next: lein with-profile +bench run -m anvil.bench.corpus-survey "
                    "--tree " out-dir " --out docs/jenkins-compat/coverage-survey.md"))
      (System/exit (if (pos? fail) 1 0)))))

(-main *command-line-args*)
