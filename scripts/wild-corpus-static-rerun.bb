#!/usr/bin/env bb
;; v0.4.1 T6 — Static-classification rerun harness.
;;
;; Without SCM, anvil's runner runs the translator + dispatcher but
;; can't materially execute shell commands. The classification we get
;; here is the TRANSLATOR + DISPATCHER verdict on the same wild-corpus
;; Jenkinsfile sources as v0.3.3 / v0.4.0 — a fair proof that T3 (AI)
;; and T4 (provenance) didn't regress the static path.
;;
;; For a real-artifact comparison, the operator should layer SCM
;; configs onto these jobs and rerun (~90 min wall clock for hbase).
(require '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def anvil-url "http://localhost:8766")
(def corpus-root "/tmp/anvil-v041/corpus")

(defn- post-json! [path body]
  (http/post (str anvil-url path)
             {:body (json/encode body)
              :headers {"Content-Type" "application/json"}
              :throw false}))

(defn- register! [name]
  (let [jf (slurp (fs/file corpus-root name "Jenkinsfile"))
        resp (post-json! "/anvil/admin/jobs"
                         {:name name :jenkinsfile_source jf})]
    [(:status resp) (count jf)]))

(defn- trigger! [name]
  (:status (http/post (str anvil-url "/jenkins/job/" name "/build")
                      {:throw false})))

(defn- poll-build [name max-s]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 max-s))]
    (loop []
      (let [r (http/get (str anvil-url "/jenkins/job/" name "/1/api/json")
                        {:throw false})]
        (cond
          (and (= 200 (:status r))
               (let [b (json/decode (:body r) true)]
                 (false? (:building b))))
          (json/decode (:body r) true)

          (> (System/currentTimeMillis) deadline)
          {:result "TIMEOUT"}

          :else
          (do (Thread/sleep 500) (recur)))))))

(defn- extract-rule [name]
  (let [html (:body (http/get (str anvil-url "/jobs/" name "/1")
                              {:throw false}))]
    (or (second (re-find #"<strong>([^—]+?)\s*</strong>" (str html)))
        "?")))

(def jobs (->> (fs/list-dir corpus-root) (map fs/file-name) sort))

(println "v0.4.1-T6 static-classification rerun — anvil v0.4.1-rc (commit 11d1a70)")
(println "----------------------------------------------------------------------------")
(printf "%-35s | %5s | %4s | %s\n" "job" "bytes" "ms" "verdict")
(println (apply str (repeat 80 "-")))

(let [results
      (vec
       (for [name jobs]
         (let [[reg-s b] (register! name)
               trig-s (trigger! name)
               b1 (poll-build name 30)
               result (:result b1)
               dur (:duration b1)]
           (printf "%-35s | %5d | %4s | %s\n" name b (or dur "-") (or result "—"))
           (flush)
           {:name name :bytes b :result result :duration dur})))]
  (println)
  (println "Tally:")
  (doseq [[k v] (->> results (group-by :result) (sort-by key))]
    (printf "  %-12s %d\n" (str k ":") (count v)))
  (spit "/tmp/anvil-v041/v041-results.edn" (pr-str results)))
