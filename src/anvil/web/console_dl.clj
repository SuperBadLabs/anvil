(ns anvil.web.console-dl
  "Download endpoints for build consoles (TU2.6).

   Two formats:
     ?download=raw   — bytes of the on-disk log as-is (ANSI included)
     ?download=text  — same, with ANSI escapes stripped

   Both ship Content-Disposition: attachment with a sensible filename
   so 'right-click → save link as' works in browsers. Returns 404 if
   no such build / no log file.

   These endpoints DO NOT touch jobs/console-log-for (which renders the
   in-memory effects vector). They go straight to the on-disk file —
   so for streaming builds, this is the full content, not the
   :effects-rendered prefix."
  (:require [clojure.java.io :as io]
            [anvil.web.ansi :as ansi]
            [anvil.web.jenkins-api.jobs :as jobs]))

(defn handler [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))
        log-path (:log-path b)
        f (when log-path (io/file log-path))
        fmt (or (get-in req [:query-params "download"]) "raw")]
    (cond
      (or (nil? b) (nil? f) (not (.exists ^java.io.File f)))
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "no console log for " job-name "#" n)}

      (= fmt "text")
      (let [raw (slurp f)
            txt (ansi/strip-ansi raw)]
        {:status 200
         :headers {"Content-Type" "text/plain; charset=utf-8"
                   "Content-Disposition"
                   (str "attachment; filename=\"" job-name "-" n ".console.txt\"")}
         :body txt})

      :else
      {:status 200
       :headers {"Content-Type" "text/plain; charset=utf-8"
                 "Content-Disposition"
                 (str "attachment; filename=\"" job-name "-" n ".console.log\"")}
       :body (slurp f)})))
