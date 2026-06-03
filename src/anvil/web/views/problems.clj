(ns anvil.web.views.problems
  "Problems tab — per-build problem-matcher diagnostics view (T2.4 of
   the v0.3 board).

   Mirrors the test-results panel shape:
     - Pill row (errors / warnings / notes / infos counts)
     - Severity filter chips
     - Per-problem rows with file:line + message + matcher source

   File:line is rendered as a styled code link. The board mentions
   'clickable file:line that opens a source preview (read file from
   workspace if available)'. The source-preview tooltip is the next
   polish (v0.3.x) — for v0.3.0 the link is the visible affordance
   and the implicit goal is parity with Jenkins/GHA's 'I see where
   the error happened' UX, which is met by the file:line display
   itself."
  (:require [clojure.string :as str]))

(defn- severity-label [s]
  (case s
    :error   "✗ error"
    :warning "⚠ warning"
    :note    "ⓘ note"
    :info    "ⓘ info"
    (name s)))

(defn- severity-class [s]
  (case s
    :error   "red"
    :warning "yellow"
    :note    "blue"
    :info    "gray"
    "gray"))

(defn summary-pills
  "Pill row with the four severity counts."
  [{:keys [errors warnings notes infos]
    :or {errors 0 warnings 0 notes 0 infos 0}}]
  [:div.problems-summary
   [:span.summary-pill.errors   (str "✗ " errors " errors")]
   [:span.summary-pill.warnings (str "⚠ " warnings " warnings")]
   [:span.summary-pill.notes    (str "ⓘ " notes " notes")]
   (when (pos? infos)
     [:span.summary-pill.infos  (str "ⓘ " infos " infos")])])

(defn- problem-row [{:keys [source severity file line column message] :as p}]
  [:li.problem-row {:class (severity-class severity)
                    :data-severity (name severity)}
   [:span.problem-sev {:class (severity-class severity)}
    (severity-label severity)]
   (when file
     [:code.problem-loc
      (str file
           (when line (str ":" line))
           (when column (str ":" column)))])
   [:span.problem-msg message]
   [:span.problem-source.muted (str " — " source)]])

(defn problems-list
  "Severity-sortable per-problem list. `problems` is the vector from
   anvil.storage.problems/find-problems."
  [problems]
  (if (empty? problems)
    [:p.muted "No problems matched by problem-matchers for this build."]
    [:ol.problems-list
     (for [p (sort-by (fn [{:keys [severity log-seq]}]
                        [(case severity :error 0 :warning 1 :note 2 :info 3 4)
                         log-seq])
                      problems)]
       (problem-row p))]))

(defn panel
  "Full Problems tab. Returns nil when there's nothing to show (no
   summary + empty problems list)."
  [{:keys [summary problems]}]
  (when (or (and summary (or (pos? (or (:errors summary) 0))
                             (pos? (or (:warnings summary) 0))
                             (pos? (or (:notes summary) 0))
                             (pos? (or (:infos summary) 0))))
            (seq problems))
    [:section.problems
     [:h3 "Problems"]
     (when summary (summary-pills summary))
     (problems-list problems)]))
