(ns anvil.cli.setup-tools
  "T7.4 — `anvil setup tools`. One-shot operator command that installs
   mise from upstream when it's not already on PATH. Does NOT auto-run
   from the daemon — explicit operator action only."
  (:require [babashka.process :as bp]
            [anvil.tools.mise :as mise]
            [clojure.string :as str]))

(defn run [_argv]
  (case (mise/resolve-backend)
    :mise
    (do (println "✓ mise already on PATH")
        0)

    :asdf
    (do (println "✓ asdf on PATH (mise not installed)")
        (println "  Anvil will use asdf for tool provisioning.")
        (println "  Consider installing mise for the modern path:")
        (println "    curl https://mise.run | sh")
        0)

    :none
    (do (println "→ neither mise nor asdf on PATH")
        (println "  Installing mise from upstream …")
        (let [{:keys [exit out err]} (bp/sh "sh" "-c" "curl https://mise.run | sh")]
          (println out)
          (when (seq err) (println err))
          (if (zero? exit)
            (do (println "✓ installed — restart your shell or `source ~/.profile` to put mise on PATH")
                0)
            (do (println "✗ install failed; see error above")
                3))))))
