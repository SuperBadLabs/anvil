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
            [anvil.web.events-sse :as events-sse]
            [anvil.web.widgets :as widgets]
            [anvil.web.views.console-page :as console-page]
            [anvil.web.console-dl :as console-dl]
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

(defn- handler-queue-page [req]
  (html (queue-page/page req)))

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
   ["/jobs/:name"      {:get handler-job-detail     :name ::job-detail}]
   ["/jobs/:name/:number" {:get handler-build-detail :name ::build-detail}]
   ["/jobs/:name/:number/console" {:get handler-build-console :name ::build-console}]
   ["/queue"           {:get handler-queue-page     :name ::queue}]
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
   ;; anvil-native real-time event stream (TU1.3). SSE backed by
   ;; anvil.events.bus. Topic filter via ?topics= (see events-sse ns).
   ["/anvil/events"   {:get events-sse/handler}]
   ;; Live UI widget fragments (TU1.4). Each endpoint here returns
   ;; HTML hiccup that htmx-sse swaps in on the appropriate bus event.
   ["/anvil/widgets/dashboard-stats" {:get widgets/dashboard-stats}]
   jenkins-routes])

(defn make-handler
  "Build the reitit ring handler for anvil. Pure function of routes; can be
   called from tests without starting a server."
  []
  (-> (ring/ring-handler
       (ring/router routes)
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
      ring-params/wrap-params))
