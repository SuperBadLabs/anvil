(ns anvil.web.views.status
  "Status / landing page — the first thing a user sees after `lein run`.

   Deliberately minimal. Real anvil UI views (build list, log viewer,
   trigger form) land in later tranches."
  (:require [hiccup2.core :as h]
            [anvil.version :as v]))

(defn page
  "Return an HTML string for the status page."
  [{:keys [build-count agent-count]}]
  (str
   (h/html
    [:html
     [:head
      [:title (str "anvil " v/version)]
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:style "
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
               max-width: 720px; margin: 4em auto; padding: 0 1em; color: #222; }
        h1 { font-size: 2.2em; margin-bottom: 0.2em; }
        .tagline { color: #666; font-size: 1.05em; margin-top: 0; margin-bottom: 2em; }
        .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1em; margin: 2em 0; }
        .card { background: #f7f7f8; border-radius: 8px; padding: 1.2em 1.4em; }
        .card-label { color: #888; font-size: 0.85em; text-transform: uppercase; letter-spacing: 0.05em; }
        .card-value { font-size: 2em; font-weight: 600; margin-top: 0.2em; }
        .links a { display: inline-block; margin-right: 1.4em; color: #1f6feb; text-decoration: none; }
        .links a:hover { text-decoration: underline; }
        footer { margin-top: 3em; color: #999; font-size: 0.85em; border-top: 1px solid #eee; padding-top: 1em; }
        code { background: #f0f0f1; padding: 0.1em 0.4em; border-radius: 3px; font-size: 0.9em; }"]]
     [:body
      [:h1 "anvil"]
      [:p.tagline v/tagline]

      [:div.grid
       [:div.card
        [:div.card-label "Builds"]
        [:div.card-value (str (or build-count 0))]]
       [:div.card
        [:div.card-label "Agents"]
        [:div.card-value (str (or agent-count 0))]]]

      [:h3 "What's running?"]
      [:p "This is a pre-alpha skeleton — the daemon is alive but no parsing,
           execution, or UI is wired yet. See the program tranches in "
       [:code "docs/jenkins-compat/execution-board.md"]
       " for what lands when."]

      [:h3 "Next steps for users (when this is real)"]
      [:ul
       [:li "Run "
        [:code "anvil import jenkinsfile <path>"]
        " to convert your Jenkinsfile to anvil's native shape."]
       [:li "Run "
        [:code "anvil build <pipeline>"]
        " to execute it."]
       [:li "Point your existing "
        [:code "jenkins-cli.jar"]
        " at "
        [:code "http://localhost:8080/jenkins/"]
        " for compat-mode access."]]

      [:div.links
       [:a {:href "/api/status"} "JSON status"]
       [:a {:href "/api/health"} "Health probe"]]

      [:footer
       (v/version-string)
       " · powered by chengis-core"
       " · "
       [:a {:href "https://github.com/" :style "color:#999;"} "source"]]]])))
