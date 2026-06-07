#!/usr/bin/env bb
;; AN5-RERUN — trigger the wild-corpus dirty-dozen against a live
;; anvil instance configured with the AN5-3d overlay, then tally:
;;   - classification per build
;;   - artifact files on disk per build
;;
;; USAGE
;; -----
;;   ANVIL_URL=http://localhost:8765 \
;;     bb scripts/wild-corpus-rerun.bb [--subset N] [--max-minutes M]
;;
;; v0.4 AN6-6 — `--max-minutes M` (default 30) sets the harness cap
;; for the completion-poll loop. apache-hbase routinely needs 90+
;; minutes for its first end-to-end run; bump this for runs that
;; include it.  The dispatcher's per-build timeout (configurable via
;; `:anvil.dispatcher/build-timeout-min` in anvil.edn) is independent —
;; this knob only controls how long the harness WAITS for the build
;; daemon to report completion.  See docs/dispatcher/long-builds.md.
;;
;; The anvil instance must be running with `wild-corpus-agents.edn`
;; loaded as its registry (see the file header for instructions).
;;
;; OUTPUT
;; ------
;; Writes a markdown summary to /tmp/wild-corpus-rerun.md and prints
;; the headline numbers to stdout:
;;
;;   wild-corpus rerun [date] — anvil <version>
;;   Builds attempted:  N
;;   Builds :success:   X
;;   Builds :failure:   Y
;;   Builds :unsupported: Z
;;   Total artifact files on disk: K
;;   Largest single artifact: M bytes (job=...)
;;
;; The /tmp/wild-corpus-rerun.md file has the per-build breakdown for
;; pasting into a receipt or the v0.4 board.

