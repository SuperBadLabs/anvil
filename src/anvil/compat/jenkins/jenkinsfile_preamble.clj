(ns anvil.compat.jenkins.jenkinsfile-preamble
  "Extract the 'preamble' from a Jenkinsfile: every top-level statement
   OUTSIDE the `pipeline { ... }` block. Real-world Jenkinsfiles
   commonly define helper functions (`boolean isDeployedBranch() { ... }`,
   `def mavenBuild(jdk, args) { ... }`) at file scope and call them from
   inside `script { ... }` blocks.

   Without the preamble threaded through to script-block compilation,
   those calls hit the Groovy Script's MissingMethodException with
   `No signature of method: JenkinsDSLScript.isDeployedBranch()` — which
   the wild-corpus matrix surfaced as one of three biggest blockers
   (apache-maven, plus contributors to others).

   Strategy: balanced-brace scan. Find the leading `pipeline {` keyword,
   match its body, splice it out. Everything that remains — `#!groovy`
   shebang, comments, top-level imports, function defs above and below
   the pipeline — becomes the preamble.

   This is intentionally string-based (not AST-walked) because:
   - 99% of real Jenkinsfiles have exactly ONE top-level `pipeline {}` and
     the rest is either comments or function defs.
   - Balanced-brace scan handles strings, single-line + block comments
     correctly via a small state machine.
   - AST-based extraction would require Groovy parser cooperation to get
     stable source-region offsets per top-level statement, which adds
     considerable complexity for marginal robustness gains.")

(defn- find-pipeline-keyword-start
  "Return the offset of the `pipeline` keyword that opens the declarative
   pipeline block, or nil if none is found. We scan past line-leading
   whitespace + `#!` lines, then look for the literal word `pipeline`
   followed by optional whitespace and `{`."
  [^String src]
  (let [m (re-matcher #"(?m)^\s*pipeline\s*\{" src)]
    (when (.find m) (.start m))))

(defn- triple-quote-here?
  [^String src ^Long i quote-char]
  (and (< (+ i 2) (.length src))
       (= (.charAt src i) quote-char)
       (= (.charAt src (inc i)) quote-char)
       (= (.charAt src (+ i 2)) quote-char)))

(defn- skip-triple-quoted [^String src ^Long i quote-char]
  ;; Returns the index AFTER the closing `\"\"\"` or `'''`.
  (let [n (.length src)]
    (loop [j (+ i 3)]
      (cond
        (>= j n) n
        (and (= (.charAt src j) \\) (< (inc j) n)) (recur (+ j 2))
        (triple-quote-here? src j quote-char) (+ j 3)
        :else (recur (inc j))))))

(defn- skip-string [^String src ^Long i quote-char]
  ;; Returns the index AFTER the closing quote of the current string
  ;; (or the matching triple-quote). Triple-quotes first: mojarra's
  ;; kubernetes-yaml block uses `yaml \"\"\"...\"\"\"` heredocs whose
  ;; bodies frequently include `{` characters — without proper triple-
  ;; quote skipping the brace-balance scanner terminates the outer
  ;; `pipeline {}` block early and the preamble ends up carrying
  ;; declarative `post { … }` blocks that don't parse at script-block
  ;; scope.
  (if (triple-quote-here? src i quote-char)
    (skip-triple-quoted src i quote-char)
    (let [n (.length src)]
      (loop [j (inc i)]
        (cond
          (>= j n) n
          (and (= (.charAt src j) \\) (< (inc j) n)) (recur (+ j 2))
          (= (.charAt src j) quote-char) (inc j)
          :else (recur (inc j)))))))

(defn- skip-line-comment [^String src ^Long i]
  (let [n (.length src)]
    (loop [j (+ i 2)]
      (cond
        (>= j n) n
        (= (.charAt src j) \newline) (inc j)
        :else (recur (inc j))))))

(defn- skip-block-comment [^String src ^Long i]
  (let [n (.length src)]
    (loop [j (+ i 2)]
      (cond
        (>= j n) n
        (and (= (.charAt src j) \*) (< (inc j) n) (= (.charAt src (inc j)) \/)) (+ j 2)
        :else (recur (inc j))))))

(defn- find-matching-brace
  "Given an offset pointing at the opening `{`, return the offset of the
   matching `}` (exclusive). Skips through strings + comments so braces
   inside them don't count toward the depth."
  [^String src ^Long open-brace-idx]
  (let [n (.length src)]
    (loop [i (inc open-brace-idx)
           depth 1]
      (cond
        (>= i n) nil

        (zero? depth) i

        :else
        (let [c (.charAt src i)]
          (cond
            (and (= c \/) (< (inc i) n)
                 (= (.charAt src (inc i)) \/))
            (recur (skip-line-comment src i) depth)

            (and (= c \/) (< (inc i) n)
                 (= (.charAt src (inc i)) \*))
            (recur (skip-block-comment src i) depth)

            (or (= c \") (= c \'))
            (recur (skip-string src i c) depth)

            (= c \{)
            (recur (inc i) (inc depth))

            (= c \})
            (if (= 1 depth)
              (inc i)
              (recur (inc i) (dec depth)))

            :else
            (recur (inc i) depth)))))))

(defn extract
  "Return the Jenkinsfile source with the top-level declarative
   `pipeline { ... }` block removed. The remainder — shebang, comments,
   top-level function defs — is suitable to prepend to a `script { ... }`
   block body before Groovy compilation.

   When no `pipeline {}` is found, returns the whole source (scripted
   Pipeline files put everything at top level; the script-block path
   isn't hit in that mode anyway, but be safe).

   When the `pipeline {}` block can't be brace-matched (unbalanced
   source, etc.), returns nil — caller falls back to body-source only."
  [^String src]
  (when (string? src)
    (if-let [start (find-pipeline-keyword-start src)]
      (let [brace-idx (.indexOf src (int \{) (int start))]
        (when (pos? brace-idx)
          (when-let [end (find-matching-brace src brace-idx)]
            (str (subs src 0 start)
                 (subs src end)))))
      ;; No `pipeline {` keyword — the whole file is preamble (scripted-
      ;; Pipeline shape) or noise. Return as-is.
      src)))
