#!/usr/bin/env bb
;; feed-anvil-broad.bb — broader Jenkinsfile diversity matrix
;; 15 non-jenkinsci OSS projects spanning declarative/scripted/matrix/
;; kubernetes/dockerfile/shared-lib/nested-nodes/withCredentials shapes.
(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[babashka.process :as bp]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.pprint :refer [pprint]])

(def anvil "http://localhost:8765")
(def workdir "/tmp/anvil-broad")
(def per-job-timeout-secs 180)

(def projects
  [{:name "hibernate-orm"        :repo "https://github.com/hibernate/hibernate-orm.git"            :branch "main"   :path "Jenkinsfile"            :shape :scripted+sharedlib+withcreds}
   {:name "hibernate-search"     :repo "https://github.com/hibernate/hibernate-search.git"         :branch "main"   :path "Jenkinsfile"            :shape :scripted+parallel+withcreds}
   {:name "eclipse-jdt-core"     :repo "https://github.com/eclipse-jdt/eclipse.jdt.core.git"       :branch "master" :path "Jenkinsfile"            :shape :declarative-simple}
   {:name "eclipse-epsilon"      :repo "https://github.com/eclipse-epsilon/epsilon.git"            :branch "main"   :path "Jenkinsfile"            :shape :declarative+kubernetes-agent}
   {:name "eclipse-jkube"        :repo "https://github.com/eclipse/jkube.git"                      :branch "master" :path "Jenkinsfile"            :shape :declarative+withcreds+gpg}
   {:name "apache-camel"         :repo "https://github.com/apache/camel.git"                       :branch "main"   :path "Jenkinsfile"            :shape :declarative+matrix+withcreds}
   {:name "apache-camel-quarkus" :repo "https://github.com/apache/camel-quarkus.git"               :branch "main"   :path "Jenkinsfile"            :shape :declarative-minimal-deploy}
   {:name "apache-maven"         :repo "https://github.com/apache/maven.git"                       :branch "master" :path "Jenkinsfile"            :shape :declarative-small}
   {:name "apache-zookeeper"     :repo "https://github.com/apache/zookeeper.git"                   :branch "master" :path "Jenkinsfile"            :shape :declarative+matrix+jdk-axes+cron}
   {:name "apache-cxf"           :repo "https://github.com/apache/cxf.git"                         :branch "main"   :path "Jenkinsfile"            :shape :declarative+per-stage-agent+matrix}
   {:name "apache-activemq"      :repo "https://github.com/apache/activemq.git"                    :branch "main"   :path "Jenkinsfile"            :shape :declarative+triggers+withcreds}
   {:name "apache-streampipes"   :repo "https://github.com/apache/streampipes.git"                 :branch "dev"    :path "Jenkinsfile"            :shape :declarative-nested-node}
   {:name "apache-cassandra"     :repo "https://github.com/apache/cassandra.git"                   :branch "trunk"  :path ".jenkins/Jenkinsfile"   :shape :declarative+scripted-mix+dockerfile}
   {:name "apache-hbase"         :repo "https://github.com/apache/hbase.git"                       :branch "master" :path "dev-support/Jenkinsfile" :shape :declarative+nested-nodes+parallel}
   {:name "eclipse-mojarra"      :repo "https://github.com/eclipse-ee4j/mojarra.git"               :branch "master" :path "Jenkinsfile"            :shape :declarative+kubernetes-yaml+release}])

(fs/create-dirs workdir)

(defn clone-or-skip [{:keys [name repo branch]}]
  (let [dest (str workdir "/" name)]
    (if (fs/exists? (str dest "/.git"))
      :exists
      (let [r (bp/shell {:dir workdir :continue true :out :string :err :string}
                       "git" "clone" "--depth" "1" "--branch" branch repo name)]
        (if (zero? (:exit r)) :cloned :clone-failed)))))

(defn jenkinsfile-source [{:keys [name path]}]
  (let [p (str workdir "/" name "/" path)]
    (when (fs/exists? p) (slurp p))))

(defn register [{:keys [name]} src]
  (let [body (json/generate-string {:name name :jenkinsfile_source src})]
    (http/post (str anvil "/anvil/admin/jobs")
               {:headers {"content-type" "application/json"}
                :body body
                :throw false})))

