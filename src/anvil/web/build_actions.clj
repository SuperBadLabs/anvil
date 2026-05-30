(ns anvil.web.build-actions
  "POST handlers for the per-build page actions (TU3.3 retry) and the
   artifact-serving GET (TU3.5).

   Kept apart from the views so the views stay pure render."
  (:require [clojure.java.io :as io]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.build-summary :as summary]))

;; ---------------------------------------------------------------------------
;; TU3.3 — retry
;; ---------------------------------------------------------------------------

(defn retry [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))]
    (cond
      (nil? b)
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "no such build: " job-name "#" n)}

      :else
      (let [item (queue/enqueue! job-name {:parameters (:parameters b)})
            qid (:queue-id item)]
        ;; Whether the request is htmx (from the Retry button) or a
        ;; vanilla POST, redirect to the job page so the user sees the
        ;; new build appear in the live builds-table. htmx prefers
        ;; HX-Redirect; non-htmx gets a normal 303 See Other.
        (let [target (str "/jobs/" job-name)
              htmx? (= "true" (get-in req [:headers "hx-request"]))]
          (if htmx?
            {:status 200
             :headers {"HX-Redirect" target
                       "X-Anvil-Queue-Id" (str qid)}
             :body ""}
            {:status 303
             :headers {"Location" target
                       "X-Anvil-Queue-Id" (str qid)}
             :body (str "queued as #" qid "; redirecting to " target)}))))))

;; ---------------------------------------------------------------------------
;; TU3.5 — artifact download
;; ---------------------------------------------------------------------------

(defn serve-artifact [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        ;; reitit catches the trailing path under :path
        rel-path (get-in req [:path-params :path])
        b (when n (jobs/find-build job-name n))
        f (when (and b rel-path) (summary/artifact-file b rel-path))]
    (cond
      (nil? b)
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "no such build: " job-name "#" n)}

      (nil? f)
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "no such artifact: " rel-path)}

      :else
      {:status 200
       :headers {"Content-Type" (summary/guess-content-type (.getName f))
                 "Content-Length" (str (.length f))
                 "Content-Disposition" (str "inline; filename=\"" (.getName f) "\"")}
       :body (io/input-stream f)})))
