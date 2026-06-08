#!/usr/bin/env bb
;; AN5-RERUN — trigger the wild-corpus dirty-dozen against a live
;; anvil instance configured with the AN5-3d overlay, then tally:
;;   - classification per build
;;   - artifact files on disk per build
;;
;; USAGE
;; -----
;; Single-host (the original AN5-RERUN flow):
;;
;;   ANVIL_URL=http://localhost:8765 \
;;     bb scripts/wild-corpus-rerun.bb [--subset N] [--max-minutes M]
;;
;; Fleet mode (v0.4.2 — CPU-weighted shard distribution):
;;
;;   bb scripts/wild-corpus-rerun.bb \
;;     --fleet=http://heman:8765,http://mario:8765,http://luigi:8765 \
;;     [--cycle=N]              ; rotates which host gets the heavyweights
;;     [--max-minutes M]
;;
;;   Optional explicit per-host weight (overrides the daemon's reported
;;   numExecutors, useful when a host is shared with other work):
;;     --fleet=http://heman:8765:4,http://mario:8765:2,http://luigi:8765:8
;;
;;   Without explicit weights, each daemon is queried for its
;;   `numExecutors` (now sourced from :anvil.queue/workers /
;;   ANVIL_WORKERS / max(2,cores/4) — see queue.clj) and jobs are
;;   apportioned proportionally. The v0.4.1-T6 hand-coded 4/5/5 split
;;   that saturated 12-core Mario while 56-core Luigi sat idle is
;;   replaced by this dynamic apportionment.
;;
;; v0.4 AN6-6 — `--max-minutes M` (default 30) sets the harness cap
;; for the completion-poll loop. apache-hbase routinely needs 90+
;; minutes for its first end-to-end run; bump this for runs that
;; include it.  The dispatcher's per-build timeout (configurable via
;; `:anvil.dispatcher/build-timeout-min` in anvil.edn) is independent —
;; this knob only controls how long the harness WAITS for the build
;; daemon to report completion.  See docs/dispatcher/long-builds.md.
;;
;; The anvil instance(s) must be running with `wild-corpus-agents.edn`
;; loaded as registry (see the file header for instructions).
;;
;; OUTPUT
;; ------
;; Writes a markdown summary to /tmp/wild-corpus-rerun.md and prints
;; the headline numbers to stdout, including (in fleet mode) the
;; per-host shard plan so the operator can sanity-check the split
;; before kicking off a 4-hour run.

