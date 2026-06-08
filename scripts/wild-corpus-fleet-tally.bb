#!/usr/bin/env bb
;; Snapshot tally — sum jars + bytes across ALL build dirs per job
;; per host, regardless of still-running.
(require '[babashka.process :as p] '[clojure.string :as str])

(def shards
  {"HeMan"  ["wild-apache-activemq" "wild-apache-camel"
             "wild-eclipse-jdt-core" "wild-eclipse-jkube"]
   "mario"  ["wild-apache-camel-quarkus" "wild-apache-cxf"
             "wild-apache-maven" "wild-apache-zookeeper"
             "wild-eclipse-epsilon"]
   "luigi"  ["wild-apache-cassandra" "wild-apache-hbase"
             "wild-apache-streampipes" "wild-hibernate-orm"
             "wild-hibernate-search"]})

(defn- jar-stats [host job]
  ;; Find LATEST build dir per job, count jars + bytes there.
  (let [base "/tmp/anvil-v041/target/anvil-builds"
        cmd (format "ls -d %s/%s/[0-9]* 2>/dev/null | sort -V | tail -1 | xargs -r -I{} find {} -name '*.jar' -type f -printf '%%s\\n' 2>/dev/null"
                    base job)
        out (if (= host "HeMan")
              (:out (p/shell {:out :string :err :string :continue true} "bash" "-c" cmd))
              (:out (p/shell {:out :string :err :string :continue true}
                             "ssh" host "bash" "-c" (str "\"" cmd "\""))))
        sizes (->> (str/split-lines (str out))
                   (keep #(when-not (str/blank? %)
                            (try (Long/parseLong (str/trim %)) (catch Exception _ nil)))))]
    {:job job :host host :jars (count sizes) :bytes (reduce + 0 sizes)}))

(defn- still-running? [host]
  (let [cmd "docker ps --filter ancestor=maven:3.9-eclipse-temurin-17 --filter ancestor=maven:3.9-eclipse-temurin-21 --format '{{.Names}}|{{.RunningFor}}'"
        out (if (= host "HeMan")
              (:out (p/shell {:out :string :err :string :continue true} "bash" "-c" cmd))
              (:out (p/shell {:out :string :err :string :continue true}
                             "ssh" host "bash" "-c" (str "\"" cmd "\""))))]
    (->> (str/split-lines (str out)) (remove str/blank?))))

(printf "v0.4.1-T6 FLEET — snapshot at %s\n" (str (java.time.LocalTime/now)))
(println "===========================================================")
(printf "%-30s %-7s %6s %14s   %s\n" "job" "host" "jars" "bytes" "mb")
(println (apply str (repeat 80 "-")))
(let [stats (vec (for [[host jobs] shards, j jobs] (jar-stats host j)))]
  (doseq [s (sort-by (juxt :host :job) stats)]
    (printf "%-30s %-7s %6d %14d   %7.2f\n"
            (:job s) (:host s) (:jars s) (:bytes s)
            (/ (:bytes s) 1024.0 1024.0)))
  (println)
  (println "By host:")
  (doseq [[host rs] (group-by :host stats)]
    (printf "  %-7s %d jobs   %6d jars   %7.2f MB\n"
            host (count rs) (reduce + (map :jars rs))
            (/ (reduce + (map :bytes rs)) 1024.0 1024.0)))
  (println)
  (printf "TOTAL: %d jobs   %d jars   %.2f MB (%.3f GB)\n"
          (count stats) (reduce + (map :jars stats))
          (/ (reduce + (map :bytes stats)) 1024.0 1024.0)
          (/ (reduce + (map :bytes stats)) 1024.0 1024.0 1024.0)))

(println)
(println "Still running:")
(doseq [host ["HeMan" "mario" "luigi"]]
  (let [rs (still-running? host)]
    (when (seq rs)
      (doseq [r rs] (printf "  %-7s %s\n" host r)))))
