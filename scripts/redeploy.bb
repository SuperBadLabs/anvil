#!/usr/bin/env bb
;; anvil-redeploy.bb — pull master, rebuild, hot-swap into systemd anvil.
;;
;; Designed to be invoked from a systemd timer every few minutes. Idempotent:
;; if there are no new commits, exits cleanly without touching the running
;; service.
;;
;; Behavior:
;;   - git fetch origin master
;;   - if origin/master == HEAD: log "no change" and exit 0
;;   - else: ff-merge, lein uberjar, back up current jar, install new jar,
;;     restart anvil.service, health-check on http://127.0.0.1:8765/
;;     - on health-check failure: roll back to backed-up jar, restart, exit 1
;;     - on lein uberjar failure: keep current jar untouched, exit 1
;;
;; Assumes:
;;   - This script runs as a user with passwordless sudo
;;   - /var/lib/anvil/source is a fresh clone of anvil
;;   - /opt/anvil/anvil.jar is the installed uberjar (target of systemd ExecStart)
;;   - anvil.service is the systemd unit name
;;   - chengis-core 0.1.0 is already in ~/.m2 (otherwise add the install prelude)
;;
;; v0.2.2 — added 2026-06-03 alongside /jobs/new patch.

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[babashka.http-client :as http]
         '[clojure.string :as str])

(def REPO     "/var/lib/anvil/source")
(def TARGET   "/opt/anvil/anvil.jar")
(def BACKUP   "/opt/anvil/anvil.jar.previous")
(def SERVICE  "anvil.service")
(def HEALTH   "http://127.0.0.1:8765/")
(def HEALTH-TIMEOUT-SECS 30)

(defn- ts []
  (str/replace (str (java.time.Instant/now)) #"\..*Z$" "Z"))

(defn- log [& args]
  (println (str "[" (ts) "] anvil-redeploy: " (str/join " " args))))

(defn- run
  "Run a shell command in REPO. Returns {:exit, :out, :err}.
   Logs the failure to stderr but doesn't throw."
  [& args]
  (let [r (apply shell {:dir REPO
                         :out :string
                         :err :string
                         :continue true}
                  args)]
    {:exit (:exit r)
     :out (str/trim (or (:out r) ""))
     :err (str/trim (or (:err r) ""))}))

(defn- ok? [r] (zero? (:exit r)))

(defn- rev [ref]
  (:out (run "git" "rev-parse" ref)))

(defn- find-uberjar []
  (->> (fs/glob (str REPO "/target/uberjar") "anvil-*-standalone.jar")
       (sort-by fs/last-modified-time)
       last
       str))

(defn- health-check! []
  (loop [i 0]
    (cond
      (>= i HEALTH-TIMEOUT-SECS)
      false

      (try
        (= 200 (:status (http/get HEALTH {:throw false :timeout 3000})))
        (catch Exception _ false))
      true

      :else
      (do (Thread/sleep 1000) (recur (inc i))))))

(defn- roll-back! []
  (log "✗ Health check failed — rolling back to previous jar")
  (run "sudo" "cp" BACKUP TARGET)
  (run "sudo" "systemctl" "restart" SERVICE)
  (if (health-check!)
    (log "↩  Rollback restored service to previous jar")
    (log "‼  Rollback ALSO failed — anvil is down; manual intervention needed")))

(defn- redeploy! [from-sha to-sha]
  (log "Building uberjar...")
  (let [build (run "lein" "uberjar")]
    (if-not (ok? build)
      (do
        (log "✗ lein uberjar failed (keeping current jar)")
        (log (subs (:err build) 0 (min 500 (count (:err build)))))
        (System/exit 1))
      (let [jar (find-uberjar)]
        (if-not (and jar (fs/exists? jar))
          (do
            (log "✗ uberjar not found at target/uberjar/anvil-*-standalone.jar")
            (System/exit 1))
          (do
            (log "Backing up current jar to" BACKUP)
            (run "sudo" "cp" TARGET BACKUP)
            (log "Installing" jar "→" TARGET)
            (run "sudo" "cp" jar TARGET)
            (log "Restarting" SERVICE)
            (run "sudo" "systemctl" "restart" SERVICE)
            (if (health-check!)
              (log "✓ Redeploy succeeded —" from-sha "→" to-sha)
              (do
                (roll-back!)
                (System/exit 1)))))))))

(defn -main [& _]
  (when-not (fs/exists? REPO)
    (log "✗" REPO "does not exist (clone anvil there first)")
    (System/exit 1))

  ;; Refuse to redeploy if there are local uncommitted changes — those
  ;; only happen via manual edits, and silently overwriting them with
  ;; a fast-forward would be surprising.
  (let [status (run "git" "status" "--porcelain")]
    (when-not (str/blank? (:out status))
      (log "✗ deploy checkout" REPO "has local changes; refusing to redeploy")
      (log "    (manual edits should happen in your dev clone, not here)")
      (System/exit 1)))

  (let [before (rev "HEAD")]
    (run "git" "fetch" "--quiet" "origin" "master")
    (let [after (rev "origin/master")]
      (cond
        (str/blank? after)
        (do (log "✗ couldn't read origin/master") (System/exit 1))

        (= before after)
        (log "no change · head=" (subs before 0 8))

        :else
        (do
          (log "new commits ·" (subs before 0 8) "→" (subs after 0 8))
          (run "git" "merge" "--ff-only" "origin/master")
          (redeploy! before after))))))

(apply -main *command-line-args*)
