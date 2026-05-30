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

  /* ── ANSI palette (TU2.3 / TU2.4) ─────────────────────────────────── */
  /* Foreground basic */
  .ansi-fg-black   { color: #0d1117; }
  .ansi-fg-red     { color: #f85149; }
  .ansi-fg-green   { color: #3fb950; }
  .ansi-fg-yellow  { color: #d29922; }
  .ansi-fg-blue    { color: #58a6ff; }
  .ansi-fg-magenta { color: #bc8cff; }
  .ansi-fg-cyan    { color: #39c5cf; }
  .ansi-fg-white   { color: #c9d1d9; }
  /* Foreground bright */
  .ansi-fg-brblack   { color: #6e7681; }
  .ansi-fg-brred     { color: #ffa198; }
  .ansi-fg-brgreen   { color: #56d364; }
  .ansi-fg-bryellow  { color: #e3b341; }
  .ansi-fg-brblue    { color: #79c0ff; }
  .ansi-fg-brmagenta { color: #d2a8ff; }
  .ansi-fg-brcyan    { color: #56d4dd; }
  .ansi-fg-brwhite   { color: #f0f6fc; }
  /* Background basic + bright */
  .ansi-bg-black, .ansi-bg-brblack     { background: #0d1117; }
  .ansi-bg-red, .ansi-bg-brred         { background: #4c1f22; }
  .ansi-bg-green, .ansi-bg-brgreen     { background: #1f3a1e; }
  .ansi-bg-yellow, .ansi-bg-bryellow   { background: #422d09; }
  .ansi-bg-blue, .ansi-bg-brblue       { background: #1f3b66; }
  .ansi-bg-magenta, .ansi-bg-brmagenta { background: #3c1f51; }
  .ansi-bg-cyan, .ansi-bg-brcyan       { background: #0d3b3c; }
  .ansi-bg-white, .ansi-bg-brwhite     { background: #c9d1d9; color: #0d1117; }
  /* Styles */
  .ansi-bold      { font-weight: 700; }
  .ansi-dim       { opacity: 0.6; }
  .ansi-italic    { font-style: italic; }
  .ansi-underline { text-decoration: underline; }
  .ansi-strike    { text-decoration: line-through; }

  /* ── Console layout (TU2) ─────────────────────────────────────────── */
  .console-frame { position: relative; }
  .console-frame pre.console { max-height: 70vh; }
  details.stage-fold { background: var(--bg-elevated); border-radius: var(--radius-1);
                       margin: var(--space-2) 0; }
  details.stage-fold > summary { padding: var(--space-2) var(--space-3); cursor: pointer;
                                 font-family: var(--font-sans); font-weight: 500;
                                 color: var(--fg); user-select: none; }
  details.stage-fold > summary .stage-meta { color: var(--fg-faint); font-size: 0.85em; margin-left: var(--space-3); }
  details.stage-fold > pre.console { margin: 0; border-top-left-radius: 0; border-top-right-radius: 0; max-height: 50vh; }

  /* Jump-to-bottom button (TU2.5) */
  .jump-to-bottom { position: absolute; bottom: var(--space-3); right: var(--space-3);
                    background: var(--accent); color: white;
                    border: none; border-radius: var(--radius-1);
                    padding: var(--space-2) var(--space-3); font-size: 0.85em;
                    cursor: pointer; opacity: 0; pointer-events: none;
                    transition: opacity 200ms;
                    box-shadow: 0 2px 6px rgba(0,0,0,0.25); }
  .jump-to-bottom.shown { opacity: 1; pointer-events: auto; }

  /* Console toolbar (download links + elapsed timer) */
  .console-toolbar { display: flex; gap: var(--space-3); align-items: center;
                     margin: var(--space-2) 0; font-size: 0.85em; color: var(--fg-muted); }
  .console-toolbar a { color: var(--accent); text-decoration: none; }
  .console-toolbar a:hover { text-decoration: underline; }
  .console-toolbar .elapsed { color: var(--fg); font-family: var(--font-mono); }

  /* Queue + executors (TU5) */
  .queue-frame, .executors-frame { margin: var(--space-2) 0; }

  /* Build form (TU4.1) */
  .form-row { margin: var(--space-3) 0; }
  .form-row label { display: block; color: var(--fg); margin-bottom: var(--space-1); }
  .form-row .field-name { font-weight: 600; color: var(--fg-strong); }
  .form-row .field-kind { color: var(--fg-faint); font-size: 0.85em; }
  .form-row .field-desc { color: var(--fg-muted); font-size: 0.9em; }
  .form-row input[type=text],
  .form-row input[type=password],
  .form-row input[type=number],
  .form-row select,
  .form-row textarea { width: 100%; max-width: 480px; padding: var(--space-2) var(--space-3);
                       border: 1px solid var(--border); border-radius: var(--radius-1);
                       font-family: var(--font-sans); font-size: 1em;
                       background: var(--bg); color: var(--fg); }
  .form-row input:focus, .form-row select:focus { outline: 2px solid var(--accent); }
  .form-row .field-error { color: var(--err); font-size: 0.85em; margin-top: var(--space-1); min-height: 1.2em; }
  .form-row .field-error.error { background: var(--err-bg); padding: var(--space-1) var(--space-2);
                                  border-radius: var(--radius-1); }
  .form-row.trigger-extras { background: var(--bg-elevated); padding: var(--space-2) var(--space-3);
                              border-radius: var(--radius-1); }
  .btn-trigger { background: var(--ok); color: white; border: none;
                 border-radius: var(--radius-1); padding: var(--space-2) var(--space-4);
                 font-size: 1em; font-weight: 600; cursor: pointer; }
  .btn-trigger:hover { opacity: 0.9; }
  .btn-secondary { background: var(--bg-elevated); color: var(--fg); border: 1px solid var(--border);
                    border-radius: var(--radius-1); padding: var(--space-2) var(--space-3);
                    font-size: 0.9em; cursor: pointer; }
  .btn-secondary:hover { background: var(--bg-hover); }
  .shortlink { margin: var(--space-3) 0; background: var(--bg-elevated);
               padding: var(--space-3); border-radius: var(--radius-1); }
  .shortlink textarea { width: 100%; max-width: 100%; font-family: var(--font-mono);
                        font-size: 0.85em; border: 1px solid var(--border);
                        background: var(--bg); color: var(--fg);
                        padding: var(--space-2); border-radius: var(--radius-1); }

  /* Retry button (TU3.3) */
  .btn-retry { background: var(--accent); color: white; border: none;
               border-radius: var(--radius-1); padding: var(--space-2) var(--space-3);
               font-size: 0.85em; cursor: pointer; }
  .btn-retry:hover { opacity: 0.9; }

  /* Param-diff list (TU3.2) */
  dl.diff-list > div { margin: var(--space-2) 0; padding: var(--space-2) var(--space-3);
                       background: var(--bg-elevated); border-radius: var(--radius-1); }
  dl.diff-list dt { display: inline; color: var(--fg); margin-right: var(--space-2); }
  dl.diff-list dd { display: inline; margin: 0; color: var(--fg-muted); }

  /* Compare-grid (TU3.4) */
  .compare-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-2); }
  .compare-head { padding: var(--space-2) var(--space-3); color: var(--fg-muted);
                  border-bottom: 1px solid var(--border); }
  .compare-col  { padding: var(--space-2); border-left: 3px solid transparent; }
  .stage-card   { background: var(--bg-elevated); border-radius: var(--radius-1);
                  padding: var(--space-2) var(--space-3); }
  .stage-card.failed { border-left: 3px solid var(--err); }
  .stage-card-head { color: var(--fg); margin-bottom: var(--space-2); }
  ul.steps-mini { list-style: none; padding: 0; margin: 0; }
  ul.steps-mini li { font-size: 0.85em; margin: 0.2em 0; color: var(--fg-muted); }
  ul.steps-mini code { background: transparent; color: var(--fg); }

  /* Per-job sparkline (TU3.6) */
  .job-sparkline { display: inline-block; vertical-align: middle; }
  .job-sparkline rect { transition: opacity 120ms; }
  .job-sparkline:hover rect { opacity: 0.85; }

  /* Builds-table live frame (TU3.1) */
  .builds-table-frame { margin-top: var(--space-2); }

  /* Per-step duration SVG (TU2.8) */
  .duration-chart { width: 100%; height: auto; margin: var(--space-3) 0; }
  .duration-chart .bar { fill: var(--accent); }
  .duration-chart .bar:hover { fill: var(--info); }
  .duration-chart .label { fill: var(--fg-muted); font-size: 11px; font-family: var(--font-sans); }
  .duration-chart .axis  { stroke: var(--border); stroke-width: 1; }

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
       [:a {:href "/executors" :class (when (= active :executors) "active")} "Executors"]
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
