(ns anvil.integration.github-subscriber-test
  "Tests for T3.4 — bus subscriber wiring :build-started /
   :build-done to GitHub Checks API calls."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.integration.github :as gh]
            [anvil.integration.github-subscriber :as sub]))

(use-fixtures :each
  (fn [f]
    (gh/clear-check-runs!)
    (sub/stop!)
    (try (f)
         (finally
           (sub/stop!)
           (bus/unsubscribe-all!)
           (gh/clear-check-runs!)))))

(deftest build-started-creates-check-run-when-configured
  (let [posts (atom [])
        cfg-fn (constantly {:repo "foo/bar" :checks-enabled? true})]
    (with-redefs [anvil.integration.github-subscriber/job-github-config cfg-fn
                  gh/create-check-run! (fn [params]
                                         (swap! posts conj params)
                                         {:status 201
                                          :parsed {:id 12345}})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type :build-started
                     :payload {:job-name "demo"
                               :build-number 1
                               :parameters {"GIT_COMMIT" "abc123"}}})
      (is (= 1 (count @posts)))
      (let [call (first @posts)]
        (is (= "foo/bar" (:repo call)))
        (is (= "abc123" (:head-sha call)))
        (is (= :in-progress (:status call))))
      (testing "mapping recorded for later update"
        (is (= 12345 (:check-run-id (gh/check-run-for "demo" 1))))))))

(deftest build-done-patches-check-run-conclusion
  (gh/record-check-run! "demo" 7 99999 "foo/bar")
  (let [patches (atom [])]
    (with-redefs [gh/update-check-run! (fn [params]
                                         (swap! patches conj params)
                                         {:status 200})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type :build-done
                     :payload {:job-name "demo"
                               :build-number 7
                               :result :success}})
      (is (= 1 (count @patches)))
      (let [call (first @patches)]
        (is (= 99999 (:check-run-id call)))
        (is (= :success (:conclusion call)))
        (is (= :completed (:status call)))))))

(deftest build-started-without-config-is-no-op
  (let [posts (atom [])]
    (with-redefs [anvil.integration.github-subscriber/job-github-config
                  (constantly nil)
                  gh/create-check-run! (fn [_]
                                         (swap! posts conj :should-not-fire)
                                         {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type :build-started
                     :payload {:job-name "unconfigured"
                               :build-number 1
                               :parameters {"GIT_COMMIT" "x"}}})
      (is (empty? @posts)))))

(deftest build-done-without-prior-mapping-is-no-op
  (let [patches (atom [])]
    (with-redefs [gh/update-check-run! (fn [_]
                                         (swap! patches conj :should-not-fire)
                                         {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type :build-done
                     :payload {:job-name "demo"
                               :build-number 9999
                               :result :success}})
      (is (empty? @patches)
          "no check-run was recorded for build 9999, so update is silently skipped"))))
