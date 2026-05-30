(ns anvil.web.ansi-test
  "Tests for the ANSI SGR → HTML colorizer.

   Plain old text in / well-formed HTML out. Real-world samples from
   mvn / npm / cargo / gradle are below the unit cases."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [anvil.web.ansi :as ansi]))

(def ^:private ESC "")

(defn- sgr [& nums]
  (str ESC "[" (str/join ";" nums) "m"))

(deftest plain-text-roundtrips-as-escaped-html
  (is (= "hello" (ansi/ansi->html "hello")))
  (is (= "&lt;tag&gt;" (ansi/ansi->html "<tag>"))
      "HTML special characters always escaped"))

(deftest single-color-wraps-in-span
  (let [out (ansi/ansi->html (str (sgr 31) "boom" (sgr 0)))]
    (is (= "<span class=\"ansi-fg-red\">boom</span>" out))))

(deftest color-then-style-merges-classes
  (let [out (ansi/ansi->html (str (sgr 31) (sgr 1) "x" (sgr 0)))]
    (is (str/includes? out "ansi-fg-red"))
    (is (str/includes? out "ansi-bold"))))

(deftest empty-sgr-resets
  (let [out (ansi/ansi->html (str (sgr 31) "a" ESC "[m" "b"))]
    (is (str/includes? out "<span class=\"ansi-fg-red\">a</span>"))
    (is (str/includes? out "b")
        "after ESC[m, no span open, 'b' is plain")))

(deftest bright-colors-recognized
  (let [out (ansi/ansi->html (str (sgr 91) "x" (sgr 0)))]
    (is (str/includes? out "ansi-fg-brred"))))

(deftest background-colors-recognized
  (let [out (ansi/ansi->html (str (sgr 41) "x" (sgr 0)))]
    (is (str/includes? out "ansi-bg-red"))))

(deftest style-reset-with-22-keeps-color
  (let [out (ansi/ansi->html (str (sgr 31 1) "bolded" (sgr 22) "plain" (sgr 0)))]
    (is (str/includes? out "ansi-bold")
        "bolded segment carries both classes")
    (is (str/includes? out "ansi-fg-red\">plain")
        "after 22, bold is dropped but red persists")))

(deftest unknown-codes-tolerated
  (testing "256-color sequences are NOT crashed; just skipped"
    (let [out (ansi/ansi->html (str ESC "[38;5;208m" "orange" (sgr 0)))]
      (is (string? out) "doesn't throw")
      ;; The 38 + 5 + 208 codes get applied in our state-machine but
      ;; 38, 5, 208 aren't in fg-class, so no class is produced —
      ;; the text survives, plain.
      (is (str/includes? out "orange")))))

(deftest non-sgr-csi-dropped
  (testing "ESC[K (erase to end of line) and ESC[2J (clear screen) dropped"
    (let [out (ansi/ansi->html (str "before" ESC "[K" "after"))]
      (is (= "beforeafter" out)))
    (let [out (ansi/ansi->html (str "x" ESC "[2J" "y"))]
      (is (= "xy" out)))))

(deftest unterminated-color-still-closes-span-at-end
  (let [out (ansi/ansi->html (str (sgr 31) "no-reset"))]
    (is (str/ends-with? out "</span>")
        "the function emits well-formed HTML even when input doesn't reset")))

(deftest html-escape-protects-inside-and-outside-spans
  (let [out (ansi/ansi->html (str "<a>" (sgr 31) "<b>" (sgr 0) "<c>"))]
    (is (str/includes? out "&lt;a&gt;"))
    (is (str/includes? out "&lt;b&gt;"))
    (is (str/includes? out "&lt;c&gt;"))
    (is (not (str/includes? out "<a>")))))

(deftest strip-ansi-removes-everything
  (is (= "hello" (ansi/strip-ansi (str (sgr 31) "hello" (sgr 0)))))
  (is (= "abc" (ansi/strip-ansi (str ESC "[1;31m" "a" ESC "[K" "b" ESC "[m" "c"))))
  (is (= "plain" (ansi/strip-ansi "plain"))))

;; ===========================================================================
;; Real-world samples — strings copy-pasted from actual tool runs.
;; ===========================================================================

(deftest npm-error-line-renders
  ;; npm prefixes errors with `npm ERR!` and emits bold-red:
  ;;   \e[31mnpm\e[39m \e[1mERR!\e[22m\e[39m code ENOENT
  (let [s (str ESC "[31mnpm" ESC "[39m " ESC "[1mERR!" ESC "[22m" ESC "[39m code ENOENT")
        out (ansi/ansi->html s)]
    (is (str/includes? out "ansi-fg-red\">npm"))
    (is (str/includes? out "ansi-bold\">ERR!"))
    (is (str/includes? out "code ENOENT"))))

(deftest cargo-warning-line-renders
  ;; cargo emits bold-yellow `warning:` prefix.
  ;;   \e[1;33mwarning\e[0m\e[1m: unused variable `x`\e[0m
  (let [s (str ESC "[1;33mwarning" ESC "[0m" ESC "[1m: unused variable `x`" ESC "[0m")
        out (ansi/ansi->html s)]
    (is (str/includes? out "ansi-fg-yellow"))
    (is (str/includes? out "ansi-bold"))
    (is (str/includes? out "unused variable `x`"))))

(deftest gradle-success-line-renders
  ;; gradle's `BUILD SUCCESSFUL` is bold green.
  ;;   \e[1;32mBUILD SUCCESSFUL\e[0m in 2s
  (let [s (str ESC "[1;32mBUILD SUCCESSFUL" ESC "[0m in 2s")
        out (ansi/ansi->html s)]
    (is (str/includes? out "ansi-fg-green ansi-bold"))
    (is (str/includes? out "BUILD SUCCESSFUL"))
    (is (str/includes? out "in 2s"))))
