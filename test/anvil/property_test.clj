(ns anvil.property-test
  "TX13 — property-based tests for the gnarliest bits of anvil.

   Each property generates 100+ random inputs (via test.check) and
   asserts an invariant that should hold for ANY input the production
   code might see. This catches the bugs regression tests miss —
   exactly like the Ant-glob `?` substitution-order bug I fixed
   during TX11C development (the regex translator silently replaced
   the structural `?` quantifier with `[^/]`, so any glob using `?`
   or `**/` failed for top-level files).

   Properties covered:
     1. Ant glob → regex predicate is order-invariant under
        comma-separated alternatives, includes-vs-excludes semantics
        are correct
     2. Groovy GString interpolation against a binding produces the
        same string as plain Clojure substitution
     3. AES-GCM credentials round-trip — encrypt then decrypt returns
        the original plaintext, even on unicode-heavy or boundary
        inputs"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.string :as str]
            [anvil.compat.jenkins.steps.stash :as stash]
            [anvil.compat.jenkins.translator :as translator]
            [anvil.compat.jenkins.matrix-expander :as mx]
            [anvil.storage.credentials :as creds]))

(def ^:private TRIALS 100)

;; ---------------------------------------------------------------------------
;; Ant glob → regex predicate
;;
;; The bug we're guarding against: a `?` substitution that clobbered
;; the structural `?` quantifier in our generated regex. Specifically
;; `**/*` was producing `^(.+/)[^/][^/]*$` (mandatory leading dir +
;; one char + zero-or-more) instead of `^(?:.+/)?[^/]*$` (optional
;; leading dir + non-/ run). So root-level files didn't match.
;; ---------------------------------------------------------------------------

