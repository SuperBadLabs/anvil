(ns anvil.web.views.provenance-pill-test
  "v0.4.1 T4.4 — Hiccup-shape tests for the provenance pill view.

   Pure data — feeds the pill helper a synthesized effects vector
   (no real cosign, no dispatcher).  Asserts that:
     - flag-off returns nil (no rendering, AV4-7)
     - no provenance/* effects returns nil (no false positives)
     - happy path renders count + attestations details
     - degraded-only renders the failure callout
     - mixed renders 'partial' state"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [anvil.features :as features]
            [anvil.web.views.provenance-pill :as pp]))

(use-fixtures :each
  (fn [t]
    (try (t)
         (finally
           (features/set! :provenance false)))))

(defn- attested [path sha256 bundle]
  [:provenance/attested {:path path :sha256 sha256 :bundle bundle :cosign-version "v3.0.6"}])

(defn- degraded [reason note]
  [:provenance/degraded {:reason reason :note note}])

;; ---------------------------------------------------------------------------
;; extract-attestations — pure helper
;; ---------------------------------------------------------------------------

(deftest extract-pulls-attested-and-degraded
  (let [effects [[:archive {:artifacts "target/*.jar"}]
                 (attested "/ws/a.jar" "aaaa" "/ws/a.jar.intoto.jsonl")
                 (attested "/ws/b.jar" "bbbb" "/ws/b.jar.intoto.jsonl")
                 (degraded :cosign-error "boom")]
        result (pp/extract-attestations effects)]
    (is (= 2 (count (:attested result))))
    (is (= 1 (count (:degraded result))))
    (is (= "/ws/a.jar" (-> result :attested first :path)))))

(deftest extract-empty-when-no-provenance-effects
  (let [result (pp/extract-attestations
                [[:archive {:artifacts "target/*.jar"}]
                 [:sh {:cmd "make"}]])]
    (is (= [] (:attested result)))
    (is (= [] (:degraded result)))))

;; ---------------------------------------------------------------------------
;; pill — flag gating
;; ---------------------------------------------------------------------------

(deftest pill-returns-nil-when-flag-off
  (features/set! :provenance false)
  (is (nil? (pp/pill {:job-name "demo" :number 1
                      :effects [(attested "/ws/a.jar" "aaaa" "/ws/a.jar.intoto.jsonl")]
                      :workspace "/ws"}))
      "flag off → nil so the build page renders identically to pre-T4.4"))

(deftest pill-returns-nil-when-no-provenance-effects-even-with-flag-on
  (testing "no archiveArtifacts step in this build → no pill (don't manufacture 'Provenance: 0' UI)"
    (features/set! :provenance true)
    (is (nil? (pp/pill {:job-name "demo" :number 1
                        :effects [[:archive {:artifacts "target/*.jar"}]
                                  [:sh {:cmd "make"}]]
                        :workspace "/ws"})))))

;; ---------------------------------------------------------------------------
;; pill — happy path
;; ---------------------------------------------------------------------------

(deftest pill-happy-path-shows-count-and-cosign-version
  (features/set! :provenance true)
  (let [hiccup (pp/pill {:job-name "demo" :number 1
                         :effects [(attested "/ws/target/a.jar" "aaaa" "/ws/target/a.jar.intoto.jsonl")
                                   (attested "/ws/target/b.jar" "bbbb" "/ws/target/b.jar.intoto.jsonl")
                                   (attested "/ws/target/c.jar" "cccc" "/ws/target/c.jar.intoto.jsonl")]
                         :workspace "/ws"})
        html (str hiccup)]
    (is (str/includes? html "attested"))
    (is (str/includes? html "3 artifact"))
    (is (str/includes? html "cosign v3.0.6"))
    (testing "hx-trigger wires the SSE swap"
      (is (str/includes? html "sse:provenance-attested")))
    (testing "expandable details with per-artifact link"
      (is (str/includes? html "a.jar"))
      (is (str/includes? html "/jobs/demo/1/artifact/target/a.jar.intoto.jsonl")
          "bundle link goes through the existing artifact-serve route"))))

(deftest pill-happy-path-single-artifact-uses-singular-noun
  (features/set! :provenance true)
  (let [html (str (pp/pill {:job-name "demo" :number 1
                            :effects [(attested "/ws/a.jar" "aaaa" "/ws/a.jar.intoto.jsonl")]
                            :workspace "/ws"}))]
    (is (str/includes? html "1 artifact)")
        "singular: 1 artifact, not 1 artifacts")
    (is (not (str/includes? html "1 artifacts")))))

;; ---------------------------------------------------------------------------
;; pill — degraded paths
;; ---------------------------------------------------------------------------

(deftest pill-all-degraded-shows-failed-state
  (features/set! :provenance true)
  (let [html (str (pp/pill {:job-name "demo" :number 1
                            :effects [(degraded :cosign-missing "no cosign")]
                            :workspace "/ws"}))]
    (is (str/includes? html "failed"))
    (is (str/includes? html "1 degraded"))
    (testing "operator sees the reason"
      (is (str/includes? html "cosign-missing")))))

(deftest pill-mixed-shows-partial-state
  (features/set! :provenance true)
  (let [html (str (pp/pill {:job-name "demo" :number 1
                            :effects [(attested "/ws/a.jar" "aaaa" "/ws/a.jar.intoto.jsonl")
                                      (attested "/ws/b.jar" "bbbb" "/ws/b.jar.intoto.jsonl")
                                      (degraded :cosign-sign-failed "exit 1")]
                            :workspace "/ws"}))]
    (is (str/includes? html "partial"))
    (is (str/includes? html "2 attested"))
    (is (str/includes? html "1 failed"))))
