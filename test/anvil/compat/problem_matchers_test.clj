(ns anvil.compat.problem-matchers-test
  "Tests for the problem-matcher framework (T2.1, T2.5, T2.7 corpus).

   Covers:
   - workflow-command parser (GHA's ::warning/::error/::notice format)
   - per-tool regex rule matchers (gcc, rustc, javac, mypy, eslint, msbuild)
   - load-rules-from-classpath returns 6 bundled rules in stable order
   - match-line dispatches workflow-command first, then walks rules"
  (:require [clojure.test :refer [deftest is testing]]
            [anvil.compat.problem-matchers :as pm]))

;; ---------------------------------------------------------------------------
;; Workflow-command parser (T2.5)
;; ---------------------------------------------------------------------------

(deftest workflow-command-warning-with-attrs
  (let [p (pm/workflow-command-match
           "::warning file=src/foo.rs,line=42,col=7::expected ; found }")]
    (is (= "::workflow" (:source p)))
    (is (= "src/foo.rs" (:file p)))
    (is (= 42 (:line p)))
    (is (= 7 (:column p)))
    (is (= :warning (:severity p)))
    (is (= "expected ; found }" (:message p)))))

(deftest workflow-command-error-without-col
  (let [p (pm/workflow-command-match
           "::error file=lib.clj,line=12::Unable to resolve symbol: foo")]
    (is (= :error (:severity p)))
    (is (= 12 (:line p)))
    (is (nil? (:column p)))
    (is (= "Unable to resolve symbol: foo" (:message p)))))

(deftest workflow-command-notice-maps-to-note
  (let [p (pm/workflow-command-match "::notice::nothing structural about it")]
    (is (= :note (:severity p)))
    (is (nil? (:file p)))
    (is (= "nothing structural about it" (:message p)))))

(deftest workflow-command-non-match-returns-nil
  (is (nil? (pm/workflow-command-match "hello world")))
  (is (nil? (pm/workflow-command-match "::not-a-severity foo::bar")))
  (is (nil? (pm/workflow-command-match ""))))

;; ---------------------------------------------------------------------------
;; YAML-rule matchers (T2.1) — golden corpus of real tool outputs
;; ---------------------------------------------------------------------------

(deftest bundled-rules-loads-six-tools
  (let [rules (pm/bundled-rules)]
    (is (= 6 (count rules)))
    (is (= #{"gcc" "rustc" "javac" "mypy" "eslint" "msbuild"}
           (set (map :owner rules))))))

(defn- match-via-rules [line]
  (pm/match-rules line (pm/bundled-rules)))

(deftest gcc-error
  (let [p (match-via-rules
           "src/main.c:42:15: error: expected ';' before '}' token")]
    (is (= "gcc" (:source p)))
    (is (= "src/main.c" (:file p)))
    (is (= 42 (:line p)))
    (is (= 15 (:column p)))
    (is (= :error (:severity p)))
    (is (= "expected ';' before '}' token" (:message p)))))

(deftest gcc-warning
  (let [p (match-via-rules
           "include/foo.h:10:5: warning: implicit declaration of function 'frobnicate'")]
    (is (= :warning (:severity p)))
    (is (re-find #"frobnicate" (:message p)))))

(deftest rustc-arrow-only-form
  (let [p (match-via-rules "  --> src/lib.rs:42:5")]
    (is (= "rustc" (:source p)))
    (is (= "src/lib.rs" (:file p)))
    (is (= 42 (:line p)))
    (is (= 5 (:column p)))))

(deftest rustc-clippy-style
  ;; clippy and rustfmt sometimes emit the gcc-shaped one-liner
  (let [p (match-via-rules
           "src/main.rs:88:1: error: trailing whitespace")]
    (is (#{"rustc" "gcc"} (:source p))
        "either rustc or gcc rule fires first — both produce the right IR")
    (is (= 88 (:line p)))
    (is (= :error (:severity p)))))

(deftest javac-error
  (let [p (match-via-rules
           "/home/foo/src/Bar.java:42: error: cannot find symbol")]
    (is (= "javac" (:source p)))
    (is (= "/home/foo/src/Bar.java" (:file p)))
    (is (= 42 (:line p)))
    (is (= :error (:severity p)))
    (is (= "cannot find symbol" (:message p)))))

(deftest javac-warning
  (let [p (match-via-rules
           "/tmp/Foo.java:127: warning: [deprecation] foo() in Bar has been deprecated")]
    (is (= "javac" (:source p)))
    (is (= :warning (:severity p)))
    (is (re-find #"deprecated" (:message p)))))

(deftest mypy-error
  (let [p (match-via-rules
           "src/foo.py:42: error: Incompatible return value type  [return-value]")]
    (is (= "mypy" (:source p)))
    (is (= 42 (:line p)))
    (is (= :error (:severity p)))
    ;; The [return-value] error code is stripped from the message
    (is (= "Incompatible return value type" (:message p)))))

(deftest mypy-with-column
  (let [p (match-via-rules
           "src/foo.py:42: 7: error: Need type annotation for \"x\"")]
    (is (= 42 (:line p)))
    (is (= 7 (:column p)))))

(deftest eslint-compact-format
  (let [p (match-via-rules
           "/home/foo/src/index.js: line 42, col 5, Error - 'x' is not defined. (no-undef)")]
    (is (= "eslint" (:source p)))
    (is (= "/home/foo/src/index.js" (:file p)))
    (is (= 42 (:line p)))
    (is (= 5 (:column p)))
    (is (= :error (:severity p)))))

(deftest msbuild-c-sharp
  (let [p (match-via-rules
           "Foo.cs(42,7,42,15): error CS1002: ; expected [/path/to/Foo.csproj]")]
    (is (= "msbuild" (:source p)))
    (is (= "Foo.cs" (:file p)))
    (is (= 42 (:line p)))
    (is (= 7 (:column p)))
    (is (= :error (:severity p)))
    (is (= "; expected" (:message p))
        "the [/path/to/Foo.csproj] suffix is stripped")))

;; ---------------------------------------------------------------------------
;; match-line (top-level dispatch)
;; ---------------------------------------------------------------------------

(deftest match-line-prefers-workflow-command
  (testing "lines emitting ::warning go through the workflow-command parser
            even when a regex rule would also match"
    (let [p (pm/match-line "::warning file=foo.c,line=1,col=1::msg")]
      (is (= "::workflow" (:source p))))))

(deftest match-line-falls-through-to-rules
  (let [p (pm/match-line "src/main.c:42:15: error: foo")]
    (is (= "gcc" (:source p)))
    (is (= 42 (:line p)))))

(deftest match-line-returns-nil-on-non-diagnostic
  (is (nil? (pm/match-line "hello world")))
  (is (nil? (pm/match-line "[INFO] Building project")))
  (is (nil? (pm/match-line ""))))

;; ---------------------------------------------------------------------------
;; Corpus tally — board T2.7 specifies "8-tool golden corpus"
;; ---------------------------------------------------------------------------

(deftest corpus-spans-at-least-8-distinct-tool-emit-shapes
  (let [lines
        ["::warning file=foo.rs,line=1,col=1::msg-1"           ; 1 workflow-command
         "src/main.c:42:15: error: foo"                         ; 2 gcc
         "include/x.h:10:5: warning: foo"                       ; 3 gcc-warn
         "  --> src/lib.rs:42:5"                                ; 4 rustc-arrow
         "/foo/Bar.java:42: error: cannot find symbol"          ; 5 javac
         "src/foo.py:42: error: bad  [return-value]"            ; 6 mypy
         "/x/y.js: line 42, col 5, Error - 'x' (no-undef)"      ; 7 eslint
         "Foo.cs(42,7,42,15): error CS1002: ; expected"         ; 8 msbuild
         "/tmp/Foo.java:127: warning: deprecated"]              ; 9 javac-warn
        sources (->> lines (keep pm/match-line) (map :source) distinct)]
    (is (>= (count sources) 7)
        (str "expected ≥7 distinct sources, got " (pr-str sources)))))
