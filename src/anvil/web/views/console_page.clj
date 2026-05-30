(ns anvil.web.views.console-page
  "Per-build console view (TU2.2 + TU2.4 + TU2.5 + TU2.7 + TU2.8).

   At /jobs/<name>/<n>/console — the marquee feature. Renders:
     - the on-disk console log up to NOW (backlog)
     - a `<details>` per pipeline stage (TU2.4), folded by stage marker
       in the effects vector
     - an ANSI-coloured render (TU2.3)
     - SSE-tailed delta for new lines while building? (TU2.2)
     - 'Jump to bottom' button with auto-scroll lock (TU2.5)
     - live elapsed-time counter (TU2.7)
     - inline-SVG per-step duration breakdown (TU2.8)
     - download links: ?download=raw + ?download=text (TU2.6)

   Renders ALL needed JS inline at the bottom of the page; total
   shim is ~120 LoC of vanilla JS, no framework, no build step."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [anvil.web.views.layout :as layout]
            [anvil.web.jenkins-api.jobs :as jobs]
            [anvil.web.ansi :as ansi]))

;; ---------------------------------------------------------------------------
;; Stage grouping from the effects vector
;; ---------------------------------------------------------------------------

(defn- effects->stage-groups
  "Walk the build's effects vector, grouping :stdout/:stderr lines
   (and rendered :sh / :echo markers) between :agent/stage-enter and
   :agent/stage-leave events. Returns a vec of stage maps:
     {:name STR :lines [STR ...] :step-count N}"
  [effects]
  (let [outside (atom [])
        groups (atom [])
        current (atom nil)]
    (doseq [ev effects]
      (let [tag (first ev)
            payload (second ev)]
        (cond
          (= tag :agent/stage-enter)
          (do (when @current (swap! groups conj @current))
              (reset! current {:name (:stage payload) :lines [] :step-count 0}))

          (= tag :agent/stage-leave)
          (do (when @current (swap! groups conj @current))
              (reset! current nil))

          ;; Console-shaped events while inside a stage
          (and @current (#{:stdout :stderr :echo} tag))
          (swap! current update :lines conj (str payload))

          (and @current (= :sh tag))
          (do (swap! current update :lines conj (str "+ " (:cmd payload)))
              (swap! current update :step-count inc))

          ;; Pre-stage or stage-free output
          (and (not @current) (#{:stdout :stderr :echo} tag))
          (swap! outside conj (str payload))

          :else nil)))
    (when @current (swap! groups conj @current))
    {:outside @outside
     :stages @groups}))

;; ---------------------------------------------------------------------------
;; Inline-SVG per-step duration chart (TU2.8)
;;
;; We don't have stage timestamps in the effects vector (would require
;; an engine change), so v1 uses :step-count per stage as a proxy for
;; relative cost. The chart axis says \"steps\" not \"seconds\"; honest
;; about what we're showing.
;; ---------------------------------------------------------------------------

(defn- stage-chart-svg
  "Render a horizontal bar chart of stage 'cost' (step-count proxy).
   Pure server-side SVG; no chart library."
  [stage-groups]
  (let [stages (vec (:stages stage-groups))]
    (when (seq stages)
      (let [n (count stages)
            row-h 22
            label-w 140
            chart-w 600
            total-w (+ label-w chart-w 12)
            total-h (* n row-h)
            max-cost (max 1 (apply max (map :step-count stages)))]
        [:svg.duration-chart
         {:viewBox (str "0 0 " total-w " " total-h)
          :role "img"
          :aria-label "Per-stage step count"}
         (for [[i {:keys [name step-count]}] (map-indexed vector stages)
               :let [y (* i row-h)
                     bar-w (* chart-w (/ (double step-count) max-cost))]]
           [:g
            [:text.label {:x (- label-w 6) :y (+ y (* 0.7 row-h))
                          :text-anchor "end"}
             (str name)]
            [:rect.bar {:x label-w :y (+ y 4)
                        :width (max 1 bar-w) :height (- row-h 8)
                        :rx 2}]
            [:text.label {:x (+ label-w bar-w 6) :y (+ y (* 0.7 row-h))}
             (str step-count " step" (when-not (= 1 step-count) "s"))]])]))))

;; ---------------------------------------------------------------------------
;; Hiccup for one stage (TU2.4)
;; ---------------------------------------------------------------------------

(defn- render-stage-html
  "ANSI-colour each line in `:lines`, join with \\n, return the raw
   HTML payload for a <pre> inside the <details>."
  [{:keys [lines]}]
  (->> lines
       (map ansi/ansi->html)
       (str/join "\n")))

(defn- stage-fold
  [{:keys [name step-count] :as stage}]
  [:details.stage-fold {:open true}
   [:summary
    [:span (or name "<unnamed stage>")]
    [:span.stage-meta
     (str step-count " step" (when-not (= 1 step-count) "s"))]]
   [:pre.console (h/raw (render-stage-html stage))]])

;; ---------------------------------------------------------------------------
;; The page
;; ---------------------------------------------------------------------------

(def ^:private console-js
  "Inline JS shim (~80 lines including comments). Vanilla, no deps.
   Tracks the bottom-of-console scroll state, shows the
   'Jump to bottom' button when user scrolls up, follows new SSE-
   appended lines when at the bottom. Also runs the elapsed-timer
   tick when the build is still in flight.

   Loaded once per build page; deduplicates if re-included."
  "
(function(){
  if (window.__anvilConsoleWired) return;
  window.__anvilConsoleWired = true;

  // ── TU2.5: auto-scroll lock + Jump to bottom ─────────────────────
  function wireScrollLock(frame, pre, btn) {
    var threshold = 24;  // px from bottom counts as 'following'
    var following = true;
    function atBottom() {
      return (pre.scrollHeight - pre.scrollTop - pre.clientHeight) < threshold;
    }
    function setFollowing(v) {
      following = v;
      btn.classList.toggle('shown', !v);
    }
    pre.addEventListener('scroll', function(){
      setFollowing(atBottom());
    });
    btn.addEventListener('click', function(){
      pre.scrollTop = pre.scrollHeight;
      setFollowing(true);
    });
    // Re-scroll on every DOM mutation IF following.
    new MutationObserver(function(){
      if (following) pre.scrollTop = pre.scrollHeight;
    }).observe(pre, {childList: true, subtree: true, characterData: true});
    // Initial: at bottom by default.
    pre.scrollTop = pre.scrollHeight;
  }
  document.querySelectorAll('.console-frame').forEach(function(frame){
    var pre = frame.querySelector('pre.console.live-tail');
    var btn = frame.querySelector('.jump-to-bottom');
    if (pre && btn) wireScrollLock(frame, pre, btn);
  });

  // ── TU2.7: elapsed timer ─────────────────────────────────────────
  function tickElapsed(el) {
    var started = parseInt(el.dataset.startedAtMs, 10);
    if (!started) return;
    function fmt(ms) {
      var s = Math.floor(ms / 1000);
      var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = s % 60;
      return (h ? (h + 'h ') : '') + (m + 'm ') + (ss + 's');
    }
    function step(){ el.textContent = fmt(Date.now() - started); }
    step();
    var id = setInterval(step, 1000);
    // Freeze on SSE :build-done event.
    document.querySelectorAll('[hx-ext~=\"sse\"][sse-connect]').forEach(function(src){
      src.addEventListener('sse:build-done', function(){
        clearInterval(id);
        el.dataset.finished = '1';
      });
    });
  }
  document.querySelectorAll('.elapsed[data-started-at-ms]').forEach(function(el){
    if (!el.dataset.finished) tickElapsed(el);
  });

  // ── TU2.2: SSE-tailed live append ─────────────────────────────────
  // The .live-tail <pre> listens for sse:console-line events on its
  // configured sse-connect endpoint and appends the line as a span.
  document.querySelectorAll('pre.console.live-tail').forEach(function(pre){
    var topic = pre.dataset.sseTopic;
    if (!topic) return;
    var src = new EventSource('/anvil/events?topics=' + encodeURIComponent(topic));
    src.addEventListener('console-line', function(ev){
      try {
        var payload = JSON.parse(ev.data);
        var line = document.createTextNode((payload.line || '') + '\\n');
        pre.appendChild(line);
      } catch (_) {}
    });
    src.addEventListener('console-end', function(){
      src.close();
    });
  });
})();")

(defn page
  "Render the console page. URL pattern: /jobs/<name>/<n>/console."
  [req]
  (let [job-name (get-in req [:path-params :name])
        n (try (Integer/parseInt (str (get-in req [:path-params :number])))
               (catch Exception _ nil))
        b (when n (jobs/find-build job-name n))]
    (if-not b
      (layout/page
       {:title "Build not found" :active :jobs}
       [:h2 "Build " [:code (str job-name "#" n)] " not found"]
       [:p [:a {:href (str "/jobs/" job-name)} "← back to " job-name]])
      (let [building? (:building? b)
            stage-groups (effects->stage-groups (:effects b))
            started-at (:started-at b)
            started-ms (when started-at (.toEpochMilli started-at))
            sse-topic (str "build:" job-name ":" n)]
        (layout/page
         {:title (str job-name " #" n " — console") :active :jobs}
         [:h2 job-name " " [:code (str "#" n)] " — console"
          " "
          (cond
            building?                   [:span.badge.anim "running"]
            (= :success (:result b))    [:span.badge.blue "success"]
            (= :failure (:result b))    [:span.badge.red  "failure"]
            :else                       [:span.badge.gray "—"])]

         [:p.muted
          [:a {:href (str "/jobs/" job-name)} (str "← " job-name)]
          " · "
          [:a {:href (str "/jobs/" job-name "/" n)} "build summary"]]

         ;; Toolbar: downloads + elapsed timer (TU2.6 + TU2.7)
         [:div.console-toolbar
          [:span "Downloads:"]
          [:a {:href (str "/jobs/" job-name "/" n "/console?download=raw")
               :download (str job-name "-" n ".console.log")}
           "raw"]
          [:a {:href (str "/jobs/" job-name "/" n "/console?download=text")
               :download (str job-name "-" n ".console.txt")}
           "text"]
          [:span "·"]
          [:span "Elapsed:"]
          (if building?
            [:span.elapsed {:data-started-at-ms (str started-ms)}]
            [:span.elapsed (str (:duration-ms b) " ms")])]

         ;; Stage-fold backlog (TU2.4) — rendered server-side.
         (when (seq (:outside stage-groups))
           [:details.stage-fold {:open true}
            [:summary [:span "<pre-stage output>"]]
            [:pre.console
             (h/raw (->> (:outside stage-groups)
                         (map ansi/ansi->html)
                         (str/join "\n")))]])
         (for [stage (:stages stage-groups)]
           (stage-fold stage))

         ;; Live-tail pre (TU2.2 + TU2.5) — empty on completed builds,
         ;; carries new SSE-streamed lines on running ones.
         (when building?
           [:div.console-frame
            [:h3 "Live tail"]
            [:pre.console.live-tail
             {:data-sse-topic sse-topic
              :hx-ext "sse"
              :sse-connect (str "/anvil/events?topics=" sse-topic)}]
            [:button.jump-to-bottom {:type "button"}
             "↓ Jump to bottom"]])

         ;; Per-stage step-count chart (TU2.8) — only for builds with
         ;; >0 stages recorded.
         (when (seq (:stages stage-groups))
           [:div
            [:h3 "Per-stage step count"]
            (stage-chart-svg stage-groups)])

         [:script (h/raw console-js)])))))
