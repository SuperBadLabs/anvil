(ns anvil.web.server
  "HTTP server lifecycle. http-kit, started in dev via `lein run`, started
   in prod via the uberjar's main."
  (:require [org.httpkit.server :as http]
            [taoensso.timbre :as log]
            [anvil.web.routes :as routes]
            [anvil.version :as v]))

(defonce ^:private server-state (atom nil))

(defn start!
  "Start the HTTP server. Idempotent; returns the running server."
  ([] (start! {}))
  ([{:keys [port host] :or {port 8080 host "0.0.0.0"}}]
   (when @server-state
     (log/warn "anvil server already running; refusing to restart"))
   (when-not @server-state
     (let [handler (routes/make-handler)
           server (http/run-server handler {:port port :ip host :legacy-return-value? false})]
       (reset! server-state server)
       (log/info (str (v/version-string)
                      " listening on http://" host ":" port))
       server))))

(defn stop!
  "Stop the HTTP server if running."
  ([] (stop! 100))
  ([timeout-ms]
   (when-let [server @server-state]
     (http/server-stop! server {:timeout timeout-ms})
     (reset! server-state nil)
     (log/info "anvil server stopped"))))

(defn server-info
  "Return the running server's port + status. nil if not running."
  []
  (when-let [server @server-state]
    {:port (http/server-port server)
     :status (http/server-status server)}))