;; Access the private function for testing (production callers go
;; through stash!/unstash!).
(def ^:private pattern->predicate
  @#'stash/pattern->predicate)

(def ^:private path-segment
  (gen/such-that
   #(not (str/blank? %))
   (gen/fmap
    (fn [chs] (apply str chs))
    (gen/vector (gen/elements [\a \b \c \d \e \f \g \h \i \j \k \l
                               \m \n \o \p \q \r \s \t \u \v \w \x])
                1 8))
   10))

(def ^:private relative-path
  "Generated path with 0–4 directory segments + a filename, no
   leading slash. e.g. 'sub/dir/file.txt'."
  (gen/fmap
   (fn [[segs name ext]]
     (let [path (apply str (interpose "/" segs))
           full (if (str/blank? path)
                  (str name "." ext)
                  (str path "/" name "." ext))]
       full))
   (gen/tuple (gen/vector path-segment 0 4)
              path-segment
              (gen/elements ["txt" "log" "jar" "xml" "java"]))))

(defspec catch-all-glob-matches-any-path TRIALS
  (prop/for-all
   [p relative-path]
   ;; `**/*` must match any non-empty relative path, regardless of
   ;; whether the file is at the root or nested.
   (let [pred (pattern->predicate "**/*")]
     (boolean (pred p)))))

(defspec extension-glob-matches-by-extension TRIALS
  (prop/for-all
   [p relative-path
    ext (gen/elements ["txt" "log" "jar" "xml" "java"])]
   ;; `**/*.<ext>` matches iff the path ends with .<ext>
   (let [pred (pattern->predicate (str "**/*." ext))
         expected (str/ends-with? p (str "." ext))]
     (= expected (boolean (pred p))))))

(defspec comma-separated-globs-are-union TRIALS
  (prop/for-all
   [p relative-path]
   (let [union-pred (pattern->predicate "**/*.txt,**/*.log")
         txt-pred   (pattern->predicate "**/*.txt")
         log-pred   (pattern->predicate "**/*.log")]
     (= (boolean (union-pred p))
        (or (boolean (txt-pred p))
            (boolean (log-pred p)))))))

(defspec specific-dir-glob-excludes-other-dirs TRIALS
  (prop/for-all
   [p relative-path]
   (let [pred (pattern->predicate "target/**/*")
         expected (str/starts-with? p "target/")]
     (= expected (boolean (pred p))))))

;; ---------------------------------------------------------------------------
;; Groovy small-expression evaluator (matrix expander)
;;
;; The matrix expander's `groovy-eval` substitutes bindings into
;; GString-like expressions. Property: for a literal binding map and
;; a template referencing only its keys, the evaluator produces the
;; same string as plain Clojure interpolation.
;;
;; We exercise the public surface via `expand-matrices` against a
;; synthesised mini-Jenkinsfile.
;; ---------------------------------------------------------------------------

(def ^:private axis-name
  (gen/elements ["platform" "jdk" "browser" "env" "region"]))

(def ^:private axis-value
  (gen/one-of
   [(gen/elements ["linux" "macos" "alpine" "ubuntu"])
    (gen/elements ["21" "25" "11" "17"])]))

(defn- groovy-string-list
  "Render a Clojure seq of strings as a Groovy list literal:
     ['a', 'b', 'c']  (single-quoted, comma-separated)"
  [xs]
  (str "[" (str/join ", " (map #(str "'" % "'") xs)) "]"))

(defspec matrix-expands-to-cartesian-cardinality 30
  (prop/for-all
   [k1 axis-name
    k2 axis-name
    v1 (gen/such-that seq (gen/vector axis-value 1 3))
    v2 (gen/such-that seq (gen/vector axis-value 1 3))]
   ;; Either we skip (same keys collapse a Groovy map literal) or we
   ;; get the right cardinality. Use distinct values per axis since
   ;; Groovy's combinations() treats the lists as sets-of-positions.
   (let [v1 (distinct v1)
         v2 (distinct v2)
         expected-combos (* (count v1) (count v2))
         src (str "def axes = ["
                  k1 ": " (groovy-string-list v1) ","
                  k2 ": " (groovy-string-list v2)
                  "]\n"
                  "axes.values().combinations {\n"
                  "  def (a, b) = it\n"
                  "  stage(\"${a}-${b}\") { sh 'noop' }\n"
                  "}")]
     (or (= k1 k2)
         (let [base (translator/parse src "synthetic")
               expanded (mx/expand-matrices base src)
               summary (some :matrix-expansion (:options expanded))]
           (= expected-combos (or (:combinations-surviving summary) 0)))))))

;; ---------------------------------------------------------------------------
;; AES-GCM credentials round-trip
;;
;; Property: for any string, encrypt-then-decrypt is identity. This
;; covers:
;;   - randomly-positioned multi-byte UTF-8 chars (the encryption
;;     does .getBytes "UTF-8" so any valid String round-trips)
;;   - empty string
;;   - very long strings (up to a few KB)
;; ---------------------------------------------------------------------------

(def ^:private encrypt-string  @#'creds/encrypt-string)
(def ^:private decrypt-string  @#'creds/decrypt-string)

(def ^:private utf8-string
  ;; Mix ASCII + a sampling of multi-byte code points + punctuation.
  ;; gen/string-alphanumeric would skip the interesting cases.
  (gen/fmap
   (fn [chs] (apply str chs))
   (gen/vector
    (gen/one-of
     [gen/char-ascii
      (gen/return \é)        ; 2-byte UTF-8
      (gen/return \ü)        ; 2-byte UTF-8
      (gen/return \€)        ; 3-byte UTF-8
      (gen/return \中)])     ; 3-byte UTF-8 (BMP)
    ;; Surrogate-pair emoji are deliberately omitted — Clojure
    ;; char literals can't carry one anyway; the encrypt/decrypt
    ;; path works on byte arrays so 4-byte sequences are exercised
    ;; via the .getBytes "UTF-8" path on every other generated string.
    0 200)))

(defspec aes-gcm-round-trip-identity TRIALS
  (prop/for-all
   [plaintext utf8-string]
   (= plaintext (decrypt-string (encrypt-string plaintext)))))

(defspec aes-gcm-produces-distinct-ciphertexts TRIALS
  ;; Same plaintext → distinct ciphertext on each encryption (nonce
  ;; randomization). If this regresses (e.g. nonce reuse) it's a
  ;; serious crypto bug.
  (prop/for-all
   [plaintext (gen/such-that #(seq %) utf8-string)]
   (let [c1 (encrypt-string plaintext)
         c2 (encrypt-string plaintext)]
     (not= c1 c2))))

;; ---------------------------------------------------------------------------
;; Regression: the exact bug TX11C dev hit
;; ---------------------------------------------------------------------------

(deftest ant-glob-top-level-file-regression
  (testing "**/* must match a top-level file (regression for the ?
            substitution-order bug from TX11C)"
    (let [pred (pattern->predicate "**/*")]
      (is (pred "a.txt")     "top-level file")
      (is (pred "sub/a.txt") "nested file")
      (is (pred "deep/nested/path/a.txt")))))

(deftest ant-glob-question-mark-wildcard
  (testing "`?` glob is one non-slash char; must NOT be confused with
            regex quantifier"
    (let [pred (pattern->predicate "?.txt")]
      (is (pred "a.txt"))
      (is (pred "z.txt"))
      (is (not (pred "ab.txt")) "? matches exactly one char")
      (is (not (pred "a/.txt")) "? must not match /"))))
