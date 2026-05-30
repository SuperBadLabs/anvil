(ns anvil.web.views.queue-page
  "Live queue + executors view (TU5.1+5.2+5.4+5.5).

   /queue            — full page with the queue fragment + executors
                       fragment
   /executors        — full page focused on executor capacity
   /anvil/widgets/queue      — htmx-sse refresh target
   /anvil/widgets/executors  — htmx-sse refresh target

   Both fragments follow the docs/anvil-ui/live-widgets.md convention:
   subscribe to :queue + :global on the bus, hx-trigger on the
   relevant SSE event types, swap outerHTML."
  (:require [hiccup2.core :as h]
            [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.queue :as queue]
            [anvil.web.jenkins-api.runner :as runner]))

;; ---------------------------------------------------------------------------
;; Queue fragment (TU5.1 + TU5.4 + TU5.5)
;; ---------------------------------------------------------------------------

(defn- queue-row [item]
  (let [reason (queue/blocked-reason item)
        running? (= :dispatched (:phase item))
        cancelled? (:cancelled? item)
        enq-at (:enqueued-at item)
        enq-ms (some-> enq-at (.toEpochMilli))]
    [:tr
     [:td "#" (:queue-id item)]
     [:td [:a {:href (str "/jobs/" (:job-name item))} (:job-name item)]]
     [:td (cond
            cancelled?  [:span.badge.gray "cancelled"]
            running?    [:span.badge.anim "dispatched"]
            reason      [:span.badge.yellow reason]
            :else       [:span.badge.blue "ready"])]
     ;; TU5.5: live queued-for counter. Frozen for cancelled/dispatched.
     [:td (if (or cancelled? running? (nil? enq-ms))
            [:span.muted (str enq-at)]
            [:span.elapsed.queued-for
             {:data-started-at-ms (str enq-ms)}
             "…"])]
     [:td
      (when (and (not cancelled?) (not running?))
        [:button.btn-secondary
         {:type "button"
          :hx-post (str "/queue/cancel/" (:queue-id item))
          :hx-confirm "Cancel this queued build?"}
         "✕ cancel"])]]))

(defn queue-fragment
  "Self-contained live queue table. Follows the htmx-sse convention.
   Re-fetched on every queue-* bus event."
  []
  (let [items (queue/queue-snapshot)
        active (remove #(or (:cancelled? %)
                            (= :dispatched (:phase %))
                            (= :completed (:phase %)))
                       items)]
    [:div.queue-frame
     {:id "queue-frame"
      :hx-ext "sse"
      :sse-connect "/anvil/events?topics=queue"
      :hx-get "/anvil/widgets/queue"
      :hx-trigger "sse:queue-enqueued, sse:queue-dispatched"
      :hx-swap "outerHTML"}
     (if (empty? active)
       [:p.muted "Queue empty."]
       [:table
        [:thead
         [:tr [:th "id"] [:th "job"] [:th "status"]
          [:th "queued for"] [:th "actions"]]]
        [:tbody (for [it active] (queue-row it))]])]))

;; ---------------------------------------------------------------------------
;; Executors fragment (TU5.2 + TU5.4)
;; ---------------------------------------------------------------------------

(defn- executor-row
  [slot-idx in-flight-item]
  (if in-flight-item
    (let [{:keys [job-name build-number started-at]} in-flight-item]
      [:tr
       [:td [:code (str "exec-" slot-idx)]]
       [:td [:span.badge.anim "busy"]]
       [:td [:a {:href (str "/jobs/" job-name)} job-name] " "
            [:a {:href (str "/jobs/" job-name "/" build-number)} (str "#" build-number)]]
       [:td [:span.elapsed
             {:data-started-at-ms (str (some-> started-at (.toEpochMilli)))}
             "…"]]
       [:td [:button.btn-secondary
             {:type "button"
              :hx-post (str "/jobs/" job-name "/" build-number "/kill")
              :hx-confirm (str "Kill build " job-name " #" build-number "?")}
             "⛔ kill"]]])
    [:tr
     [:td [:code (str "exec-" slot-idx)]]
     [:td [:span.badge.gray "idle"]]
     [:td [:span.muted "—"]]
     [:td [:span.muted "—"]]
     [:td]]))

(defn executors-fragment
  []
  (let [{:keys [max-workers]} (queue/config-snapshot)
        in-flight (runner/in-flight-snapshot)
        slots (vec (concat
                    (map vector (range) in-flight)
                    (map (fn [i] [i nil])
                         (range (count in-flight) (max 1 max-workers)))))]
    [:div.executors-frame
     {:id "executors-frame"
      :hx-ext "sse"
      :sse-connect "/anvil/events?topics=global"
      :hx-get "/anvil/widgets/executors"
      :hx-trigger "sse:build-started, sse:build-done"
      :hx-swap "outerHTML"}
     [:table
      [:thead
       [:tr [:th "Executor"] [:th "Status"] [:th "Current build"]
        [:th "Running for"] [:th "Action"]]]
      [:tbody
       (for [[i item] slots]
         (executor-row i item))]]]))

;; ---------------------------------------------------------------------------
;; Inline JS shared by queue + executors pages — TU2.7's elapsed
;; ticker, isolated so this page doesn't depend on having visited
;; /console first.
;; ---------------------------------------------------------------------------

(def ^:private tick-js
  "
(function(){
  if (window.__anvilElapsedWired) return;
  window.__anvilElapsedWired = true;
  function tick(){
    document.querySelectorAll('.elapsed[data-started-at-ms]').forEach(function(el){
      var t = parseInt(el.dataset.startedAtMs, 10);
      if(!t) return;
      var s = Math.floor((Date.now() - t)/1000);
      var h = Math.floor(s/3600), m = Math.floor((s%3600)/60), ss = s%60;
      el.textContent = (h ? (h+'h ') : '') + (m+'m ') + (ss+'s');
    });
  }
  tick(); setInterval(tick, 1000);
})();
")

;; ---------------------------------------------------------------------------
;; Pages
;; ---------------------------------------------------------------------------

(defn page
  "Full /queue page: status cards + queue fragment + executors
   fragment, all live."
  [_req]
  (let [items (queue/queue-snapshot)
        active (remove #(or (:cancelled? %)
                            (= :dispatched (:phase %))
                            (= :completed (:phase %)))
                       items)
        running (queue/running-snapshot)
        running-count (reduce + 0 (vals running))
        {:keys [max-workers]} (queue/config-snapshot)]
    (layout/page
     {:title "Queue + Executors" :active :queue}
     [:h2 "Queue + executors"]

     [:div.stat-row
      [:div.stat
       [:div.stat-label "Queued"]
       [:div.stat-value {:class (if (seq active) "yellow" "muted")} (count active)]]
      [:div.stat
       [:div.stat-label "Running"]
       [:div.stat-value {:class (if (pos? running-count) "yellow" "muted")} running-count]]
      [:div.stat
       [:div.stat-label "Capacity"]
       [:div.stat-value (str running-count " / " max-workers)]]]

     [:h3 "Queue"]
     (queue-fragment)

     [:h3 "Executors"]
     (executors-fragment)

     [:script (h/raw tick-js)])))

(defn executors-page
  "Standalone /executors page. Same fragment, different framing."
  [_req]
  (let [{:keys [max-workers]} (queue/config-snapshot)
        in-flight (runner/in-flight-snapshot)]
    (layout/page
     {:title "Executors" :active :queue}
     [:h2 "Executors (" (count in-flight) " / " max-workers ")"]
     [:p.muted
      [:a {:href "/queue"} "← back to queue"]]
     (executors-fragment)
     [:script (h/raw tick-js)])))
