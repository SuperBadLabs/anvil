(ns anvil.cost
  "v0.5 T0.5 — per-host rate schema reader.

   The cost-per-minute feature (T2) sums wall-time × per-host rates
   across every step of a build. Operators declare rates in
   `anvil.edn` under `:anvil.cost/host-rates`:

       {:anvil.cost/host-rates {\"heman\" {:rate-per-min 0.012
                                          :currency :usd}
                                \"mario\" {:rate-per-min 0.005
                                          :currency :usd}
                                \"luigi\" {:rate-per-min 0.018
                                          :currency :usd}}}

   Rates default to 0 if absent — anvil never invents a number; the
   `/cost` dashboard shows `$0.00 (no rate declared for <host>)` for
   any host the operator hasn't priced.

   This namespace ships at T0.5 (read-only helper). T2.1 wires the
   recorder; T2.3 wires the dashboard."
  (:require [anvil.config :as config]))

(def ^:private default-rate
  {:rate-per-min 0.0 :currency :usd})

(defn host-rate
  "Look up the per-minute rate for `host`. Returns a map with
   `:rate-per-min` (number, defaults to 0.0) and `:currency`
   (keyword, defaults to :usd). Never returns nil."
  [host]
  (let [cfg ((requiring-resolve 'anvil.config/load-edn) "anvil" {})
        rates (get cfg :anvil.cost/host-rates {})]
    (merge default-rate (get rates host))))

(defn cost-for
  "Compute the cost in (rate-per-min units) for a wall-clock duration
   on `host`. Pure function — no IO except the config read.

   `wall-ms` is the wall-clock milliseconds. Returns a map
   `{:cents <long> :rate-per-min <num> :currency <kw>}`.

   Anvil internally tracks cost in `:cents` (1/100 of the currency
   unit) so we don't carry floating-point cumulative error across
   sums. UI rendering happens at presentation time."
  [host wall-ms]
  (let [{:keys [rate-per-min currency]} (host-rate host)
        minutes (/ wall-ms 60000.0)
        units (* minutes rate-per-min)
        cents (long (Math/round (* units 100)))]
    {:cents cents
     :rate-per-min rate-per-min
     :currency currency}))

(defn total-cents
  "Sum `:cents` across a sequence of cost maps."
  [costs]
  (apply + 0 (map :cents costs)))
