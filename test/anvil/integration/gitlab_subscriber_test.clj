(ns anvil.integration.gitlab-subscriber-test
  "Tests for T3.1 — bus subscriber wiring :build-started / :build-done
   to the GitLab Commit Status API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.integration.gitlab :as gl]
            [anvil.integration.gitlab-subscriber :as sub]))

(use-fixtures :each
  (fn [f]
    (gl/clear-shas!)
    (sub/stop!)
    (try (f)
         (finally
           (sub/stop!)
           (bus/unsubscribe-all!)
           (gl/clear-shas!)))))

;; ---------------------------------------------------------------------------
;; build-started
;; ---------------------------------------------------------------------------

(deftest build-started-posts-running-when-configured
  (let [posts (atom [])
        fake-config {"demo" {:project-id "42" :checks-enabled? true}}]
    (with-redefs [anvil.integration.gitlab-subscriber/gitlab-jobs-config
                  (constantly fake-config)
                  anvil.features/enabled? (fn [_] true)
                  gl/post-commit-status! (fn [params]
                                           (swap! posts conj params)
                                           {:status 201 :body "{}"})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name "demo" :build-number 1 :sha "abc123"}})
      (is (= 1 (count @posts)))
      (let [call (first @posts)]
        (is (= "42"      (:project-id call)))
        (is (= "abc123"  (:sha call)))
        (is (= "running" (:state call))))
      (testing "SHA is cached for on-build-done lookup"
        (is (= "abc123" (gl/sha-for "demo" 1)))))))

(deftest build-started-without-sha-is-no-op
  (let [posts (atom [])]
    (with-redefs [anvil.integration.gitlab-subscriber/gitlab-jobs-config
                  (constantly {"demo" {:project-id "1" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] true)
                  gl/post-commit-status! (fn [p] (swap! posts conj p) {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name "demo" :build-number 2}}) ; no :sha
      (is (empty? @posts) "no SHA → no post"))))

(deftest build-started-without-config-is-no-op
  (let [posts (atom [])]
    (with-redefs [anvil.integration.gitlab-subscriber/gitlab-jobs-config
                  (constantly {}) ; no entry for demo
                  anvil.features/enabled? (fn [_] true)
                  gl/post-commit-status! (fn [p] (swap! posts conj p) {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name "demo" :build-number 3 :sha "x"}})
      (is (empty? @posts) "unconfigured job → no post"))))

(deftest build-started-with-flag-off-is-no-op
  (let [posts (atom [])]
    (with-redefs [anvil.integration.gitlab-subscriber/gitlab-jobs-config
                  (constantly {"demo" {:project-id "1" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] false) ; flag off
                  gl/post-commit-status! (fn [p] (swap! posts conj p) {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name "demo" :build-number 4 :sha "x"}})
      (is (empty? @posts) "feature flag off → no post"))))

;; ---------------------------------------------------------------------------
;; build-done
;; ---------------------------------------------------------------------------

(deftest build-done-posts-final-state-and-fires-evt-mr-checked
  (gl/record-sha! "demo" 5 "sha999")
  (let [posts  (atom [])
        events (atom [])]
    (with-redefs [anvil.integration.gitlab-subscriber/gitlab-jobs-config
                  (constantly {"demo" {:project-id "7" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] true)
                  gl/post-commit-status! (fn [params]
                                           (swap! posts conj params)
                                           {:status 200 :body "{}"})]
      (bus/subscribe! (topics/topic-job "demo")
                      (fn [ev] (swap! events conj ev)))
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-done
                     :payload {:job-name "demo" :build-number 5 :result :success}})
      (is (= 1 (count @posts)))
      (let [call (first @posts)]
        (is (= "7"       (:project-id call)))
        (is (= "sha999"  (:sha call)))
        (is (= "success" (:state call))))
      (testing ":evt-mr-checked event published on per-job topic"
        (let [ev (first (filter #(= :mr-checked (:type %)) @events))]
          (is (some? ev))
          (is (= "demo" (:job-name ev)))
          (is (= 5      (:build-number ev)))
          (is (= "success" (:state ev))))))))

(deftest build-done-uses-sha-from-payload-when-present
  (let [posts (atom [])]
    (with-redefs [anvil.integration.gitlab-subscriber/gitlab-jobs-config
                  (constantly {"demo" {:project-id "1" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] true)
                  gl/post-commit-status! (fn [p] (swap! posts conj p) {:status 200 :body "{}"})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-done
                     :payload {:job-name "demo" :build-number 10
                               :sha      "payload-sha" :result :failure}})
      (is (= "payload-sha" (:sha (first @posts)))))))

(deftest build-done-without-sha-is-no-op
  (gl/clear-shas!) ; ensure no cached SHA either
  (let [posts (atom [])]
    (with-redefs [anvil.integration.gitlab-subscriber/gitlab-jobs-config
                  (constantly {"demo" {:project-id "1" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] true)
                  gl/post-commit-status! (fn [p] (swap! posts conj p) {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-done
                     :payload {:job-name "demo" :build-number 99 :result :success}})
      (is (empty? @posts) "no SHA (payload or cache) → no post"))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(deftest stop-clears-subscription-tokens
  (sub/start!)
  (sub/stop!)
  (is (empty? @@#'anvil.integration.gitlab-subscriber/subscription-tokens)))
