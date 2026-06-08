(ns anvil.cache.lookup-test
  "v0.5 T1.3 — lookup facade tests.

   The facade sits over anvil.cache.local-store; tests verify:
     - hit?/fetch/record! round-trip against a temp store
     - fetch returns nil on miss (not throwing)
     - record! swallows errors (logs + returns nil)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [babashka.fs :as fs]
            [anvil.cache.lookup :as lookup]))

(def ^:dynamic *tmp-root* nil)

(use-fixtures :each
  (fn [f]
    (let [d (fs/create-temp-dir {:prefix "anvil-lookup-test-"})]
      (try
        (binding [*tmp-root* (str d)] (f))
        (finally (fs/delete-tree d))))))

(defn- opts [] {:store-root *tmp-root*})

(def test-key
  "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")

;; ---------------------------------------------------------------------------
;; hit? / fetch round-trip
;; ---------------------------------------------------------------------------

(deftest miss-returns-nil-and-false
  (is (false? (lookup/hit? (opts) test-key)))
  (is (nil?   (lookup/fetch (opts) test-key))))

(deftest record-then-hit
  (lookup/record! (opts) test-key
                  {:stdout "hello\n" :stderr "" :exit 0
                   :wall-ms 500 :image-digest "sha256:abc"
                   :command "echo hello" :now 1000})
  (is (true? (lookup/hit? (opts) test-key)))
  (let [r (lookup/fetch (opts) test-key)]
    (is (= "hello\n" (:stdout r)))
    (is (= 0         (:exit r)))
    (is (= 500       (:wall-ms r)))
    (is (= "sha256:abc" (:image-digest r)))))

;; ---------------------------------------------------------------------------
;; Error-swallowing contract
;; ---------------------------------------------------------------------------

(deftest fetch-on-corrupted-entry-returns-nil
  ;; Write a meta.edn with unclosed braces so edn/read-string throws
  (let [entry-dir (clojure.java.io/file *tmp-root* "ab" test-key)]
    (.mkdirs entry-dir)
    (spit (clojure.java.io/file entry-dir "exit") "0")
    (spit (clojure.java.io/file entry-dir "meta.edn") "{:bad-key UNCLOSED-MAP"))
  ;; fetch should swallow the exception and return nil
  (is (nil? (lookup/fetch (opts) test-key))
      "fetch tolerates a corrupt meta.edn (unclosed-map EDN throws)"))

(deftest record-with-bad-opts-returns-nil
  ;; Pass a store-root that is a file, not a directory, to trigger an I/O error
  (let [tmp-file (fs/create-temp-file {:prefix "anvil-bad-root-"})]
    (try
      (let [bad-opts {:store-root (str tmp-file "/not-a-dir")}
            r (lookup/record! bad-opts test-key {:exit 0 :now 1000})]
        (is (nil? r) "record! returns nil on I/O error"))
      (finally (fs/delete tmp-file)))))
