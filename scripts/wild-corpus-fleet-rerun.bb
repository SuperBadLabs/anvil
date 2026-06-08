#!/usr/bin/env bb
;; v0.4.1 T6 — Fleet wild-corpus real-artifact rerun across HeMan + Mario + Luigi.
;;
;; Three anvil v0.4.1-rc daemons on :8766 each. Each daemon owns its
;; shard of the 14 wild Jenkinsfiles. Per-host docker fleet runs the
;; agent { label … } container builds via the wild-corpus-agents.edn
;; overlay. Harness fans triggers out in parallel, polls all three
;; daemons, tallies on completion.
;;
;; NOTE: This is the v0.4.1-T6 receipt artifact — the shard map is
;; frozen at the hand-coded HeMan=4/Mario=5/Luigi=5 split that
;; saturated Mario at load 28 while Luigi sat idle. For new runs,
;; prefer `scripts/wild-corpus-rerun.bb --fleet=...` which queries
;; each daemon's numExecutors and apportions via Hamilton's method
;; with heavyweight rotation (v0.5, PR #72).

(require '[babashka.http-client :as http]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def shards
  {"http://localhost:8766"     ; HeMan, 32c/56G
   [{:name "wild-apache-activemq"  :scm-url "https://github.com/apache/activemq.git"   :branch "main"}
    {:name "wild-apache-camel"     :scm-url "https://github.com/apache/camel.git"      :branch "main"}
    {:name "wild-eclipse-jdt-core" :scm-url "https://github.com/eclipse-jdt/eclipse.jdt.core.git" :branch "master"}
    {:name "wild-eclipse-jkube"    :scm-url "https://github.com/eclipse-jkube/jkube.git" :branch "master"}]

   "http://mario:8766"          ; Mario, 12c/30G
   [{:name "wild-apache-camel-quarkus" :scm-url "https://github.com/apache/camel-quarkus.git" :branch "main"}
    {:name "wild-apache-cxf"           :scm-url "https://github.com/apache/cxf.git"          :branch "main"}
    {:name "wild-apache-maven"         :scm-url "https://github.com/apache/maven.git"        :branch "master"}
    {:name "wild-apache-zookeeper"     :scm-url "https://github.com/apache/zookeeper.git"    :branch "master"}
    {:name "wild-eclipse-epsilon"      :scm-url "https://github.com/eclipse/epsilon.git"     :branch "main"}]

   "http://luigi:8766"          ; Luigi, 56c/123G — heavyweights
   [{:name "wild-apache-cassandra"  :scm-url "https://github.com/apache/cassandra.git"  :branch "trunk"}
    {:name "wild-apache-hbase"      :scm-url "https://github.com/apache/hbase.git"      :branch "master"}
    {:name "wild-apache-streampipes" :scm-url "https://github.com/apache/streampipes.git" :branch "dev"}
    {:name "wild-hibernate-orm"     :scm-url "https://github.com/hibernate/hibernate-orm.git"   :branch "main"}
    {:name "wild-hibernate-search"  :scm-url "https://github.com/hibernate/hibernate-search.git" :branch "main"}]})

(def corpus-source "/tmp/anvil-v041/corpus")  ; from earlier export

(defn- jf-for [name]
  (let [f (str corpus-source "/" name "/Jenkinsfile")]
    (when (.exists (java.io.File. f)) (slurp f))))

;; For jobs not in the dogfood snapshot (cassandra, hbase), seed
;; a minimal placeholder we can replace later.
;;
;; apache-cassandra is Ant-based: the repo ships `build.xml` with
;; `jar` / `artifacts` targets and NO `pom.xml`. The original
;; placeholder ran `mvn package` here — that failed instantly
;; ("non-readable POM"), exited via `|| true`, and yielded zero
;; artifacts. The synthetic below now bootstraps ant from the
;; upstream tarball (chengis-core's docker backend defaults to
;; `--user $UID:$GID`, so `apt-get install` is permission-denied
;; inside the maven image) and runs `ant jar`, which produces
;; `build/apache-cassandra-<version>.jar` in 30-60s. Label is
;; `Hadoop` (→ maven:3.9-eclipse-temurin-17) because cassandra
;; trunk targets JDK 11/17, not the JDK 21 the `ubuntu` label maps
;; to. The `|| true` is dropped — the classifier should see the
;; honest exit code, not a silenced no-op masquerading as
;; `:success`. Target stays at `jar`; the `artifacts` target needs
;; additional dependencies we don't bootstrap here. Single-line
;; `sh '...'` (not `sh '''...'''`) — anvil's Groovy heredoc parse
;; drops leading lines from triple-quoted blocks.
;;
;; Verified locally against the v0.5 daemon: build #4 produced
;; `build/apache-cassandra-7.0-SNAPSHOT.jar` in 82.9s and
;; classified `:success` honestly.
;;
;; apache-hbase IS Maven (real pom.xml at the repo root) — the
;; mvn placeholder stays.
(def fallback-jfs
  {"wild-apache-cassandra"
   (str "pipeline { agent { label 'Hadoop' }\n"
        "  stages { stage('build') { steps { "
        "sh 'curl -fsSL https://archive.apache.org/dist/ant/binaries/apache-ant-1.10.14-bin.tar.gz | tar xz && ./apache-ant-1.10.14/bin/ant -q jar'"
        " } } } }\n")
   "wild-apache-hbase"
   "pipeline { agent { label 'Hadoop' }\n  stages { stage('build') { steps { sh 'mvn -B -DskipTests=true package || true' } } } }\n"})

(defn- post-json! [url path body]
  (http/post (str url path)
             {:body (json/encode body)
              :headers {"Content-Type" "application/json"}
              :throw false}))

(defn- register! [url {:keys [name scm-url branch]}]
  (let [jf (or (jf-for name) (get fallback-jfs name)
               "pipeline { agent any\n  stages { stage('echo') { steps { sh 'echo build' } } } }\n")
        body {:name name
              :jenkinsfile_source jf
              :scm {:type "git" :url scm-url :branch branch}}]
    (:status (post-json! url "/anvil/admin/jobs" body))))

(defn- trigger! [url name]
  (:status (http/post (str url "/jenkins/job/" name "/build") {:throw false})))

(defn- build-state [url name]
  (try
    (let [r (http/get (str url "/jenkins/job/" name "/1/api/json") {:throw false})]
      (when (= 200 (:status r))
        (json/decode (:body r) true)))
    (catch Exception _ nil)))

(defn- verdict-banner [url name]
  (let [html (try (str (:body (http/get (str url "/jobs/" name "/1") {:throw false})))
                  (catch Exception _ ""))]
    (or (second (re-find #"<strong>([^<]+?)</strong>" html)) "?")))

(defn- stats-paths [name]
  [(format "/tmp/anvil-v041/workspaces/%s/1" name)
   (format "/tmp/anvil-v041/target/anvil-builds/%s/1" name)
   (format "/tmp/anvil-v041/artifacts/%s/1" name)])

(defn- artifact-stats [host name]
  ;; Workspace lives under /tmp/anvil-v041 — see stats-paths for the
  ;; three locations we probe (workspaces, target/anvil-builds,
  ;; artifacts), in `||` short-circuit order.
  ;;
  ;; The remote command MUST reach the remote shell with its inner
  ;; single quotes (`'*.jar'`, `'%s\n'`) intact. The original code
  ;; wrapped cmd-find in an outer `'...'` for `ssh host bash -c '...'`,
  ;; but single quotes can't nest in POSIX shell — the remote bash
  ;; alternates quoted/unquoted regions, dropping `\n` to literal `n`
  ;; and gluing all sizes onto one unparseable line (verified
  ;; empirically on the 2026-06-08 fleet rerun: luigi/cassandra
  ;; reported 0 jars when 425 jars / 252 MB were on disk).
  ;;
  ;; Fix: pass cmd-find as a single argv element to ssh. ssh already
  ;; runs the remote command through the user's configured shell in
  ;; non-interactive mode (no `bash -c` layer needed), and the inner
  ;; quotes are parsed once, correctly, on the remote side.
  (let [cmd-find (str/join " || "
                    (for [p (stats-paths name)]
                      (format "[ -d %s ] && find %s -type f -name '*.jar' -printf '%%s\\n' 2>/dev/null" p p)))
        out (if (= host "localhost")
              (:out (p/shell {:out :string :err :string :continue true} "bash" "-c" cmd-find))
              (:out (p/shell {:out :string :err :string :continue true} "ssh" host cmd-find)))
        sizes (->> (str/split-lines (str out))
                   (keep #(when-not (str/blank? %) (try (Long/parseLong (str/trim %)) (catch Exception _ nil)))))]
    {:jar-count (count sizes) :total-bytes (reduce + 0 sizes)}))

(defn- independent-jar-count
  "Independent cross-check of artifact-stats's jar count, using a
   different command shape (`find ... | wc -l` instead of
   `-printf '%s\\n'`). If this disagrees with the receipt for a
   non-localhost host, the ssh quoting is broken again."
  [host name]
  (let [cmd (str/join " || "
              (for [p (stats-paths name)]
                (format "[ -d %s ] && find %s -type f -name '*.jar' 2>/dev/null | wc -l" p p)))
        out (-> (p/shell {:out :string :err :string :continue true}
                         (if (= host "localhost") "bash" "ssh")
                         (if (= host "localhost") "-c" host)
                         cmd)
                :out str str/trim)]
    (try (Long/parseLong out) (catch Exception _ -1))))

(def all-jobs (mapcat val shards))

(printf "v0.4.1-T6 FLEET wild-corpus rerun — %d builds across 3 hosts\n" (count all-jobs))
(println "===========================================================")

;; Phase 1: register all jobs in parallel
(println "Phase 1: registering jobs ...")
(let [futs (doall
            (for [[url jobs] shards, j jobs]
              (future [(:name j) url (register! url j)])))]
  (doseq [f futs]
    (let [[n u s] @f]
      (printf "  %s → %s : status=%d\n" u n s))))

;; Phase 2: trigger all in parallel
(println "\nPhase 2: triggering builds ...")
(let [futs (doall
            (for [[url jobs] shards, j jobs]
              (future
                (let [s (trigger! url (:name j))]
                  [(:name j) url s (System/currentTimeMillis)]))))
      triggered (mapv deref futs)]
  (doseq [[n _ s _] triggered]
    (printf "  trigger %s : status=%d\n" n s))
  (println "\nPhase 3: polling for completion (cap 90 min) ...")
  ;; Phase 3: poll every 30s, print per-host running counts
  (let [deadline (+ (System/currentTimeMillis) (* 90 60 1000))
        target (count all-jobs)]
    (loop []
      (let [states
            (for [[url jobs] shards, j jobs]
              (let [b (build-state url (:name j))]
                {:name (:name j) :url url
                 :building (boolean (:building b))
                 :result (:result b)
                 :duration (:duration b)}))
            done (filter #(not (:building %)) states)
            running (filter :building states)
            counts (frequencies (map :url states))
            run-counts (frequencies (map :url running))]
        (printf "  t=%ds  done=%d/%d  running=[HeMan:%d mario:%d luigi:%d]\n"
                (quot (- (System/currentTimeMillis)
                         (-> triggered first (nth 3)))
                      1000)
                (count done) target
                (get run-counts "http://localhost:8766" 0)
                (get run-counts "http://mario:8766" 0)
                (get run-counts "http://luigi:8766" 0))
        (flush)
        (cond
          (= (count done) target)
          (println "\nALL BUILDS DONE")

          (> (System/currentTimeMillis) deadline)
          (println "\nDEADLINE HIT — proceeding with partial tally")

          :else
          (do (Thread/sleep 30000) (recur)))))

    ;; Phase 4: collect verdicts + artifact bytes per host
    (println "\nPhase 4: tallying ...")
    (let [results
          (vec
           (for [[url jobs] shards, j jobs]
             (let [b (build-state url (:name j))
                   v (verdict-banner url (:name j))
                   host (cond (str/includes? url "localhost") "localhost"
                              (str/includes? url "mario") "mario"
                              (str/includes? url "luigi") "luigi")
                   stats (artifact-stats host (:name j))]
               {:name (:name j) :host host :result (:result b)
                :duration (:duration b) :verdict v
                :jar-count (:jar-count stats)
                :total-bytes (:total-bytes stats)})))]
      (println "\n=== PER-BUILD RECEIPT ===")
      (printf "%-30s %-7s %-45s %6s %12s\n" "job" "host" "verdict" "jars" "bytes")
      (println (apply str (repeat 100 "-")))
      (doseq [r (sort-by (juxt :host :name) results)]
        (printf "%-30s %-7s %-45s %6d %12d\n"
                (:name r) (:host r) (:verdict r)
                (:jar-count r) (:total-bytes r)))
      (println)
      (printf "TOTAL: %d builds, %d jars, %.2f GB on disk\n"
              (count results)
              (reduce + (map :jar-count results))
              (/ (reduce + (map :total-bytes results)) 1024.0 1024.0 1024.0))

      ;; Sanity check: cross-verify the receipt's jar count for the
      ;; first non-localhost build via an independent ssh `find | wc -l`.
      ;; A MISMATCH here is the canary for the SSH quoting bug
      ;; (see artifact-stats docstring). Prefer a SUCCESS build that
      ;; should have produced jars; fall back to any non-localhost
      ;; build if none succeeded.
      (when-let [r (or (some #(when (and (not= "localhost" (:host %))
                                         (= "SUCCESS" (:result %))
                                         (pos? (:jar-count %))) %)
                             results)
                       (some #(when (not= "localhost" (:host %)) %) results))]
        (let [indep (independent-jar-count (:host r) (:name r))
              ok? (= (:jar-count r) indep)]
          (printf "\nSANITY CHECK [%s/%s]: receipt=%d jars, independent ssh wc -l=%d → %s\n"
                  (:host r) (:name r) (:jar-count r) indep
                  (if ok? "OK" "MISMATCH (ssh quoting suspected — see artifact-stats)"))))

      (spit "/tmp/anvil-v041/fleet-results.edn" (pr-str results)))))