(require '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def anvil-url (or (System/getenv "ANVIL_URL") "http://localhost:8765"))
(def corpus-root (or (System/getenv "WILD_CORPUS_ROOT") "/tmp/anvil-broad"))

;; The 12 buildable wild-corpus entries. apache-cassandra (dockerfile,
;; harness TIMEOUT) and eclipse-mojarra (k8s + YAML, harness TIMEOUT)
;; are intentionally excluded — see wild-corpus-agents.edn header.
(def dirty-dozen
  [{:name "apache-activemq"      :scm-url "https://github.com/apache/activemq.git" :branch "main"}
   {:name "apache-camel"         :scm-url "https://github.com/apache/camel.git" :branch "main"}
   {:name "apache-camel-quarkus" :scm-url "https://github.com/apache/camel-quarkus.git" :branch "main"}
   {:name "apache-cxf"           :scm-url "https://github.com/apache/cxf.git" :branch "main"}
   {:name "apache-hbase"         :scm-url "https://github.com/apache/hbase.git" :branch "master"}
   {:name "apache-maven"         :scm-url "https://github.com/apache/maven.git" :branch "master"}
   {:name "apache-streampipes"   :scm-url "https://github.com/apache/streampipes.git" :branch "dev"}
   {:name "apache-zookeeper"     :scm-url "https://github.com/apache/zookeeper.git" :branch "master"}
   {:name "eclipse-epsilon"      :scm-url "https://github.com/eclipse/epsilon.git" :branch "main"}
   {:name "eclipse-jdt-core"     :scm-url "https://github.com/eclipse-jdt/eclipse.jdt.core.git" :branch "master"}
   {:name "eclipse-jkube"        :scm-url "https://github.com/eclipse-jkube/jkube.git" :branch "master"}
   {:name "hibernate-orm"        :scm-url "https://github.com/hibernate/hibernate-orm.git" :branch "main"}
   {:name "hibernate-search"     :scm-url "https://github.com/hibernate/hibernate-search.git" :branch "main"}])

(defn- jenkinsfile-for [name]
  (let [f (fs/file corpus-root name "Jenkinsfile")]
    (when (fs/exists? f) (slurp (fs/file f)))))

(defn- register-job! [{:keys [name scm-url branch]}]
  (let [body (json/encode {:name (str "wild-" name)
                           :jenkinsfile_source (jenkinsfile-for name)
                           :scm {:type "git" :url scm-url :branch branch}})
        resp (http/post (str anvil-url "/anvil/admin/jobs")
                        {:body body
                         :headers {"Content-Type" "application/json"}
                         :throw false})]
    (when (>= (:status resp) 400)
      (println "  ! register failed:" (:status resp) (str (:body resp))))
    (:status resp)))

(defn- trigger-build! [name]
  (let [resp (http/post (str anvil-url "/jenkins/job/wild-" name "/build")
                        {:throw false})]
    (when (>= (:status resp) 400)
      (println "  ! trigger failed:" (:status resp)))
    (:status resp)))

(defn- build-status [name n]
  (try
    (let [resp (http/get (str anvil-url "/jenkins/job/wild-" name "/" n "/api/json")
                         {:throw false})]
      (when (= 200 (:status resp))
        (json/decode (:body resp) true)))
    (catch Exception _ nil)))

(defn- workspace-files-on-disk [name]
  ;; The runner writes builds under target/anvil-builds/<job>/<n>/
  ;; from anvil's cwd. We probe for the most-recent build dir.
  (let [job-dir (fs/file (System/getProperty "user.dir") "target" "anvil-builds"
                          (str "wild-" name))]
    (if (fs/exists? job-dir)
      (let [recent (->> (fs/list-dir job-dir)
                        (filter fs/directory?)
                        (sort-by #(fs/last-modified-time %))
                        last)]
        (when recent
          (->> (fs/glob recent "**" {:max-depth 5})
               (filter fs/regular-file?)
               (map (fn [p] {:path (str p)
                             :size (fs/size p)}))
               (sort-by :size >)
               vec)))
      [])))

(defn -main [& args]
  (let [subset (when-let [n (some #(when (re-find #"^--subset" %) %) args)]
                 (Long/parseLong (last (str/split n #"="))))
        targets (cond->> dirty-dozen
                  subset (take subset))
        _ (println "Registering" (count targets) "jobs against" anvil-url)
        _ (doseq [t targets]
            (printf "  → register %s ... " (:name t))
            (let [s (register-job! t)] (println s)))
        _ (println "Triggering builds...")
        _ (doseq [t targets]
            (printf "  → trigger %s ... " (:name t))
            (let [s (trigger-build! (:name t))] (println s)))
        ;; Wait for completion — each build has its own timeout.
        ;; v0.4 AN6-6: the cap is configurable via --max-minutes so
        ;; runs that include apache-hbase don't get clipped at the
        ;; 30-min default before its first artifact lands.
        max-minutes (or (some-> (System/getProperty "max-minutes") parse-long) 30)
        _ (printf "Waiting for completion (poll every 30s, max %d min)...\n" max-minutes)
        completed (atom #{})
        max-iterations (* 2 max-minutes)  ; 30s polls per minute
        results (loop [iter 0]
                  (if (or (= (count @completed) (count targets))
                          (>= iter max-iterations))
                    (mapv (fn [{:keys [name]}]
                            (let [s (build-status name 1)]
                              {:name name
                               :result (:result s)
                               :building? (:building s)
                               :duration-ms (:duration s)}))
                          targets)
                    (do (Thread/sleep 30000)
                        (doseq [{:keys [name]} targets]
                          (let [s (build-status name 1)]
                            (when (and s (not (:building s)) (:result s))
                              (swap! completed conj name))))
                        (printf "  [%d/%d done]\n"
                                (count @completed) (count targets))
                        (flush)
                        (recur (inc iter)))))
        artifacts-per-build (into {} (for [{:keys [name]} targets]
                                       [name (workspace-files-on-disk name)]))
        ;; Headline numbers
        success-count (count (filter #(= "SUCCESS" (:result %)) results))
        failure-count (count (filter #(= "FAILURE" (:result %)) results))
        unsupported-count (count (filter #(= "NOT_BUILT" (:result %)) results))
        total-artifact-files (apply + (map count (vals artifacts-per-build)))
        largest (apply max-key :size (mapcat val artifacts-per-build))]

    (println)
    (println "================================================================")
    (println "  WILD-CORPUS RERUN RECEIPT")
    (println "================================================================")
    (printf  "  Builds attempted:    %d\n" (count targets))
    (printf  "  Builds :success:     %d\n" success-count)
    (printf  "  Builds :failure:     %d\n" failure-count)
    (printf  "  Builds :unsupported: %d\n" unsupported-count)
    (printf  "  Total artifact files on disk:  %d\n" total-artifact-files)
    (when largest
      (printf "  Largest artifact:    %d bytes (%s)\n" (:size largest) (:path largest)))
    (println "================================================================")

    ;; Write markdown receipt for pasting
    (spit "/tmp/wild-corpus-rerun.md"
          (str "# wild-corpus rerun receipt\n\n"
               "Date: TODO (the script can't call Date.now here)\n\n"
               "## Headline\n\n"
               "| | Count |\n|---|---|\n"
               "| Builds attempted | " (count targets) " |\n"
               "| :success | " success-count " |\n"
               "| :failure | " failure-count " |\n"
               "| :unsupported | " unsupported-count " |\n"
               "| Artifact files on disk | " total-artifact-files " |\n\n"
               "## Per-build breakdown\n\n"
               "| Build | Result | Artifact files | Largest (bytes) |\n"
               "|---|---|---|---|\n"
               (str/join "\n"
                         (for [r results
                               :let [arts (get artifacts-per-build (:name r))]]
                           (str "| " (:name r) " | "
                                (or (:result r) "?") " | "
                                (count arts) " | "
                                (or (some-> arts first :size str) "—") " |")))))
    (println "Receipt written to /tmp/wild-corpus-rerun.md")))

(apply -main *command-line-args*)
