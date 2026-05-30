(ns anvil.web.build-actions
  "POST handlers for the per-build page actions (TU3.3 retry) and the
   artifact-serving GET (TU3.5).

   Kept apart from the views so the views stay pure render."
  (:require [clojure.java.io :as io]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.runner :as runner]
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
;; TU5.3 — kill a running build
;; ---------------------------------------------------------------------------

(defn kill-build [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))
        htmx? (= "true" (get-in req [:headers "hx-request"]))
        redirect-to (str "/jobs/" job-name "/" n)]
    (cond
      (nil? b)
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "no such build: " job-name "#" n)}

      (not (:building? b))
      {:status 409
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "build is not currently running"}

      :else
      (let [interrupted? (boolean (runner/kill! job-name n))]
        (if htmx?
          {:status 200
           :headers {"HX-Redirect" redirect-to
                     "X-Anvil-Killed" (str interrupted?)}
           :body ""}
          {:status 303
           :headers {"Location" redirect-to
                     "X-Anvil-Killed" (str interrupted?)}
           :body (str "kill signal sent (interrupted=" interrupted? ")")})))))

;; ---------------------------------------------------------------------------
;; TU5.3 — cancel a queued (not-yet-running) item
;; ---------------------------------------------------------------------------

(defn cancel-queued [req]
  (let [qid (try (Long/parseLong (str (get-in req [:path-params :queue-id])))
                 (catch Exception _ nil))
        htmx? (= "true" (get-in req [:headers "hx-request"]))
        cancelled? (when qid (queue/cancel! qid))]
    (if cancelled?
      (if htmx?
        {:status 200 :headers {"HX-Redirect" "/queue"} :body ""}
        {:status 303 :headers {"Location" "/queue"} :body "cancelled"})
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "no such queue item: " qid)})))

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
