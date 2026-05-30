(ns anvil.web.views.layout
  "Shared layout + CSS for every admin-UI page. Keep the look minimal —
   one stylesheet, no JS, no client-side state. The dashboard exists
   to help an operator answer 'is the build pipeline healthy?' at a
   glance, not to compete with Jenkins's Blue Ocean."
  (:require [hiccup2.core :as h]
            [anvil.version :as v]))

(def ^:private css "
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         max-width: 1080px; margin: 2em auto; padding: 0 1em; color: #222; }
  h1, h2, h3 { color: #111; }
  h1 { font-size: 2em; margin: 0 0 0.2em; }
  .tagline { color: #666; font-size: 1em; margin: 0 0 1.5em; }
  nav { display: flex; gap: 1em; margin-bottom: 1.5em; border-bottom: 1px solid #eee; padding-bottom: 0.5em; }
  nav a { color: #1f6feb; text-decoration: none; padding: 0.3em 0; }
  nav a.active { font-weight: 600; border-bottom: 2px solid #1f6feb; }
  nav a:hover { text-decoration: underline; }

  /* Status cards row */
  .stat-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.8em; margin: 1.2em 0; }
  .stat { background: #f7f7f8; border-radius: 8px; padding: 1em 1.1em; }
  .stat-label { color: #888; font-size: 0.78em; text-transform: uppercase; letter-spacing: 0.05em; }
  .stat-value { font-size: 1.7em; font-weight: 600; margin-top: 0.2em; }
  .stat-value.green  { color: #1a7f37; }
  .stat-value.red    { color: #cf222e; }
  .stat-value.yellow { color: #9a6700; }
  .stat-value.muted  { color: #888; }

  /* Tables */
  table { width: 100%; border-collapse: collapse; margin-top: 1em; }
  th, td { text-align: left; padding: 0.55em 0.7em; border-bottom: 1px solid #eee; vertical-align: top; }
  th { color: #666; font-size: 0.85em; font-weight: 500; text-transform: uppercase; letter-spacing: 0.03em; }
  tr:hover { background: #fafbfc; }
  td a { color: #1f6feb; text-decoration: none; }
  td a:hover { text-decoration: underline; }
  td.muted { color: #999; }

  /* Build-result badges */
  .badge { display: inline-block; padding: 0.18em 0.55em; border-radius: 4px; font-size: 0.82em; font-weight: 600; }
  .badge.blue   { background: #ddf4ff; color: #0969da; }
  .badge.red    { background: #ffebe9; color: #cf222e; }
  .badge.yellow { background: #fff8c5; color: #9a6700; }
  .badge.gray   { background: #f0f0f1; color: #666; }
  .badge.anim   { background: #ddf4ff; color: #0969da; animation: pulse 1.5s infinite; }
  @keyframes pulse { 0%,100% { opacity: 1 } 50% { opacity: 0.55 } }

  /* Code blocks */
  code { background: #f0f0f1; padding: 0.12em 0.4em; border-radius: 3px; font-size: 0.9em; }
  pre.console { background: #0d1117; color: #c9d1d9; padding: 1em 1.2em; border-radius: 8px;
                font-family: ui-monospace, SFMono-Regular, monospace; font-size: 0.85em;
                line-height: 1.4em; max-height: 60vh; overflow: auto; white-space: pre-wrap; word-wrap: break-word; }

  /* Footer */
  footer { margin-top: 3em; color: #999; font-size: 0.85em; border-top: 1px solid #eee; padding-top: 1em; }
")

(defn- color->badge
  "Map a Jenkins color (or anvil keyword) to a CSS class for the build-result badge."
  [c]
  (let [c (some-> c name)]
    (cond
      (= c "blue")        "blue"
      (= c "blue_anime")  "anim"
      (= c "red")         "red"
      (= c "yellow")      "yellow"
      (= c "aborted")     "gray"
      :else                "gray")))

(defn page
  "Render the full HTML for an admin-UI page. `body-hiccup` is the
   page-specific contents; `active` marks the current nav entry."
  [{:keys [title active]} & body-hiccup]
  (str
   (h/html
    [:html
     [:head
      [:title (str (or title "anvil") " · anvil")]
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:style (h/raw css)]]
     [:body
      [:h1 "anvil"]
      [:p.tagline "Drop-in Jenkins replacement, powered by chengis-core."]
      [:nav
       [:a {:href "/" :class (when (= active :dashboard) "active")} "Dashboard"]
       [:a {:href "/jobs" :class (when (= active :jobs) "active")} "Jobs"]
       [:a {:href "/queue" :class (when (= active :queue) "active")} "Queue"]
       [:a {:href "/coverage" :class (when (= active :coverage) "active")} "Coverage"]]
      body-hiccup
      [:footer
       (v/version-string)
       " · powered by chengis-core"
       " · "
       [:a {:href "/jenkins/api/json" :style "color:#999;"} "Jenkins REST"]]]])))

(defn badge-for-color [color]
  [:span {:class (str "badge " (color->badge color))}
   (some-> color name)])
