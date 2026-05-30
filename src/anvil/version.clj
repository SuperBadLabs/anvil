(ns anvil.version
  "Version reporting for anvil. The version string flows into the status
   page, build logs, and the X-Anvil-Version response header.")

(def ^:const version "0.1.0")

(def ^:const tagline
  "Drop-in Jenkins replacement, powered by chengis-core.")

(defn version-string
  "Full identifier string used in status responses."
  []
  (str "anvil " version))
