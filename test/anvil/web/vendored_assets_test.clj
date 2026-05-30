(ns anvil.web.vendored-assets-test
  "Integrity check for vendored browser assets (AU9).

   Doctrine: anvil ships single-binary, offline. Every vendored asset
   has a SHA-256 receipt in resources/public/vendor/VENDORED.txt. If
   the on-disk file's hash drifts from the receipt — accidental edit,
   bad upgrade, corruption — this test fails loudly.

   Also covers the route: GET /public/vendor/<file> serves bytes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.web.routes :as routes])
  (:import (java.security MessageDigest)))

(defn- sha256-hex [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")
        digest (.digest md bs)]
    (apply str (map #(format "%02x" %) digest))))

(defn- bytes-of [resource-path]
  (with-open [in (io/input-stream (io/resource resource-path))]
    (.readAllBytes in)))

(def ^:private receipt-path "public/vendor/VENDORED.txt")

(defn- parse-receipt
  "Parse VENDORED.txt into [{:name 'htmx.min.js' :sha256 '…'} ...]."
  [text]
  (let [blocks (->> (str/split text #"\n--+\n")
                    (drop 1))]
    (for [block blocks
          :let [lines (str/split-lines block)
                name-line (->> lines (filter seq) first)
                sha (some (fn [l]
                            (when-let [m (re-find #"sha256:\s+([0-9a-f]+)" l)]
                              (second m)))
                          lines)]
          :when (and (string? name-line) sha)]
      {:name (str/trim name-line) :sha256 sha})))

(deftest receipt-file-exists
  (is (some? (io/resource receipt-path))
      "resources/public/vendor/VENDORED.txt must exist"))

(deftest every-vendored-file-matches-its-receipt
  (let [receipt-text (slurp (io/resource receipt-path))
        entries (parse-receipt receipt-text)]
    (is (seq entries) "VENDORED.txt parses to at least one entry")
    (doseq [{:keys [name sha256]} entries]
      (testing (str name)
        (let [path (str "public/vendor/" name)
              res  (io/resource path)]
          (is res (str "resource missing: " path))
          (when res
            (let [actual (sha256-hex (bytes-of path))]
              (is (= sha256 actual)
                  (str name ": on-disk SHA-256 (" actual
                       ") does not match receipt (" sha256 "). "
                       "Either restore the original file or update VENDORED.txt.")))))))))

(deftest public-route-serves-vendored-assets
  (let [h (routes/make-handler)
        resp (h {:request-method :get :uri "/public/vendor/htmx.min.js"})]
    (is (= 200 (:status resp)))
    (is (some? (:body resp)))))

(deftest public-route-is-not-a-directory-traversal-hole
  ;; ring's create-resource-handler is rooted at the classpath /public
  ;; prefix — but verify that obvious traversal attempts get a 404,
  ;; not /etc/passwd.
  (let [h (routes/make-handler)]
    (doseq [bad ["/public/../../../etc/passwd"
                 "/public/../project.clj"]]
      (let [resp (h {:request-method :get :uri bad})]
        (is (not= 200 (:status resp))
            (str "traversal attempt should NOT 200: " bad))))))
