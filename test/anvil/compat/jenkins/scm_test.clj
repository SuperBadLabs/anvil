(ns anvil.compat.jenkins.scm-test
  "End-to-end SCM provisioning test — actually shells out to git.
   Uses anvil itself as the clone target via the local /var/lib/anvil/source
   bare clone when present; falls back to the public GitHub remote
   otherwise (network test). Skip both via SKIP_NETWORK_TESTS env var."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [anvil.compat.jenkins.scm :as scm]))

(def ^:private test-repo
  ;; Tiny public repo used as a clone target — fast clone, stable.
  "https://github.com/SuperBadLabs/chengis-core.git")

(defn- skip-net? []
  (some? (System/getenv "SKIP_NETWORK_TESTS")))

(defn- tmp-workspace []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "anvil-scm-test-" (System/currentTimeMillis)))]
    (.mkdirs d) d))

(deftest provision-clones-into-empty-workspace
  (when-not (skip-net?)
    (testing "first call into an empty workspace fetches via git clone"
      (let [ws (tmp-workspace)]
        (try
          (let [r (scm/provision! ws {:type :git :url test-repo :branch "master"})]
            (is (= :cloned (:result r)) (str "provision: " r))
            (is (.exists (io/file ws ".git")) ".git should exist")
            (is (.exists (io/file ws "project.clj")) "chengis-core has project.clj")
            (is (string? (:sha r)))
            (is (= 40 (count (:sha r))) "full sha"))
          (finally
            (try (fs/delete-tree (str ws)) (catch Exception _ nil))))))))

(deftest provision-refreshes-existing-checkout
  (when-not (skip-net?)
    (testing "second call into a populated workspace fast-fetches instead of re-cloning"
      (let [ws (tmp-workspace)]
        (try
          (scm/provision! ws {:type :git :url test-repo :branch "master"})
          (let [r (scm/provision! ws {:type :git :url test-repo :branch "master"})]
            (is (= :refreshed (:result r)) (str "second provision: " r))
            (is (string? (:sha r))))
          (finally
            (try (fs/delete-tree (str ws)) (catch Exception _ nil))))))))

(deftest provision-without-url-skips
  (testing ":skipped when scm has no url"
    (let [ws (tmp-workspace)]
      (try
        (let [r (scm/provision! ws {:type :git :url nil :branch "master"})]
          (is (= :skipped (:result r))))
        (finally
          (try (fs/delete-tree (str ws)) (catch Exception _ nil)))))))

(deftest provision-bad-url-records-failure
  (when-not (skip-net?)
    (testing "unreachable URL produces :failed without throwing"
      (let [ws (tmp-workspace)]
        (try
          (let [r (scm/provision! ws {:type :git
                                      :url "https://example.invalid/no-such-repo.git"
                                      :branch "master"})]
            (is (= :failed (:result r)))
            (is (string? (:error r))))
          (finally
            (try (fs/delete-tree (str ws)) (catch Exception _ nil))))))))

(deftest provision-unsupported-vcs-type
  (testing "non-git :type returns :unsupported-scm-type"
    (let [ws (tmp-workspace)]
      (try
        (let [r (scm/provision! ws {:type :hg :url "x" :branch "default"})]
          (is (= :unsupported-scm-type (:result r))))
        (finally
          (try (fs/delete-tree (str ws)) (catch Exception _ nil)))))))
