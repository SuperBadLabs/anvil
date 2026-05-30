(ns anvil.web.views.layout
  "Shared layout + CSS for every admin-UI page. Keep the look minimal —
   one stylesheet, no JS, no client-side state. The dashboard exists
   to help an operator answer 'is the build pipeline healthy?' at a
   glance, not to compete with Jenkins's Blue Ocean."
  (:require [hiccup2.core :as h]
            [anvil.version :as v]))

(def ^:private css "
  /* ── Design tokens (AU8: one stylesheet; AU7: dark via prefers-color-scheme) ─
     Every color / spacing / type value below references a token. Widgets
     in TU2+ should consume tokens, not hard-coded hexes. */
  :root {
    /* Surfaces */
    --bg:           #ffffff;
    --bg-elevated: #f7f7f8;
    --bg-hover:    #fafbfc;
    --border:      #eee;
    --border-strong: #ddd;

    /* Text */
    --fg:          #222;
    --fg-strong:   #111;
    --fg-muted:    #666;
    --fg-faint:    #888;
    --fg-ghost:    #999;

    /* Accent + link */
    --accent:      #1f6feb;
    --accent-bg:   #ddf4ff;

    /* Semantic */
    --ok:          #1a7f37;
    --warn:        #9a6700;
    --warn-bg:     #fff8c5;
    --err:         #cf222e;
    --err-bg:      #ffebe9;
    --info:        #0969da;
    --info-bg:     #ddf4ff;

    /* Console */
    --console-bg:  #0d1117;
    --console-fg:  #c9d1d9;

    /* Type */
    --font-sans:   -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    --font-mono:   ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;

    /* Spacing scale (rems-ish, but expressed in em for component-local sizing) */
    --space-1:     0.25em;
    --space-2:     0.5em;
    --space-3:     0.75em;
    --space-4:     1em;
    --space-6:     1.5em;
    --space-8:     2em;

    /* Radii */
    --radius-1:    4px;
    --radius-2:    8px;

    /* Layout */
    --max-width:   1080px;
  }

  @media (prefers-color-scheme: dark) {
    :root {
      --bg:           #0d1117;
      --bg-elevated:  #161b22;
      --bg-hover:     #1f242c;
      --border:       #30363d;
      --border-strong: #444c56;
      --fg:           #c9d1d9;
      --fg-strong:    #f0f6fc;
      --fg-muted:     #8b949e;
      --fg-faint:     #768390;
      --fg-ghost:     #6e7681;
      --accent:       #58a6ff;
      --accent-bg:    #1f3b66;
      --ok:           #3fb950;
      --warn:         #d29922;
      --warn-bg:      #422d09;
      --err:          #f85149;
      --err-bg:       #4c1f22;
      --info:         #58a6ff;
      --info-bg:      #1f3b66;
    }
  }

  /* ── Base ─────────────────────────────────────────────────────────── */
  body { font-family: var(--font-sans);
         max-width: var(--max-width); margin: var(--space-8) auto; padding: 0 var(--space-4);
         background: var(--bg); color: var(--fg); }
  h1, h2, h3 { color: var(--fg-strong); }
  h1 { font-size: 2em; margin: 0 0 0.2em; }
  .tagline { color: var(--fg-muted); font-size: 1em; margin: 0 0 var(--space-6); }
  nav { display: flex; gap: var(--space-4); margin-bottom: var(--space-6);
        border-bottom: 1px solid var(--border); padding-bottom: var(--space-2); }
  nav a { color: var(--accent); text-decoration: none; padding: var(--space-1) 0; }
  nav a.active { font-weight: 600; border-bottom: 2px solid var(--accent); }
  nav a:hover { text-decoration: underline; }

  /* ── Status cards row ─────────────────────────────────────────────── */
  .stat-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
              gap: 0.8em; margin: 1.2em 0; }
  .stat { background: var(--bg-elevated); border-radius: var(--radius-2); padding: var(--space-4) 1.1em; }
  .stat-label { color: var(--fg-faint); font-size: 0.78em; text-transform: uppercase; letter-spacing: 0.05em; }
  .stat-value { font-size: 1.7em; font-weight: 600; margin-top: 0.2em; }
  .stat-value.green  { color: var(--ok); }
  .stat-value.red    { color: var(--err); }
  .stat-value.yellow { color: var(--warn); }
  .stat-value.muted  { color: var(--fg-faint); }

  /* ── Tables ───────────────────────────────────────────────────────── */
  table { width: 100%; border-collapse: collapse; margin-top: var(--space-4); }
  th, td { text-align: left; padding: 0.55em 0.7em; border-bottom: 1px solid var(--border); vertical-align: top; }
  th { color: var(--fg-muted); font-size: 0.85em; font-weight: 500; text-transform: uppercase; letter-spacing: 0.03em; }
  tr:hover { background: var(--bg-hover); }
  td a { color: var(--accent); text-decoration: none; }
  td a:hover { text-decoration: underline; }
  td.muted { color: var(--fg-ghost); }

  /* ── Build-result badges ──────────────────────────────────────────── */
  .badge { display: inline-block; padding: 0.18em 0.55em; border-radius: var(--radius-1); font-size: 0.82em; font-weight: 600; }
  .badge.blue   { background: var(--info-bg); color: var(--info); }
  .badge.red    { background: var(--err-bg);  color: var(--err); }
  .badge.yellow { background: var(--warn-bg); color: var(--warn); }
  .badge.gray   { background: var(--bg-elevated); color: var(--fg-muted); }
  .badge.anim   { background: var(--info-bg); color: var(--info); animation: pulse 1.5s infinite; }
  @keyframes pulse { 0%,100% { opacity: 1 } 50% { opacity: 0.55 } }

  /* ── Code blocks ──────────────────────────────────────────────────── */
  code { background: var(--bg-elevated); padding: 0.12em 0.4em; border-radius: 3px; font-size: 0.9em; font-family: var(--font-mono); }
  pre.console { background: var(--console-bg); color: var(--console-fg);
                padding: var(--space-4) 1.2em; border-radius: var(--radius-2);
                font-family: var(--font-mono); font-size: 0.85em;
                line-height: 1.4em; max-height: 60vh; overflow: auto;
                white-space: pre-wrap; word-wrap: break-word; }

  /* ── Footer ───────────────────────────────────────────────────────── */
  footer { margin-top: 3em; color: var(--fg-ghost); font-size: 0.85em;
           border-top: 1px solid var(--border); padding-top: var(--space-4); }
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
      [:meta {:name "color-scheme" :content "light dark"}]
      [:style (h/raw css)]
      ;; AU1+AU2+AU9: vendored htmx + SSE extension. Defer so they
      ;; don't block first paint — the existing pages still render
      ;; fully without JS; htmx only adds live updates on top.
      [:script {:src "/public/vendor/htmx.min.js"     :defer true}]
      [:script {:src "/public/vendor/htmx-sse.min.js" :defer true}]]
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
