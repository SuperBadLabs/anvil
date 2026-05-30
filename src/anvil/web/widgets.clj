(ns anvil.web.widgets
  "HTML fragment endpoints for live UI widgets (TU1.4).

   Each fn here returns the SAME hiccup fragment a full page renders,
   but as a bare response (no <html>, no <head>, no nav). htmx-sse
   fires a `sse:<event-type>` event when a bus message arrives; the
   element's `hx-get` re-fetches its widget here and swaps the
   result in via `hx-swap='outerHTML'`. The fragment's own hx-* attrs
   come back with the swap, so the live wiring persists.

   Convention: widget URLs live under /anvil/widgets/ so they're
   distinct from full pages. Each widget endpoint returns
   text/html + the X-Anvil-Version header (so cache-busting tools
   can spot a daemon restart)."
  (:require [hiccup2.core :as h]
            [anvil.version :as v]
            [anvil.web.views.dashboard :as dashboard]))

(defn- html-fragment
  "Wrap a hiccup fragment as a bare HTML response."
  [hiccup]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "X-Anvil-Version" v/version
             ;; Widgets are always fresh — never cache.
             "Cache-Control" "no-store"}
   :body (str (h/html hiccup))})

(defn dashboard-stats [_req]
  (html-fragment (dashboard/stats-fragment)))
