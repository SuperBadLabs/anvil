(ns anvil.web.views.build-page-test
  "AN4-5: build page UI renders the AN4-1 classifier's :rule and
   :explain for builds that aren't :success. Locks the badge mapping
   for the two new result classes (:neutral, :unsupported) and the
   explain banner shape."
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.web.views.build-page :as bp]))

;; Reach into the private fns through the var.
(def result-badge          @#'bp/result-badge)
(def result-explain-banner @#'bp/result-explain-banner)

;; ---------------------------------------------------------------------------
;; result-badge
;; ---------------------------------------------------------------------------

(deftest existing-result-classes-map-to-existing-badge-colors
  (is (= [:span.badge.blue "success"]
         (result-badge {:result :success})))
  (is (= [:span.badge.red "failure"]
         (result-badge {:result :failure})))
  (is (= [:span.badge.yellow "unstable"]
         (result-badge {:result :unstable})))
  (is (= [:span.badge.gray "aborted"]
         (result-badge {:result :aborted}))))

(deftest neutral-renders-gray-badge
  (is (= [:span.badge.gray "neutral"]
         (result-badge {:result :neutral}))))

(deftest unsupported-renders-amber-badge
  (is (= [:span.badge.amber "unsupported"]
         (result-badge {:result :unsupported}))))

(deftest building-renders-anim-badge
  (is (= [:span.badge.anim "running"]
         (result-badge {:building? true}))))

(deftest unknown-result-falls-through-to-dash
  (is (= [:span.badge.gray "—"]
         (result-badge {}))))

;; ---------------------------------------------------------------------------
;; result-explain-banner
;; ---------------------------------------------------------------------------

(deftest no-banner-for-successful-builds
  (is (nil? (result-explain-banner {:result :success
                                    :classify-rule :default
                                    :classify-explain "real work ran"}))))

(deftest no-banner-when-explain-missing
  (is (nil? (result-explain-banner {:result :failure
                                    :classify-rule :step-nonzero-exit
                                    :classify-explain nil}))))

(deftest failure-banner-shape
  (let [b (result-explain-banner {:result :failure
                                  :classify-rule :step-nonzero-exit
                                  :classify-explain "1 shell step(s) exited non-zero"})]
    (is (vector? b))
    (is (= :div (first b)))
    (is (= "result-banner result-banner--failure"
           (:class (second b))))
    (let [text (apply str (drop 2 b))]
      (is (re-find #"failure" text))
      (is (re-find #"step-nonzero-exit" text))
      (is (re-find #"1 shell step" text)))))

(deftest neutral-banner-uses-neutral-class
  (let [b (result-explain-banner {:result :neutral
                                  :classify-rule :no-effects-recorded
                                  :classify-explain "no shell steps ran"})]
    (is (= "result-banner result-banner--neutral"
           (:class (second b))))))

(deftest unsupported-banner-uses-amber-class
  (let [b (result-explain-banner {:result :unsupported
                                  :classify-rule :agent-unhonored
                                  :classify-explain "agent docker not honored"})]
    (is (= "result-banner result-banner--unsupported"
           (:class (second b))))))

(deftest unstable-banner-uses-unstable-class
  (let [b (result-explain-banner {:result :unstable
                                  :classify-rule :tests-failed
                                  :classify-explain "3 failures"})]
    (is (= "result-banner result-banner--unstable"
           (:class (second b))))))

(deftest aborted-banner-uses-aborted-class
  (let [b (result-explain-banner {:result :aborted
                                  :classify-rule :aborted-by-signal
                                  :classify-explain "build cancelled"})]
    (is (= "result-banner result-banner--aborted"
           (:class (second b))))))
