(ns anvil.web.routes
  "Top-level route table for anvil. TX2 brought up the daemon; TX6
   wired in the Jenkins REST shim under /jenkins/*; TX10/UI adds the
   admin dashboard at /, /jobs/*, /queue, /coverage."
  (:require [reitit.ring :as ring]
            [ring.middleware.params :as ring-params]
            [anvil.version :as v]
            [anvil.web.views.dashboard :as dashboard]
            [anvil.web.views.jobs-page :as jobs-page]
            [anvil.web.views.build-page :as build-page]
            [anvil.web.views.queue-page :as queue-page]
            [anvil.web.views.coverage-page :as coverage-page]
            [anvil.web.jenkins-api.handlers :as jenkins-h]
            [anvil.web.anvil-admin :as anvil-admin]
            [anvil.web.webhooks-github :as webhooks-github]
            [anvil.web.events-sse :as events-sse]
            [anvil.web.widgets :as widgets]
            [anvil.web.views.console-page :as console-page]
            [anvil.web.views.compare-page :as compare-page]
            [anvil.web.views.artifacts-page :as artifacts-page]
            [anvil.web.views.build-form :as build-form]
            [anvil.web.views.job-create-page :as job-create]
            [anvil.web.console-dl :as console-dl]
            [anvil.web.build-actions :as build-actions]
            [ring.middleware.cookies :as ring-cookies]
            [clojure.data.json :as json]))

(defn- html [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "X-Anvil-Version" v/version}
   :body body})

(defn- json-response [body & {:keys [status] :or {status 200}}]
  {:status status
   :headers {"Content-Type" "application/json"
             "X-Anvil-Version" v/version}
   :body (json/write-str body)})

(defn- handler-dashboard [req]
  (html (dashboard/page req)))

(defn- handler-jobs-list [req]
  (html (jobs-page/jobs-list req)))

(defn- handler-job-detail [req]
  (html (jobs-page/job-detail req)))

(defn- handler-build-detail [req]
  (html (build-page/build-detail req)))

(defn- handler-build-console [req]
  ;; ?download=raw|text routes to the file-download endpoint; bare
  ;; URL renders the HTML console view.
  (if (get-in req [:query-params "download"])
    (console-dl/handler req)
    (html (console-page/page req))))

(defn- handler-build-compare [req]
  (html (compare-page/page req)))

(defn- handler-build-artifacts [req]
  (html (artifacts-page/page req)))

(defn- handler-queue-page [req]
  (html (queue-page/page req)))

(defn- handler-executors-page [req]
  (html (queue-page/executors-page req)))

(defn- handler-coverage-page [req]
  (html (coverage-page/page req)))

(defn- handler-api-status [_req]
  (json-response {:product   "anvil"
                  :version   v/version
                  :tagline   v/tagline
                  :status    "alpha-skeleton"
                  :builds    0
                  :agents    0
                  :uptime-s  0}))

(defn- handler-health [_req]
  (json-response {:status "ok"
                  :ready  true}))

(def jenkins-routes
  "Jenkins REST API shim — mounted at /jenkins/* (TX6).
   Read-mostly: every endpoint jenkins-cli + GitHub Jenkins plugin
   commonly hit, plus build triggering. Administrative write endpoints
   are explicit 501 stubs."
  ["/jenkins"
   ["/api/json"                    {:get jenkins-h/root}]
   ["/crumbIssuer/api/json"        {:get jenkins-h/crumb}]
   ["/queue/api/json"              {:get jenkins-h/queue-api}]
   ["/queue/item/:id"              {:get jenkins-h/queue-item-by-id}]
   ["/queue/item/:id/"             {:get jenkins-h/queue-item-by-id}]
   ["/queue/item/:id/api/json"     {:get jenkins-h/queue-item-by-id}]
   ["/job/:name"
    ["/api/json"                   {:get jenkins-h/job-api}]
    ["/build"                      {:post jenkins-h/trigger-build}]
    ["/buildWithParameters"        {:post jenkins-h/trigger-build-with-parameters}]
    ["/:number"
     ["/api/json"                  {:get jenkins-h/build-api}]
     ["/consoleText"               {:get jenkins-h/console-text}]
     ["/logText/progressiveText"   {:get jenkins-h/log-progressive}]]]
   ;; 501 stubs — administrative endpoints we deliberately don't ship
   ["/createItem"                  {:post jenkins-h/create-item-stub}]
   ["/job/:name/config.xml"        {:get  jenkins-h/config-xml-stub
                                    :post jenkins-h/config-xml-stub}]
   ["/script"                      {:post jenkins-h/script-console-stub}]])

(def routes
  ["" {}
   ;; Admin UI
   ["/"                {:get handler-dashboard      :name ::root}]
   ["/status"          {:get handler-dashboard      :name ::status-html}]   ; legacy alias
   ["/jobs"            {:get handler-jobs-list      :name ::jobs}]
   ;; v0.2.2 (dogfood-driven patch): UI form for creating a job from
   ;; scratch. v0.2's empty-state CTA pointed at the `anvil import` CLI
   ;; or the JSON admin API, leaving the green-field case ("I want to
   ;; write a Jenkinsfile in the UI right now") without a path.
   ;; Literal /jobs/new is matched ahead of /jobs/:name by reitit.
   ["/jobs/new"
    {:get  (fn [req] (html (str (job-create/get-form req))))
     :post job-create/submit
     :name ::job-create}]
   ["/jobs/:name"      {:get handler-job-detail     :name ::job-detail}]
   ;; TU4.1+4.2+4.3+4.4+4.5: trigger UX. get-form returns a Hiccup
   ;; HTML string (no headers); submit returns a Ring map already.
   ;; Wrap GET in the html helper so wrap-cookies sees a real response.
   ["/jobs/:name/build-form"
    {:get  (fn [req] (html (build-form/get-form req)))
     :post build-form/submit
     :name ::build-form}]
   ["/jobs/:name/:number" {:get handler-build-detail :name ::build-detail}]
   ["/jobs/:name/:number/console"   {:get handler-build-console :name ::build-console}]
   ["/jobs/:name/:number/compare"   {:get handler-build-compare :name ::build-compare}]
   ["/jobs/:name/:number/artifacts" {:get handler-build-artifacts :name ::build-artifacts}]
   ;; TU3.3: trigger a retry (POST). Same params as the original build.
   ["/jobs/:name/:number/retry"     {:post build-actions/retry :name ::build-retry}]
   ;; TU3.5: serve a single artifact file. Path segment after
   ;; /artifact/ is captured wholesale via reitit's :path catch-all.
   ["/jobs/:name/:number/artifact/*path" {:get build-actions/serve-artifact :name ::build-artifact}]
   ["/queue"           {:get handler-queue-page     :name ::queue}]
   ["/executors"       {:get handler-executors-page :name ::executors}]
   ;; TU5.3: kill / cancel actions
   ["/jobs/:name/:number/kill"   {:post build-actions/kill-build :name ::build-kill}]
   ["/queue/cancel/:queue-id"    {:post build-actions/cancel-queued :name ::queue-cancel}]
   ["/coverage"        {:get handler-coverage-page  :name ::coverage}]
   ;; anvil-internal JSON
   ["/api"
    ["/status" {:get handler-api-status :name ::api-status}]
    ["/health" {:get handler-health :name ::api-health}]]
   ;; anvil-native admin (distinct from /jenkins/createItem which is 501).
   ;; Used by the CI integration fixture + future anvil-native tooling.
   ["/anvil/admin"
    ["/jobs"          {:get  anvil-admin/list-jobs
                       :post anvil-admin/register-job}]
    ["/jobs/:name"    {:delete anvil-admin/delete-job}]]
   ;; T3.3 — GitHub webhook receiver. Gated on :pr-checks feature
   ;; flag at the handler level (returns 503 when off) rather than
   ;; at the route layer so the URL remains stable for github to hit.
   ["/anvil/webhooks/github" {:post webhooks-github/handler}]
   ;; anvil-native real-time event stream (TU1.3). SSE backed by
   ;; anvil.events.bus. Topic filter via ?topics= (see events-sse ns).
   ["/anvil/events"   {:get events-sse/handler}]
   ;; Live UI widget fragments (TU1.4). Each endpoint here returns
   ;; HTML hiccup that htmx-sse swaps in on the appropriate bus event.
   ["/anvil/widgets/dashboard-stats"     {:get widgets/dashboard-stats}]
   ["/anvil/widgets/job-builds/:name"    {:get widgets/job-builds}]
   ["/anvil/widgets/queue"               {:get widgets/queue}]
   ["/anvil/widgets/executors"           {:get widgets/executors}]
   jenkins-routes])

(defn make-handler
  "Build the reitit ring handler for anvil. Pure function of routes; can be
   called from tests without starting a server."
  []
  (-> (ring/ring-handler
       ;; :conflicts nil disables reitit's strict overlap check —
       ;; /jobs/:name/build-form vs /jobs/:name/:number is a real
       ;; literal-vs-variable overlap and reitit's matcher already
       ;; picks the literal correctly at request time.
       (ring/router routes {:conflicts nil})
       ;; Default chain: static /public/* assets (vendored htmx etc. from
       ;; resources/public/), then 404. Resource handler is anvil-internal:
       ;; never serves /etc/passwd-style paths because it's rooted at the
       ;; classpath /public prefix.
       (ring/routes
        (ring/create-resource-handler
         {:path "/public/"
          :root "public"})
        (ring/create-default-handler
         {:not-found (fn [_]
                       {:status 404
                        :headers {"Content-Type" "text/plain"
                                  "X-Anvil-Version" v/version}
                        :body "anvil: 404 — route not found"})})))
      ;; Form + query param parsing — Jenkins' buildWithParameters
      ;; needs both.
      ring-params/wrap-params
      ;; TU4.3: read + serialize Set-Cookie for the recent-values
      ;; memory on the build-form page. Idempotent for everything
      ;; that doesn't return :cookies in its response.
      ring-cookies/wrap-cookies))
