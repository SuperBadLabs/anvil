(ns anvil.integration.bitbucket-subscriber-test
  "Tests for T3.2 — bus subscriber wiring :build-started / :build-done
   to the Bitbucket Build Status API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anvil.events.bus :as bus]
            [anvil.events.topics :as topics]
            [anvil.integration.bitbucket :as bb]
            [anvil.integration.bitbucket-subscriber :as sub]))

(use-fixtures :each
  (fn [f]
    (bb/clear-shas!)
    (sub/stop!)
    (try (f)
         (finally
           (sub/stop!)
           (bus/unsubscribe-all!)
           (bb/clear-shas!)))))

;; ---------------------------------------------------------------------------
;; build-started
;; ---------------------------------------------------------------------------

(deftest build-started-posts-inprogress-when-configured
  (let [posts (atom [])
        fake-config {"demo" {:workspace "acme" :repo-slug "myrepo" :checks-enabled? true}}]
    (with-redefs [anvil.integration.bitbucket-subscriber/bitbucket-jobs-config
                  (constantly fake-config)
                  anvil.features/enabled? (fn [_] true)
                  bb/post-build-status! (fn [params]
                                          (swap! posts conj params)
                                          {:status 201 :body "{}"})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name "demo" :build-number 1 :sha "sha-bb"}})
      (is (= 1 (count @posts)))
      (let [call (first @posts)]
        (is (= "acme"       (:workspace call)))
        (is (= "myrepo"     (:repo-slug call)))
        (is (= "sha-bb"     (:sha call)))
        (is (= "INPROGRESS" (:state call))))
      (testing "SHA cached for later build-done lookup"
        (is (= "sha-bb" (bb/sha-for "demo" 1)))))))

(deftest build-started-without-sha-is-no-op
  (let [posts (atom [])]
    (with-redefs [anvil.integration.bitbucket-subscriber/bitbucket-jobs-config
                  (constantly {"demo" {:workspace "acme" :repo-slug "r" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] true)
                  bb/post-build-status! (fn [p] (swap! posts conj p) {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name "demo" :build-number 2}}) ; no :sha
      (is (empty? @posts)))))

(deftest build-started-without-config-is-no-op
  (let [posts (atom [])]
    (with-redefs [anvil.integration.bitbucket-subscriber/bitbucket-jobs-config
                  (constantly {})
                  anvil.features/enabled? (fn [_] true)
                  bb/post-build-status! (fn [p] (swap! posts conj p) {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name "demo" :build-number 3 :sha "x"}})
      (is (empty? @posts)))))

(deftest build-started-with-flag-off-is-no-op
  (let [posts (atom [])]
    (with-redefs [anvil.integration.bitbucket-subscriber/bitbucket-jobs-config
                  (constantly {"demo" {:workspace "w" :repo-slug "r" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] false)
                  bb/post-build-status! (fn [p] (swap! posts conj p) {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-started
                     :payload {:job-name "demo" :build-number 4 :sha "x"}})
      (is (empty? @posts)))))

;; ---------------------------------------------------------------------------
;; build-done
;; ---------------------------------------------------------------------------

(deftest build-done-posts-final-state-and-fires-evt-pr-checked
  (bb/record-sha! "demo" 5 "sha-done-bb")
  (let [posts  (atom [])
        events (atom [])]
    (with-redefs [anvil.integration.bitbucket-subscriber/bitbucket-jobs-config
                  (constantly {"demo" {:workspace "acme" :repo-slug "repo" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] true)
                  bb/post-build-status! (fn [params]
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
        (is (= "acme"        (:workspace call)))
        (is (= "repo"        (:repo-slug call)))
        (is (= "sha-done-bb" (:sha call)))
        (is (= "SUCCESSFUL"  (:state call))))
      (testing ":evt-pr-checked published on per-job topic"
        (let [ev (first (filter #(= :pr-checked (:type %)) @events))]
          (is (some? ev))
          (is (= "demo" (:job-name ev)))
          (is (= 5      (:build-number ev)))
          (is (= "SUCCESSFUL" (:state ev))))))))

(deftest build-done-maps-failure-correctly
  (bb/record-sha! "demo" 6 "sha-fail")
  (let [posts (atom [])]
    (with-redefs [anvil.integration.bitbucket-subscriber/bitbucket-jobs-config
                  (constantly {"demo" {:workspace "w" :repo-slug "r" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] true)
                  bb/post-build-status! (fn [p] (swap! posts conj p) {:status 200 :body "{}"})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-done
                     :payload {:job-name "demo" :build-number 6 :result :failure}})
      (is (= "FAILED" (:state (first @posts)))))))

(deftest build-done-without-sha-is-no-op
  (bb/clear-shas!)
  (let [posts (atom [])]
    (with-redefs [anvil.integration.bitbucket-subscriber/bitbucket-jobs-config
                  (constantly {"demo" {:workspace "w" :repo-slug "r" :checks-enabled? true}})
                  anvil.features/enabled? (fn [_] true)
                  bb/post-build-status! (fn [p] (swap! posts conj p) {})]
      (sub/start!)
      (bus/publish! topics/topic-global
                    {:type    :build-done
                     :payload {:job-name "demo" :build-number 99 :result :success}})
      (is (empty? @posts)))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(deftest stop-clears-subscription-tokens
  (sub/start!)
  (sub/stop!)
  (is (empty? @@#'anvil.integration.bitbucket-subscriber/subscription-tokens)))
