(ns anvil.web.ansi
  "Clean-room ANSI SGR (Select Graphic Rendition) → HTML colorizer
   for the console-tail view (TU2.3).

   Covers what `mvn`/`npm`/`cargo`/`gradle`/`ls -l --color`/`grep
   --color` actually emit:

     - 30-37  foreground basic colors
     - 90-97  foreground bright colors
     - 40-47  background basic colors
     - 100-107 background bright colors
     - 1      bold
     - 2      faint / dim
     - 3      italic
     - 4      underline
     - 9      strikethrough
     - 22, 23, 24, 29  reset for the corresponding style
     - 39     default foreground
     - 49     default background
     - 0 / empty     reset all
     - ESC[K, ESC[2J, etc.  (cursor / clear commands — silently dropped)

   Intentionally NOT covered in v1:
     - 256-color (ESC[38;5;Nm), 24-bit truecolor (ESC[38;2;R;G;Bm) —
       rare in CI output, can be added under a `:full-color?` opt
     - Cursor positioning ESC[<row>;<col>H — meaningless in a log
     - Blink (5), reverse (7), conceal (8) — almost no CI tool emits

   Output is HTML — caller is responsible for h/raw-wrapping the
   result and surrounding it with <pre class=\"console\">.

   Tested against samples in anvil.web.ansi-test."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; SGR code → CSS class
;;
;; We emit class names instead of inline `style` attributes so the
;; tokens defined in layout.clj (:root --console-fg etc.) can dark-
;; mode themselves.
;; ---------------------------------------------------------------------------

(def ^:private fg-class
  {30 "ansi-fg-black"    31 "ansi-fg-red"     32 "ansi-fg-green"   33 "ansi-fg-yellow"
   34 "ansi-fg-blue"     35 "ansi-fg-magenta" 36 "ansi-fg-cyan"    37 "ansi-fg-white"
   90 "ansi-fg-brblack"  91 "ansi-fg-brred"   92 "ansi-fg-brgreen" 93 "ansi-fg-bryellow"
   94 "ansi-fg-brblue"   95 "ansi-fg-brmagenta" 96 "ansi-fg-brcyan" 97 "ansi-fg-brwhite"})

(def ^:private bg-class
  {40 "ansi-bg-black"    41 "ansi-bg-red"     42 "ansi-bg-green"   43 "ansi-bg-yellow"
   44 "ansi-bg-blue"     45 "ansi-bg-magenta" 46 "ansi-bg-cyan"    47 "ansi-bg-white"
   100 "ansi-bg-brblack" 101 "ansi-bg-brred"  102 "ansi-bg-brgreen" 103 "ansi-bg-bryellow"
   104 "ansi-bg-brblue"  105 "ansi-bg-brmagenta" 106 "ansi-bg-brcyan" 107 "ansi-bg-brwhite"})

(def ^:private style-class
  {1 "ansi-bold" 2 "ansi-dim" 3 "ansi-italic" 4 "ansi-underline" 9 "ansi-strike"})

;; ---------------------------------------------------------------------------
;; State machine
;; ---------------------------------------------------------------------------

(defn- empty-state [] {:fg nil :bg nil :styles #{}})

(defn- apply-code
  "Update the SGR state with one numeric parameter."
  [{:keys [fg bg styles] :as st} ^long code]
  (cond
    (or (zero? code))             (empty-state)
    (= code 22)                   (assoc st :styles (disj styles 1 2))
    (= code 23)                   (assoc st :styles (disj styles 3))
    (= code 24)                   (assoc st :styles (disj styles 4))
    (= code 29)                   (assoc st :styles (disj styles 9))
    (= code 39)                   (assoc st :fg nil)
    (= code 49)                   (assoc st :bg nil)
    (contains? fg-class code)     (assoc st :fg code)
    (contains? bg-class code)     (assoc st :bg code)
    (contains? style-class code)  (assoc st :styles (conj styles code))
    :else                         st))

(defn- apply-codes [st codes]
  (reduce apply-code st codes))

(defn- state->class
  "Render the current state as a space-separated CSS class string,
   or nil if nothing active."
  [{:keys [fg bg styles]}]
  (let [parts (cond-> []
                fg          (conj (fg-class fg))
                bg          (conj (bg-class bg))
                (seq styles) (into (map style-class styles)))]
    (when (seq parts)
      (str/join " " parts))))

;; ---------------------------------------------------------------------------
;; HTML escaping
;;
;; Inlined (10 lines) rather than pulling hiccup's str-escape — this
;; runs on every line of every build, hot path.
;; ---------------------------------------------------------------------------

(defn escape-html [^String s]
  (-> s
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'"  "&#39;")))

;; ---------------------------------------------------------------------------
;; Main: parse + render
;; ---------------------------------------------------------------------------

(def ^:private sgr-pat
  ;; ESC [ … m   (SGR — graphics)
  ;; ESC [ … <one of @-~ except m>   (other CSI — clear/cursor, drop)
  ;; ESC X      (single-char escape sequences, drop)
  #"\u001b\[([0-9;]*)m|\u001b\[[0-9;]*[A-Za-z@-~]|\u001b.")

(defn- parse-params
  "Parse a `;`-separated SGR parameter string into a vec of longs.
   Empty string → [0] (CSI m alone means reset)."
  [param-str]
  (if (str/blank? param-str)
    [0]
    (mapv (fn [p] (try (Long/parseLong p)
                       (catch Exception _ 0)))
          (str/split param-str #";"))))

(defn ansi->html
  "Convert an ANSI-coloured string to HTML. Open spans are closed
   per-call — the function emits well-formed HTML even mid-stream.

   For multi-line input, callers should split into lines themselves
   and call once per line so colour state doesn't bleed across the
   line boundaries in the rendered DOM (matches xterm behavior:
   colour resets at end-of-line in most terminals' display semantics,
   even when SGR persists across lines).

   No state preserved between calls. To preserve state across calls,
   use `ansi->html-stateful`."
  [^String s]
  (let [m (re-matcher sgr-pat s)
        sb (StringBuilder.)
        state (atom (empty-state))
        last-end (atom 0)
        open-span? (atom false)
        close-span! (fn []
                      (when @open-span?
                        (.append sb "</span>")
                        (reset! open-span? false)))
        open-span! (fn []
                     (when-let [cls (state->class @state)]
                       (.append sb "<span class=\"")
                       (.append sb cls)
                       (.append sb "\">")
                       (reset! open-span? true)))]
    (while (.find m)
      (let [match-start (.start m)
            match-end (.end m)
            chunk (subs s @last-end match-start)]
        (when (seq chunk)
          (.append sb (escape-html chunk)))
        ;; If this is an SGR (m-terminated) — group(1) non-nil — apply.
        ;; Other escapes are silently dropped.
        (when-let [p (.group m 1)]
          (close-span!)
          (swap! state apply-codes (parse-params p))
          (open-span!))
        (reset! last-end match-end)))
    ;; Tail after last match.
    (let [tail (subs s @last-end)]
      (when (seq tail)
        (.append sb (escape-html tail))))
    (close-span!)
    (.toString sb)))

(defn strip-ansi
  "Remove every ANSI escape sequence from `s` (for download=text)."
  [^String s]
  (str/replace s sgr-pat ""))
