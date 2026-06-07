(ns anvil.tools.dockerfile-test
  "Tests for v0.4 AN6-3 dockerfile-agent image-tag computation +
   ensure-image! flow.

   Hermetic — never invokes the docker daemon. The :execute? false
   branch of `build-image!` and `ensure-image!` returns a synthetic
   {:cached? true :recorded-only? true} which the dispatcher can
   thread through unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [anvil.tools.dockerfile :as df]))

(defn- temp-workspace
  "Make a temp directory containing the named (filename → content)
   pairs. Returns the workspace File."
  [files]
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "anvil-df-test-"
                    (into-array java.nio.file.attribute.FileAttribute [])))]
    (doseq [[filename content] files]
      (let [f (io/file d filename)]
        (.mkdirs (.getParentFile f))
        (spit f content)))
    d))

;; ---------------------------------------------------------------------------
;; sha256
;; ---------------------------------------------------------------------------

(deftest sha256-string
  (testing "SHA-256 of a known string"
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (df/sha256 "abc")))))

(deftest sha256-deterministic
  (testing "same input → same digest"
    (is (= (df/sha256 "hello") (df/sha256 "hello")))))

;; ---------------------------------------------------------------------------
;; dockerfile-image-tag
;; ---------------------------------------------------------------------------

(deftest tag-shape
  (testing "image tag is anvil-dockerfile:<16-char-hex>"
    (let [ws (temp-workspace {"Dockerfile" "FROM scratch\nCMD true\n"})
          tag (df/dockerfile-image-tag ws "Dockerfile")]
      (is (some? tag))
      (is (str/starts-with? tag "anvil-dockerfile:"))
      (let [hex (subs tag (count "anvil-dockerfile:"))]
        (is (= 16 (count hex)))
        (is (re-matches #"[0-9a-f]+" hex))))))

(deftest tag-changes-when-dockerfile-changes
  (testing "different Dockerfile content → different tag"
    (let [ws-a (temp-workspace {"Dockerfile" "FROM scratch\nCMD a\n"})
          ws-b (temp-workspace {"Dockerfile" "FROM scratch\nCMD b\n"})]
      (is (not= (df/dockerfile-image-tag ws-a "Dockerfile")
                (df/dockerfile-image-tag ws-b "Dockerfile"))))))

(deftest tag-changes-when-copy-source-changes
  (testing "Dockerfile identical but COPY'd file content differs → different tag"
    (let [df-body "FROM scratch\nCOPY app.jar /app/\nCMD [\"app\"]\n"
          ws-a (temp-workspace {"Dockerfile" df-body
                                "app.jar" "binary-content-A"})
          ws-b (temp-workspace {"Dockerfile" df-body
                                "app.jar" "binary-content-B"})]
      (is (not= (df/dockerfile-image-tag ws-a "Dockerfile")
                (df/dockerfile-image-tag ws-b "Dockerfile"))
          "cache-key includes named COPY sources — content change busts the tag"))))

(deftest tag-stable-when-copy-source-unrelated
  (testing "files NOT referenced by COPY/ADD don't affect the tag"
    (let [df-body "FROM scratch\nCMD true\n"
          ws-a (temp-workspace {"Dockerfile" df-body})
          ws-b (temp-workspace {"Dockerfile" df-body
                                "README.md" "unrelated"})]
      (is (= (df/dockerfile-image-tag ws-a "Dockerfile")
             (df/dockerfile-image-tag ws-b "Dockerfile"))))))

(deftest tag-nil-when-dockerfile-missing
  (testing "absent Dockerfile → nil tag (caller surfaces honest build failure)"
    (let [ws (temp-workspace {})]
      (is (nil? (df/dockerfile-image-tag ws "Dockerfile"))))))

(deftest tag-respects-flagged-copy-source-syntax
  (testing "COPY --chown=root:root src dst — flag tokens ignored, sources extracted"
    (let [ws (temp-workspace
              {"Dockerfile"
               "FROM alpine\nCOPY --chown=root:root src/app /app/\nCMD [\"/app\"]\n"
               "src/app" "binary"})
          tag1 (df/dockerfile-image-tag ws "Dockerfile")
          ;; Mutate the source — tag should change (proving it was hashed)
          _ (spit (io/file ws "src/app") "binary-2")
          tag2 (df/dockerfile-image-tag ws "Dockerfile")]
      (is (not= tag1 tag2)
          "COPY source was hashed despite --chown= flag in the line"))))

;; ---------------------------------------------------------------------------
;; ensure-image!
;; ---------------------------------------------------------------------------

(deftest ensure-image-record-only-mode
  (testing "with execute? false → returns synthetic {:cached? true :recorded-only? true}"
    (let [ws (temp-workspace {"Dockerfile" "FROM scratch\nCMD true\n"})
          r (df/ensure-image! ws "Dockerfile" {:execute? false})]
      (is (= 0 (:exit r)))
      (is (true? (:cached? r)))
      (is (true? (:recorded-only? r)))
      (is (str/starts-with? (:tag r) "anvil-dockerfile:")))))

(deftest ensure-image-missing-dockerfile
  (testing "absent Dockerfile → :missing-dockerfile? true with non-zero exit"
    (let [ws (temp-workspace {})
          r (df/ensure-image! ws "Dockerfile" {:execute? false})]
      (is (true? (:missing-dockerfile? r)))
      (is (nil? (:tag r)))
      (is (not (zero? (:exit r)))))))

(deftest ensure-image-execute-honors-image-exists
  (testing "with execute? true and the image already in the local store → cached, no docker build call"
    (let [ws (temp-workspace {"Dockerfile" "FROM scratch\nCMD true\n"})
          build-calls (atom 0)]
      (with-redefs [df/image-exists? (fn [_tag] true)
                    df/build-image! (fn [& _]
                                      (swap! build-calls inc)
                                      {:exit 0 :cached? false})]
        (let [r (df/ensure-image! ws "Dockerfile" {:execute? true})]
          (is (true? (:cached? r)))
          (is (= 0 @build-calls)
              "build-image! never called when image-exists? says yes"))))))

(deftest ensure-image-execute-invokes-build-when-missing
  (testing "with execute? true and image absent → build-image! invoked once"
    (let [ws (temp-workspace {"Dockerfile" "FROM scratch\nCMD true\n"})
          build-calls (atom [])]
      (with-redefs [df/image-exists? (fn [_tag] false)
                    df/build-image! (fn [w f tag _opts]
                                      (swap! build-calls conj [w f tag])
                                      {:tag tag :exit 0 :cached? false})]
        (let [r (df/ensure-image! ws "Dockerfile" {:execute? true})]
          (is (false? (:cached? r)))
          (is (= 0 (:exit r)))
          (is (= 1 (count @build-calls)))
          (is (str/starts-with? (last (first @build-calls)) "anvil-dockerfile:")))))))