(defn trigger [{:keys [name]}]
  (http/post (str anvil "/jenkins/job/" name "/build")
             {:throw false}))

(defn wait-for [{:keys [name]} timeout-secs]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 timeout-secs))]
    (loop []
      (let [r (http/get (str anvil "/jenkins/job/" name "/1/api/json") {:throw false})]
        (cond
          (and (= 200 (:status r))
               (let [b (json/parse-string (:body r) true)] (and (:result b) (false? (:building b)))))
          (json/parse-string (:body r) true)
          (> (System/currentTimeMillis) deadline)
          {:result "TIMEOUT"}
          :else
          (do (Thread/sleep 1500) (recur)))))))

(defn console [{:keys [name]}]
  (let [r (http/get (str anvil "/jenkins/job/" name "/1/consoleText") {:throw false})]
    (if (= 200 (:status r)) (:body r) "")))

(defn classify [c]
  (let [shs (count (re-seq #"(?m)^\+ " c))
        scaffolds (count (re-seq #"\[shared-lib-unresolved\]|\[unknown\]|\[scripted-stage" c))
        unresolved (->> (re-seq #":name \"([a-zA-Z][a-zA-Z0-9_]*)\".*?:type :jenkins/shared-lib-unresolved" c)
                        (map second) distinct)
        unknown (->> (re-seq #"\[unknown\] \{:name \"([^\"]+)\"" c)
                     (map second) distinct (take 5))
        ex (re-find #"scripted-exception.*?\"message\" \"([^\"]+)\"" c)]
    {:sh shs :scaffolds scaffolds :unresolved unresolved :unknown unknown
     :exception (when ex (subs (second ex) 0 (min 60 (count (second ex)))))}))

(println "anvil:" (-> (http/get (str anvil "/api/status")) :body (json/parse-string true) :version))
(println "=== broader-corpus matrix (" (count projects) " projects) ===\n")

(def results
  (vec
   (for [p projects]
     (do
       (println (str "========== " (:name p) " (" (:shape p) ") =========="))
       (let [clone-r (clone-or-skip p)
             src (jenkinsfile-source p)]
         (cond
           (nil? src)
           (do (println "  NO Jenkinsfile at" (:path p) "— skipping")
               (assoc p :status :no-jenkinsfile))

           :else
           (let [_ (println "  Jenkinsfile:" (count src) "bytes")
                 reg (register p src)
                 _ (println "  register:" (:status reg))
                 _ (trigger p)
                 t0 (System/currentTimeMillis)
                 build (wait-for p per-job-timeout-secs)
                 dur (- (System/currentTimeMillis) t0)
                 c (console p)
                 cls (classify c)]
             (println (format "  result: %-9s harness-wall: %dms anvil-dur: %sms"
                              (:result build) dur (or (:duration build) "")))
             (println (format "  sh:%d scaffolds:%d unresolved-libs:%s exception:%s"
                              (:sh cls) (:scaffolds cls)
                              (pr-str (:unresolved cls))
                              (or (:exception cls) "-")))
             (assoc p :status :ran :result (:result build)
                    :anvil-duration-ms (:duration build)
                    :harness-wall-ms dur
                    :classification cls))))))))

(spit (str workdir "/results.edn")
      (with-out-str (pprint results)))

(println "\n=== MATRIX ===")
(println (format "%-22s %-30s %-9s %-7s %-7s %s"
                 "project" "shape" "result" "ms" "sh" "unresolved-libs / unknown-steps"))
(println (apply str (repeat 140 "-")))
(doseq [r results]
  (let [cls (:classification r)
        sig (cond
              (= :no-jenkinsfile (:status r)) "(no JF)"
              (seq (:unresolved cls)) (str "lib:" (str/join "," (:unresolved cls)))
              (:exception cls) (str "exc:" (:exception cls))
              (seq (:unknown cls)) (str "unk:" (str/join "," (:unknown cls)))
              :else "-")]
    (println (format "%-22s %-30s %-9s %-7s %-7s %s"
                     (:name r) (str (:shape r))
                     (or (:result r) "-")
                     (or (:anvil-duration-ms r) "-")
                     (or (:sh cls) "-")
                     sig))))
(println "\nresults edn:" (str workdir "/results.edn"))
