(ns anvil.web.views.matrix
  "Matrix grid view (T4.5 of the v0.3 board).

   Renders a 2-axis row × column grid of child cell builds. For 3+
   axes the first two pick the grid; remaining axes become a per-cell
   sub-label. The caller supplies:

     :parent         the parent build IR + name + number
     :cells          [{:axes {…} :build-number N :result KW
                       :duration-ms N :building? BOOL}]
     :axes           the original IR axes (order matters for grid)

   When the cells list is empty (a matrix that hasn't expanded yet),
   renders an explanatory placeholder."
  (:require [clojure.string :as str]))

(defn- status-class [{:keys [result building?]}]
  (cond
    building?              "anim"
    (= :success  result)   "blue"
    (= :failure  result)   "red"
    (= :unstable result)   "yellow"
    (= :aborted  result)   "gray"
    :else                  "gray"))

(defn- status-label [{:keys [result building?]}]
  (cond
    building?            "▶ running"
    (= :success result)  "✓ success"
    (= :failure result)  "✗ failure"
    (= :unstable result) "⚠ unstable"
    (= :aborted result)  "⊘ aborted"
    :else                "—"))

(defn- duration-pretty [ms]
  (cond
    (nil? ms) ""
    (< ms 1000) (str ms " ms")
    (< ms 60000) (format "%.1fs" (double (/ ms 1000.0)))
    :else (format "%dm %ds" (quot ms 60000) (quot (mod ms 60000) 1000))))

(defn- pivot-axes
  "First two axes form the grid rows/cols. Remaining axes (if any)
   become per-cell sub-labels."
  [axes]
  (let [grid (take 2 axes)
        extra (drop 2 axes)]
    {:row-axis (first grid)
     :col-axis (second grid)
     :extra extra}))

(defn- cell-at
  "Find the cell whose axis map matches (row-name=row-val AND
   col-name=col-val), grouping over any extra axes. When col-name
   is nil (single-axis matrix), the column predicate is a no-op."
  [cells row-name row-val col-name col-val]
  (filter (fn [c]
            (and (= row-val (get-in c [:axes row-name]))
                 (or (nil? col-name)
                     (= col-val (get-in c [:axes col-name])))))
          cells))

(defn- cell-tile
  "Render one child build as a tile inside a grid cell."
  [{:keys [axes build-number] :as cell} job-name extra-axes]
  [:a.matrix-cell-tile
   {:href (str "/jobs/" job-name "/" build-number)
    :class (status-class cell)
    :title (str (status-label cell)
                (when (:duration-ms cell)
                  (str " · " (duration-pretty (:duration-ms cell)))))}
   [:span.cell-status (status-label cell)]
   (when (seq extra-axes)
     [:span.cell-extra
      (str/join " · "
                (map (fn [{:keys [name]}]
                       (str name "=" (get axes name)))
                     extra-axes))])])

(defn grid
  "Full grid Hiccup for a 2+ axis matrix. Single-axis matrices render
   as a single-column grid."
  [{:keys [parent cells axes]}]
  (if (empty? cells)
    [:section.matrix-grid
     [:p.muted (str "Matrix in parent #" (:number parent)
                    " has no cell builds recorded yet.")]]
    (let [{:keys [row-axis col-axis extra]} (pivot-axes axes)
          rows (:values row-axis)
          cols (when col-axis (:values col-axis))]
      [:section.matrix-grid
       [:h3 "Matrix"]
       [:table.matrix-table
        [:thead
         [:tr
          [:th {:rowspan 1} (str (:name row-axis) " ↓"
                                  (when col-axis
                                    (str " · " (:name col-axis) " →")))]
          (for [c (or cols ["—"])]
            [:th c])]]
        [:tbody
         (for [r rows]
           [:tr
            [:th r]
            (for [c (or cols ["—"])]
              [:td.matrix-cell
               (let [hits (cell-at cells (:name row-axis) r
                                   (when col-axis (:name col-axis)) c)]
                 (if (empty? hits)
                   [:span.muted "—"]
                   (for [cell hits]
                     (cell-tile cell (:name parent) extra))))])])]]])))
