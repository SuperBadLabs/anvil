(ns anvil.web.views.secrets-page
  "Secrets list page — admin-gated (T6.6 of the v0.3 board).

   At v0.3.0, anvil is single-tenant; admin gating is via
   `:anvil.secrets/admin-ips` in anvil.edn (a set of CIDR strings).
   When unset, only loopback is admitted. RBAC is a v0.4+ concern.

   The page NEVER renders cleartext values — only the per-credential
   masked preview from the credentials store."
  (:require [clojure.string :as str]
            [anvil.web.views.layout :as layout]
            [anvil.config :as config]
            [anvil.storage.credentials :as creds]))

(defn- request-ip [req]
  (or (some-> (get-in req [:headers "x-forwarded-for"])
              (str/split #",") first str/trim)
      (:remote-addr req)
      ""))

(defn- admin-allowed? [req]
  (let [ip (request-ip req)
        allow (or (:anvil.secrets/admin-ips (config/load-edn "anvil" {}))
                  #{"127.0.0.1" "::1"})]
    (contains? allow ip)))

(defn- forbidden [req]
  {:status 403
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str (layout/page
               {:title "Forbidden" :active :secrets}
               [:h2 "Forbidden"]
               [:p "Secrets admin is gated by source IP. Add "
                [:code (str (request-ip req))]
                " to "
                [:code ":anvil.secrets/admin-ips"]
                " in anvil.edn to admit it."]))})

(defn page [req]
  (if-not (admin-allowed? req)
    (forbidden req)
    (let [rows (try (creds/list-all) (catch Throwable _ []))]
      (layout/page
       {:title "Secrets" :active :secrets}
       [:h2 (str "Secrets (" (count rows) ")")]
       [:p.muted
        "Values are encrypted at rest with AES-256-GCM and never rendered. "
        "Use "
        [:code "anvil secrets add"]
        " from the CLI to create one (the value is read from stdin so "
        "it never appears in shell history)."]
       (if (empty? rows)
         [:p.muted "(no credentials stored)"]
         [:table
          [:thead [:tr [:th "ID"] [:th "Type"] [:th "Masked"] [:th "Description"]]]
          [:tbody
           (for [r rows]
             [:tr
              [:td [:code (:id r)]]
              [:td (name (or (:type r) :string))]
              [:td [:code (or (:masked r) "***")]]
              [:td (or (:description r) "")]])]])))))