(require '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.tools.cli :as cli])

(def anvil-url (or (System/getenv "ANVIL_URL") "http://localhost:8765"))
(def corpus-root (or (System/getenv "WILD_CORPUS_ROOT") "/tmp/anvil-broad"))

;; The buildable wild-corpus entries.
;;
;; v0.4 AN6-3 opt-in: apache-cassandra (`agent { dockerfile … }`) is
;; back in the set now that AN6-3 honors the dockerfile-agent shape.
;; Set `:requires-flag` on entries that need a non-default feature
;; flag turned on in `anvil.edn`:
;;   :anvil.features/dockerfile-agent true   ; for apache-cassandra
;; AND boot the daemon with `:execute? true` so docker build runs.
;; Without the flag, anvil v0.4.0 honestly classifies the build as
;; :unsupported :runtime-unsupported per the AN4-2 contract.
;;
;; v0.4.2 fleet-mode marker: `:heavyweight? true` on builds known to
;; dominate a host's load (long runtime + RAM-heavy JVM). Fleet mode
;; rotates these across hosts each cycle so the same box doesn't keep
;; absorbing every long-runner. Runtime estimates from prior cycles
;; aren't reliable enough to pin to a specific host.
;;
;; eclipse-mojarra stays excluded (k8s + YAML; needs the kubernetes
;; agent runtime which is v0.6 territory per the v0.4 board).
(def dirty-dozen
  [{:name "apache-activemq"      :scm-url "https://github.com/apache/activemq.git" :branch "main"}
   {:name "apache-camel"         :scm-url "https://github.com/apache/camel.git" :branch "main"}
   {:name "apache-camel-quarkus" :scm-url "https://github.com/apache/camel-quarkus.git" :branch "main"}
   {:name "apache-cassandra"     :scm-url "https://github.com/apache/cassandra.git" :branch "trunk"
    :requires-flag :dockerfile-agent
    :heavyweight? true
    :notes "AN6-3 dockerfile-agent. Bump --max-minutes ≥ 60 for first cold-cache run."}
   {:name "apache-cxf"           :scm-url "https://github.com/apache/cxf.git" :branch "main"}
   {:name "apache-hbase"         :scm-url "https://github.com/apache/hbase.git" :branch "master"
    :heavyweight? true
    :notes "Long-runner — bump --max-minutes ≥ 90 (AN6-6)."}
   {:name "apache-maven"         :scm-url "https://github.com/apache/maven.git" :branch "master"
    :notes "AN6-4: shared-lib mavenBuild step is :unsupported with workaround in docs/jenkins-compat/an6-4-mavenbuild-receipt.md"}
   {:name "apache-streampipes"   :scm-url "https://github.com/apache/streampipes.git" :branch "dev"}
   {:name "apache-zookeeper"     :scm-url "https://github.com/apache/zookeeper.git" :branch "master"}
   {:name "eclipse-epsilon"      :scm-url "https://github.com/eclipse/epsilon.git" :branch "main"}
   {:name "eclipse-jdt-core"     :scm-url "https://github.com/eclipse-jdt/eclipse.jdt.core.git" :branch "master"}
   {:name "eclipse-jkube"        :scm-url "https://github.com/eclipse-jkube/jkube.git" :branch "master"
    :notes "AN6-5: secret-subkeys.asc credential workaround in docs/secrets/gpg-subkey.md"}
   {:name "hibernate-orm"        :scm-url "https://github.com/hibernate/hibernate-orm.git" :branch "main"}
   {:name "hibernate-search"     :scm-url "https://github.com/hibernate/hibernate-search.git" :branch "main"}])

(defn- jenkinsfile-for [name]
  (let [f (fs/file corpus-root name "Jenkinsfile")]
    (when (fs/exists? f) (slurp (fs/file f)))))

(defn- register-job! [host {:keys [name scm-url branch]}]
  (let [body (json/encode {:name (str "wild-" name)
                           :jenkinsfile_source (jenkinsfile-for name)
                           :scm {:type "git" :url scm-url :branch branch}})
        resp (http/post (str host "/anvil/admin/jobs")
                        {:body body
                         :headers {"Content-Type" "application/json"}
                         :throw false})]
    (when (>= (:status resp) 400)
      (println "  ! register failed:" (:status resp) (str (:body resp))))
    (:status resp)))

(defn- trigger-build! [host name]
  (let [resp (http/post (str host "/jenkins/job/wild-" name "/build")
                        {:throw false})]
    (when (>= (:status resp) 400)
      (println "  ! trigger failed:" (:status resp)))
    (:status resp)))

(defn- build-status [host name n]
  (try
    (let [resp (http/get (str host "/jenkins/job/wild-" name "/" n "/api/json")
                         {:throw false})]
      (when (= 200 (:status resp))
        (json/decode (:body resp) true)))
    (catch Exception _ nil)))

(defn- workspace-files-on-disk [name]
  ;; The runner writes builds under target/anvil-builds/<job>/<n>/
  ;; from anvil's cwd. We probe for the most-recent build dir.
  ;; (In fleet mode, this only works for builds dispatched to the
  ;; local box; remote hosts' artifacts get a zero count here.
  ;; The fleet driver is responsible for aggregating remote receipts.)
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

;; ---------------------------------------------------------------------------
;; v0.4.2 — fleet-mode shard planning
;; ---------------------------------------------------------------------------

(defn- query-num-executors
  "Ask a daemon for its numExecutors via /jenkins/api/json. Returns
   the int, or nil if the host is unreachable or doesn't expose it.

   In v0.4.2 the daemon reports its actual worker pool size here
   (sourced from :anvil.queue/workers / ANVIL_WORKERS / cores/4) —
   before that fix every daemon hardcoded 2 regardless of capacity."
  [host]
  (try
    (let [resp (http/get (str host "/jenkins/api/json") {:throw false})]
      (when (= 200 (:status resp))
        (some-> (json/decode (:body resp) true) :numExecutors)))
    (catch Exception _ nil)))

(defn- parse-fleet-arg
  "Parse `--fleet=URL[:weight],URL[:weight],...` into a vector of
   `{:host URL :weight INT-or-nil}`. Weight is optional; if missing,
   the caller will query the daemon for numExecutors.

   URL may legitimately contain colons (`http://host:8765`), so we
   only treat a trailing `:N` as a weight if N parses as a positive
   integer."
  [s]
  (mapv (fn [entry]
          (let [bits (str/split entry #":")]
            (if (and (>= (count bits) 4)             ; scheme:host:port:weight
                     (re-matches #"\d+" (last bits)))
              {:host (str/join ":" (butlast bits))
               :weight (parse-long (last bits))}
              {:host entry :weight nil})))
        (str/split s #",")))

(defn- resolve-weights
  "Fill in unspecified per-host weights by querying each daemon's
   numExecutors. A host that fails both an explicit weight AND the
   query falls back to weight=1 (any positive weight beats dropping
   the host; the operator gets a printed warning)."
  [fleet]
  (mapv (fn [{:keys [host weight] :as h}]
          (cond
            weight h
            :else (let [n (query-num-executors host)]
                    (when-not n
                      (println "  ! could not query" host
                               "for numExecutors — defaulting weight=1"))
                    (assoc h :weight (or n 1)))))
        fleet))

(defn- largest-remainder-apportionment
  "Hamilton's method: distribute `total` slots across hosts in
   proportion to their `:weight`. Returns the hosts with `:capacity`
   filled in. Guarantees the capacities sum to `total` exactly (the
   reason for largest-remainder vs. naive rounding: a 4/5/5 split
   over 14 jobs with simple `floor(weight/sum*total)` strands one
   job nowhere)."
  [hosts total]
  (let [sum-w (apply + (map :weight hosts))
        raw (mapv (fn [h]
                    (let [exact (* total (/ (:weight h) (double sum-w)))
                          floor (long exact)]
                      (assoc h :floor floor :frac (- exact floor))))
                  hosts)
        assigned-floor (apply + (map :floor raw))
        leftover (- total assigned-floor)
        ;; Distribute the `leftover` extra slots to the hosts with the
        ;; largest fractional remainders.
        ranked (->> raw
                    (map-indexed (fn [i h] (assoc h :idx i)))
                    (sort-by (juxt (comp - :frac) :idx)))
        winners (set (map :idx (take leftover ranked)))]
    (->> raw
         (map-indexed (fn [i h]
                        (assoc h :capacity (+ (:floor h)
                                              (if (winners i) 1 0)))))
         (mapv #(dissoc % :floor :frac)))))

(defn- plan-shards
  "Given a resolved fleet (hosts with weights), the corpus targets,
   and a cycle number, return `[{:host URL :weight W :jobs [...]} …]`.

   Algorithm:
     1. Apportion total job slots across hosts via Hamilton's method
        proportional to weight.
     2. Pull out heavyweight jobs (`:heavyweight? true`) and assign
        them round-robin starting at host `(cycle mod num-hosts)` so
        no single host eats the same long-runner every cycle. A host
        receives a heavyweight only if its remaining capacity > 0.
     3. Fill the remaining capacity with non-heavyweight jobs, also
        weight-proportional via Hamilton on the leftover counts.

   This replaces the v0.4.1-T6 hand-coded 4/5/5 split that hit Mario
   (12c) with 5 jobs including 2 mavens — load avg 28 on 12 cores."
  [fleet targets cycle]
  (let [hosts (largest-remainder-apportionment fleet (count targets))
        host-vec (mapv (fn [h] (assoc h :jobs [] :remaining (:capacity h))) hosts)
        n-hosts (count host-vec)
        heavies (filter :heavyweight? targets)
        non-heavies (remove :heavyweight? targets)
        ;; Rotate heavies: start at the cycle-offset host, walk forward
        ;; until we find one with remaining > 0, drop the job there.
        assigned (reduce (fn [hosts [i target]]
                           (let [start (mod (+ cycle i) n-hosts)
                                 pick (some (fn [step]
                                              (let [idx (mod (+ start step) n-hosts)]
                                                (when (pos? (:remaining (nth hosts idx)))
                                                  idx)))
                                            (range n-hosts))]
                             (if pick
                               (-> hosts
                                   (update-in [pick :jobs] conj target)
                                   (update-in [pick :remaining] dec))
                               hosts)))                  ; no slot? drop silently — won't happen unless total<heavies
                         host-vec
                         (map-indexed vector heavies))
        ;; Now apportion the non-heavies among hosts with leftover
        ;; capacity. We re-run Hamilton's on (remaining)-weighted hosts.
        ;; This is conservative: a host that ate all its capacity on a
        ;; heavyweight gets no further jobs this cycle (correct — its
        ;; numExecutors is already accounted for).
        leftover-targets (vec non-heavies)
        leftover-fleet (mapv (fn [h] (assoc h :weight (:remaining h)))
                             (filter #(pos? (:remaining %)) assigned))
        leftover-plan (largest-remainder-apportionment leftover-fleet (count leftover-targets))
        ;; Walk through non-heavies, dropping each into the next host
        ;; that still has unassigned leftover-capacity.
        host->extra (zipmap (map :host leftover-plan)
                            (map :capacity leftover-plan))
        final (loop [hosts assigned
                     [t & rest] leftover-targets]
                (if-not t
                  hosts
                  (let [pick (some (fn [i]
                                     (let [h (nth hosts i)
                                           need (host->extra (:host h) 0)
                                           used (- (count (:jobs h))
                                                   (count (filter :heavyweight? (:jobs h))))]
                                       (when (< used need) i)))
                                   (range (count hosts)))]
                    (recur (if pick
                             (update-in hosts [pick :jobs] conj t)
                             hosts)
                           rest))))]
    (mapv #(select-keys % [:host :weight :capacity :jobs]) final)))

(defn- print-shard-plan! [plan]
  (println)
  (println "  Shard plan:")
  (doseq [{:keys [host weight capacity jobs]} plan]
    (printf "    %-40s w=%-3d cap=%-3d (%d job%s)\n"
            host weight capacity (count jobs) (if (= 1 (count jobs)) "" "s"))
    (doseq [j jobs]
      (printf "      - %s%s\n" (:name j) (if (:heavyweight? j) " [heavyweight]" ""))))
  (println))

;; ---------------------------------------------------------------------------
;; Run helpers — operate against a single host
;; ---------------------------------------------------------------------------

(defn- run-on-host!
  "Register + trigger every job in `targets` against `host`.
   Returns nothing; the polling phase queries each host for status."
  [host targets]
  (println "Registering" (count targets) "jobs against" host)
  (doseq [t targets]
    (printf "  → register %s @ %s ... " (:name t) host)
    (let [s (register-job! host t)] (println s)))
  (println "Triggering builds @" host)
  (doseq [t targets]
    (printf "  → trigger %s @ %s ... " (:name t) host)
    (let [s (trigger-build! host (:name t))] (println s))))

(defn- poll-all
  "Poll all `assignments` ({:host :name} pairs) until every build
   reports a non-building status OR the iteration cap is reached.
   Returns a vector of {:name :host :result :building? :duration-ms}."
  [assignments max-minutes]
  (printf "Waiting for completion (poll every 30s, max %d min)...\n" max-minutes)
  (let [completed (atom #{})
        max-iterations (* 2 max-minutes)]
    (loop [iter 0]
      (if (or (= (count @completed) (count assignments))
              (>= iter max-iterations))
        (mapv (fn [{:keys [host name]}]
                (let [s (build-status host name 1)]
                  {:name name
                   :host host
                   :result (:result s)
                   :building? (:building s)
                   :duration-ms (:duration s)}))
              assignments)
        (do (Thread/sleep 30000)
            (doseq [{:keys [host name]} assignments]
              (let [s (build-status host name 1)]
                (when (and s (not (:building s)) (:result s))
                  (swap! completed conj name))))
            (printf "  [%d/%d done]\n"
                    (count @completed) (count assignments))
            (flush)
            (recur (inc iter)))))))

;; ---------------------------------------------------------------------------
;; Receipt rendering
;; ---------------------------------------------------------------------------

(defn- print-summary!
  [targets results artifacts-per-build & {:keys [fleet-plan]}]
  (let [success-count (count (filter #(= "SUCCESS" (:result %)) results))
        failure-count (count (filter #(= "FAILURE" (:result %)) results))
        unsupported-count (count (filter #(= "NOT_BUILT" (:result %)) results))
        total-artifact-files (apply + (map count (vals artifacts-per-build)))
        largest (when (seq (mapcat val artifacts-per-build))
                  (apply max-key :size (mapcat val artifacts-per-build)))]

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
    (when fleet-plan
      (println)
      (println "  Per-host load:")
      (doseq [{:keys [host capacity jobs]} fleet-plan]
        (let [host-results (filter #(= host (:host %)) results)]
          (printf "    %-40s cap=%-3d ran=%-3d ok=%-3d fail=%-3d\n"
                  host capacity (count jobs)
                  (count (filter #(= "SUCCESS" (:result %)) host-results))
                  (count (filter #(= "FAILURE" (:result %)) host-results))))))
    (println "================================================================")

    (spit "/tmp/wild-corpus-rerun.md"
          (str "# wild-corpus rerun receipt\n\n"
               "Date: TODO (the script can't call Date.now here)\n\n"
               (when fleet-plan
                 (str "## Fleet plan\n\n"
                      "| Host | Weight | Capacity | Jobs |\n|---|---|---|---|\n"
                      (str/join "\n"
                                (for [{:keys [host weight capacity jobs]} fleet-plan]
                                  (str "| " host " | " weight " | " capacity " | "
                                       (str/join ", " (map :name jobs)) " |")))
                      "\n\n"))
               "## Headline\n\n"
               "| | Count |\n|---|---|\n"
               "| Builds attempted | " (count targets) " |\n"
               "| :success | " success-count " |\n"
               "| :failure | " failure-count " |\n"
               "| :unsupported | " unsupported-count " |\n"
               "| Artifact files on disk | " total-artifact-files " |\n\n"
               "## Per-build breakdown\n\n"
               "| Build | Host | Result | Artifact files | Largest (bytes) |\n"
               "|---|---|---|---|---|\n"
               (str/join "\n"
                         (for [r results
                               :let [arts (get artifacts-per-build (:name r))]]
                           (str "| " (:name r) " | "
                                (or (:host r) "—") " | "
                                (or (:result r) "?") " | "
                                (count arts) " | "
                                (or (some-> arts first :size str) "—") " |")))))
    (println "Receipt written to /tmp/wild-corpus-rerun.md")))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(def cli-spec
  [["-s" "--subset N" "Run only the first N corpus entries"
    :parse-fn parse-long]
   ["-m" "--max-minutes M" "Max minutes to wait for completion"
    :parse-fn parse-long :default 30]
   ["-f" "--fleet URLS" "Comma-separated daemon URLs (with optional :weight). Triggers fleet mode."]
   ["-c" "--cycle N" "Rotation cycle number for heavyweight assignment (default 0)"
    :parse-fn parse-long :default 0]
   [nil "--plan-only" "Print the shard plan and exit without registering/triggering"]])

(defn -main [& args]
  (let [{:keys [options summary errors]} (cli/parse-opts args cli-spec)
        _ (when (seq errors) (run! println errors) (System/exit 2))
        {:keys [subset max-minutes fleet cycle plan-only]} options
        targets (cond->> dirty-dozen
                  subset (take subset))]
    (if fleet
      ;; ----- Fleet mode -----
      (let [parsed (parse-fleet-arg fleet)
            resolved (resolve-weights parsed)
            plan (plan-shards resolved targets cycle)
            assignments (vec (for [{:keys [host jobs]} plan
                                   j jobs]
                               {:host host :name (:name j)}))]
        (println "Fleet:" (count resolved) "hosts; targets:" (count targets)
                 "; cycle:" cycle)
        (print-shard-plan! plan)
        (when plan-only
          (println "[--plan-only] exiting without dispatch.")
          (System/exit 0))
        ;; Dispatch sequentially per host (register+trigger is fast;
        ;; the long wait is the polling loop below which IS concurrent
        ;; across hosts).
        (doseq [{:keys [host jobs]} plan
                :when (seq jobs)]
          (run-on-host! host jobs))
        (let [results (poll-all assignments max-minutes)
              artifacts-per-build (into {} (for [{:keys [name]} targets]
                                             [name (workspace-files-on-disk name)]))]
          (print-summary! targets results artifacts-per-build :fleet-plan plan)))
      ;; ----- Single-host mode (original AN5-RERUN flow) -----
      (do
        (run-on-host! anvil-url targets)
        (let [assignments (mapv (fn [{:keys [name]}] {:host anvil-url :name name}) targets)
              results (poll-all assignments max-minutes)
              artifacts-per-build (into {} (for [{:keys [name]} targets]
                                             [name (workspace-files-on-disk name)]))]
          (print-summary! targets results artifacts-per-build))))))

(apply -main *command-line-args*)
