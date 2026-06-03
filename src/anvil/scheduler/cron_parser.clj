(ns anvil.scheduler.cron-parser
  "Jenkins-flavor cron parser (T5.1 of the v0.3 board).

   Supports:
     - 5-field standard cron: minute hour day-of-month month day-of-week
     - Field syntax: number, list (1,3,5), range (1-5), step (*/15)
     - Wildcards: *
     - Jenkins's H-spread syntax: H means 'pick a stable hashed value
       in the field's valid range, keyed on the job name so multiple
       jobs spread their load instead of all firing at minute 0'
     - Aliases: @hourly, @daily, @midnight, @weekly, @monthly, @yearly"
  (:require [clojure.string :as str])
  (:import [java.time DayOfWeek ZonedDateTime]
           [java.time.temporal ChronoField]))

(def ^:private aliases
  {"@yearly"   "0 0 1 1 *"
   "@annually" "0 0 1 1 *"
   "@monthly"  "0 0 1 * *"
   "@weekly"   "0 0 * * 0"
   "@daily"    "0 0 * * *"
   "@midnight" "0 0 * * *"
   "@hourly"   "0 * * * *"})

(defn- hash-in
  "Hash `key` (string) into a value in [lo,hi]."
  [key lo hi]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str key) "UTF-8"))
        x (bit-and (long (bit-or (bit-shift-left (long (bit-and (aget bs 0) 0xff)) 24)
                                  (bit-shift-left (long (bit-and (aget bs 1) 0xff)) 16)
                                  (bit-shift-left (long (bit-and (aget bs 2) 0xff)) 8)
                                  (long (bit-and (aget bs 3) 0xff))))
                   0x7fffffff)
        span (- hi lo -1)]
    (+ lo (mod x span))))

(defn- parse-single
  [atom-str lo hi hash-key]
  (let [s (str/trim atom-str)]
    (cond
      (= "H" s)
      #{(hash-in hash-key lo hi)}

      (re-matches #"H/(\d+)" s)
      (let [step (Integer/parseInt (re-find #"\d+" s))
            base (hash-in hash-key 0 (dec step))]
        (set (range (+ lo base) (inc hi) step)))

      (re-matches #"H\((\d+)-(\d+)\)" s)
      (let [[_ a b] (re-matches #"H\((\d+)-(\d+)\)" s)]
        #{(hash-in hash-key (Integer/parseInt a) (Integer/parseInt b))})

      (re-matches #"H\((\d+)-(\d+)\)/(\d+)" s)
      (let [[_ a b n] (re-matches #"H\((\d+)-(\d+)\)/(\d+)" s)
            a (Integer/parseInt a) b (Integer/parseInt b) n (Integer/parseInt n)
            base (hash-in hash-key 0 (dec n))]
        (set (range (+ a base) (inc b) n)))

      (re-matches #"\*/(\d+)" s)
      (let [n (Integer/parseInt (re-find #"\d+" s))]
        (set (range lo (inc hi) n)))

      (re-matches #"(\d+)-(\d+)/(\d+)" s)
      (let [[_ a b n] (re-matches #"(\d+)-(\d+)/(\d+)" s)]
        (set (range (Integer/parseInt a) (inc (Integer/parseInt b))
                    (Integer/parseInt n))))

      (re-matches #"(\d+)-(\d+)" s)
      (let [[_ a b] (re-matches #"(\d+)-(\d+)" s)]
        (set (range (Integer/parseInt a) (inc (Integer/parseInt b)))))

      (= "*" s)
      (set (range lo (inc hi)))

      (re-matches #"\d+" s)
      #{(Integer/parseInt s)}

      :else
      (throw (ex-info (str "anvil.cron: cannot parse atom: " s)
                      {:atom s})))))

(defn- parse-field [field-str lo hi hash-key]
  (let [parts (str/split field-str #",")
        merged (reduce into #{} (map #(parse-single % lo hi hash-key) parts))]
    (set (filter (fn [v] (and (>= v lo) (<= v hi))) merged))))

(defn parse
  "Parse a Jenkins cron expression. `hash-key` (typically a job name)
   makes `H` deterministic per job."
  [expr hash-key]
  (let [expr (str/trim expr)
        expanded (get aliases expr expr)
        parts (str/split expanded #"\s+")]
    (when-not (= 5 (count parts))
      (throw (ex-info (str "anvil.cron: expected 5 fields, got " (count parts))
                      {:expr expr})))
    (let [[m h dom mo dow] parts]
      {:minutes (parse-field m  0 59 (str hash-key ":m"))
       :hours   (parse-field h  0 23 (str hash-key ":h"))
       :doms    (parse-field dom 1 31 (str hash-key ":dom"))
       :months  (parse-field mo 1 12 (str hash-key ":mo"))
       :dows    (parse-field dow 0 6  (str hash-key ":dow"))
       :raw expr})))

(defn- dow-jenkins
  "Java's DayOfWeek is 1=MON..7=SUN; cron's dow is 0=SUN..6=SAT."
  [^DayOfWeek d]
  (mod (.getValue d) 7))

(defn matches?
  "True iff the given ZonedDateTime is when the schedule fires."
  [ir ^ZonedDateTime t]
  (and (contains? (:minutes ir) (.get t ChronoField/MINUTE_OF_HOUR))
       (contains? (:hours ir)   (.get t ChronoField/HOUR_OF_DAY))
       (contains? (:doms ir)    (.get t ChronoField/DAY_OF_MONTH))
       (contains? (:months ir)  (.get t ChronoField/MONTH_OF_YEAR))
       (contains? (:dows ir)    (dow-jenkins (.getDayOfWeek t)))))

(defn next-fire-after
  "Return the next ZonedDateTime AFTER `anchor` when the schedule
   fires. Walks forward minute-by-minute, capped at 366 days."
  [ir ^ZonedDateTime anchor]
  (let [start (-> anchor (.plusMinutes 1) (.withSecond 0) (.withNano 0))
        cap (.plusDays start 366)]
    (loop [t start]
      (cond
        (.isAfter t cap) nil
        (matches? ir t)  t
        :else            (recur (.plusMinutes t 1))))))
