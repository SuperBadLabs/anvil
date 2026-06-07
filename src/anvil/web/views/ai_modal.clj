(ns anvil.web.views.ai-modal
  "v0.4.1 T3.5 — UI helpers for the AI authoring buttons + response modal
   on the `/jobs/<j>` page.

   Two visible buttons:
     'Explain'  — POST /jobs/:name/ai/explain  → modal text
     'Optimize' — POST /jobs/:name/ai/optimize → modal text (and
                                                 publishes :ai-suggested
                                                 on the bus per T3.4)

   Both buttons use htmx (already vendored at TU0.3) to swap the
   response into the modal target.  Streaming is intentionally NOT
   used at this surface — operator clicks the button, sees a spinner
   for 2-8s, then the full response renders.  Streaming via SSE
   would add real complexity (chunked response body + closeable
   channel + reconnect) for a wait that's already tolerable.  When
   we want streaming, the SSE bus + the existing /anvil/events
   subscriber pattern is ready.

   Gated by `:anvil.features/ai-authoring` at the route layer per
   AV4-7 (closed by default).  When the flag is off the buttons
   themselves don't render — operators see the page as it was
   pre-T3.5, no half-states."
  (:require [anvil.features :as features]))

(defn buttons-or-nothing
  "Hiccup for the Explain/Optimize buttons + the empty modal target.
   Returns nil when the :ai-authoring flag is off so we don't render
   visible-but-broken buttons.  Place this immediately under the
   Jenkinsfile <pre> on the job detail page."
  [job-name]
  (when (features/enabled? :ai-authoring)
    [:div.ai-authoring-controls
     {:style "margin: 0.5em 0 1em 0; display:flex; gap:0.5em; align-items:center;"}
     [:button.btn-ai
      {:type "button"
       :hx-post (str "/jobs/" job-name "/ai/explain")
       :hx-target "#ai-modal-content"
       :hx-swap "innerHTML"
       :hx-indicator "#ai-modal-spinner"
       :hx-on:click "document.getElementById('ai-modal').showModal()"
       :style "padding:0.35em 0.85em; cursor:pointer;"}
      "✨ Explain this Jenkinsfile"]
     [:button.btn-ai
      {:type "button"
       :hx-post (str "/jobs/" job-name "/ai/optimize")
       :hx-target "#ai-modal-content"
       :hx-swap "innerHTML"
       :hx-indicator "#ai-modal-spinner"
       :hx-on:click "document.getElementById('ai-modal').showModal()"
       :style "padding:0.35em 0.85em; cursor:pointer;"}
      "⚡ Optimize"]
     [:span.muted
      {:style "font-size:0.85em;"}
      "(local-first: calls Anthropic API with your "
      [:code "ANTHROPIC_API_KEY"]
      ")"]
     ;; The modal itself — a <dialog> element rendered hidden, opened
     ;; by hx-on:click above.  Using <dialog> rather than a custom
     ;; overlay because it's the platform-native modal and gets focus
     ;; trap + escape-to-close for free.
     [:dialog#ai-modal
      {:style "max-width:80ch; padding:1.5em; border:1px solid #ccc; border-radius:6px;"}
      [:div {:style "display:flex; justify-content:space-between; align-items:center; margin-bottom:1em;"}
       [:h3 {:style "margin:0;"} "AI response"]
       [:button
        {:type "button"
         :onclick "document.getElementById('ai-modal').close()"
         :style "padding:0.2em 0.6em; cursor:pointer;"}
        "✕ close"]]
      [:div#ai-modal-spinner.htmx-indicator
       {:style "padding:1em 0;"}
       [:em "Calling Anthropic API… (this can take 5-10 seconds)"]]
      [:div#ai-modal-content
       {:style "white-space:pre-wrap; font-family:monospace; font-size:0.9em; line-height:1.5;"}]]]))

(defn response-fragment
  "Render the AI response as a fragment to swap into the modal.
   `result` is the map shape returned by anvil.ai.client/messages.

   The footer surfaces stop-reason + token usage (operator visibility:
   tells you whether the response was truncated, refused, or completed
   normally; same as the CLI stderr footer)."
  [{:keys [text stop-reason usage kind]}]
  [:div
   [:div.ai-response-body
    {:style "white-space:pre-wrap; font-family:monospace; font-size:0.9em;"}
    text]
   [:hr {:style "margin:1em 0; border:none; border-top:1px solid #eee;"}]
   [:div.ai-response-footer.muted
    {:style "font-size:0.85em;"}
    (case stop-reason
      "end_turn"   [:span "✓ Done."]
      "max_tokens" [:span.warn "⚠ Hit max-tokens — response truncated.  Retry with focused prompt."]
      "refusal"    [:span.warn "⚠ Model refused to respond.  Check Console for refusal details."]
      [:span (str "Done.  stop_reason=" (pr-str stop-reason))])
    (when (seq usage)
      [:span (format "  ·  tokens: in=%s  out=%s  ·  kind=%s"
                     (or (:input-tokens usage) "?")
                     (or (:output-tokens usage) "?")
                     (name (or kind :unknown)))])]])

(defn error-fragment
  "Operator-facing error rendering for the modal.  Stays inside the
   modal target so the page UX doesn't bounce; surfaces both message
   and :hint so the operator knows what to fix."
  [{:keys [message hint fix]}]
  [:div.ai-response-error
   {:style "color:#b00; padding:0.5em 0;"}
   [:strong "AI call failed: "] (or message "<unknown>")
   (when hint    [:div.muted {:style "margin-top:0.5em;"} [:strong "hint: "] hint])
   (when fix     [:div.muted {:style "margin-top:0.3em;"} [:strong "fix: "]  fix])])

(defn feature-disabled-fragment
  "Rendered when the :ai-authoring flag is off — usually unreachable
   since the buttons themselves don't render in that case, but route-
   level gates also catch direct POSTs (e.g. from external clients)."
  []
  [:div.ai-response-error
   {:style "color:#888; padding:0.5em 0;"}
   "AI authoring is disabled on this anvil instance.  Operator needs to set "
   [:code ":anvil.features/ai-authoring true"] " in anvil.edn."])
