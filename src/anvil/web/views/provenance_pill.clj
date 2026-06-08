(ns anvil.web.views.provenance-pill
  "v0.4.1 T4.4 — provenance pill on /jobs/<j>/<n>.

   Reads the dispatcher's effect stream (persisted on the build
   record), filters for :provenance/attested + :provenance/degraded,
   renders a hiccup pill with the count + per-artifact download
   links + degraded-reason callout.

   Gated by `:anvil.features/provenance` at the call site so the
   pill literally doesn't render when the operator hasn't opted in
   (AV4-7).  Degraded effects are surfaced even on the happy path
   when present — operators need to see partial signing (e.g. 2 of 3
   artifacts succeeded, 1 hit a cosign error) as honestly as full
   success.

   SSE swap: pill carries hx-trigger='sse:provenance-attested' so
   T4.5's producer can incrementally update the count + link list
   without a page reload."
  (:require [clojure.string :as str]
            [anvil.features :as features]))

(defn extract-attestations
  "Pure helper — given a build's effect stream, return
     {:attested [{:path :sha256 :bundle :cosign-version} …]
      :degraded [{:reason :path :error :note} …]}
   The per-artifact maps are exactly the second-of-tuple payloads
   emitted by the dispatcher's h-archive handler (T4.3)."
  [effects]
  (let [attested (->> effects
                      (filter #(= :provenance/attested (first %)))
                      (mapv second))
        degraded (->> effects
                      (filter #(= :provenance/degraded (first %)))
                      (mapv second))]
    {:attested attested :degraded degraded}))

(defn- artifact-link
  "Build the URL operators click to download the bundle file.  Goes
   through the existing /jobs/:name/:number/artifact/*path handler so
   we don't need a new route — the .intoto.jsonl lives beside the
   artifact in the build's artifacts dir."
  [job-name build-number bundle-path workspace]
  (let [rel (if (and workspace (str/starts-with? bundle-path workspace))
              (subs bundle-path (inc (count workspace)))
              ;; If we can't compute a relative path, fall back to
              ;; the basename — the artifact route should still find
              ;; it under archived artifacts.
              (-> (java.io.File. bundle-path) .getName))]
    (str "/jobs/" job-name "/" build-number "/artifact/" rel)))

(defn pill
  "Render the provenance pill for a build, or nil when nothing to show.

   Returns nil when:
     - :ai-authoring flag is off → nothing renders (AV4-7)
     - flag on but no :provenance/* effects (e.g. the build had no
       archiveArtifacts step) → nothing renders so we don't introduce
       a 'Provenance: 0 artifacts' false-positive UI

   Otherwise returns the hiccup fragment for the pill."
  [{:keys [job-name number effects workspace] :as _build}]
  (when (features/enabled? :provenance)
    (let [{:keys [attested degraded]} (extract-attestations effects)]
      (when (or (seq attested) (seq degraded))
        [:div.provenance-pill
         {:id "provenance-pill"
          :hx-trigger "sse:provenance-attested"
          :hx-target "this"
          :hx-swap "outerHTML"
          :style "margin: 0.5em 0; padding: 0.5em 0.75em; border:1px solid #ddd; border-radius:4px;"}
         (cond
           ;; All-attested happy path
           (and (seq attested) (empty? degraded))
           [:span
            [:strong "Provenance: ✅ attested "]
            (str "(" (count attested) " artifact"
                 (when (not= 1 (count attested)) "s") ")")
            (when-let [cv (-> attested first :cosign-version)]
              [:span.muted {:style "margin-left:0.5em; font-size:0.85em;"}
               (str "(cosign " cv ")")])]

           ;; Mixed — some signed, some failed
           (and (seq attested) (seq degraded))
           [:span
            [:strong "Provenance: ⚠ partial "]
            (str "(" (count attested) " attested, " (count degraded) " failed)")]

           ;; All degraded
           :else
           [:span
            [:strong "Provenance: ✗ failed "]
            (str "(" (count degraded) " degraded effect"
                 (when (not= 1 (count degraded)) "s") ")")])

         ;; Expandable attestation list
         (when (seq attested)
           [:details {:style "margin-top:0.5em;"}
            [:summary {:style "cursor:pointer;"}
             "Attestations"]
            [:ul {:style "font-size:0.85em; margin:0.5em 0 0 1em;"}
             (for [a attested]
               [:li
                [:code (-> (java.io.File. ^String (:path a)) .getName)]
                " — sha256 "
                [:span.muted (subs (str (:sha256 a)) 0 (min 12 (count (str (:sha256 a)))))]
                "… "
                [:a {:href (artifact-link job-name number (:bundle a) workspace)
                     :download true}
                 "[bundle]"]])]])

         ;; Degraded-reason callout — operator-actionable
         (when (seq degraded)
           [:details {:style "margin-top:0.5em;"}
            [:summary {:style "cursor:pointer; color:#b00;"}
             (str (count degraded) " degraded effect"
                  (when (not= 1 (count degraded)) "s"))]
            [:ul {:style "font-size:0.85em; margin:0.5em 0 0 1em; color:#b00;"}
             (for [d degraded]
               [:li
                [:strong (str (or (:reason d) "unknown") ": ")]
                (or (:path d) (:note d) (:error d) "")
                (when (and (:path d) (:error d))
                  [:span.muted (str " — " (:error d))])])]])]))))
