(ns anvil.cli.secrets
  "CLI for anvil's credentials store (T6.3 + T6.7).

   Subcommands:
     anvil secrets add <id> [--type string|username-password] [--description …]
                        # reads the value from stdin
     anvil secrets list
     anvil secrets show <id>    # masked only
     anvil secrets delete <id>
     anvil secrets rotate-master --new-key <base64-of-32-bytes>

   All paths require anvil.storage.db/init! first — the CLI initializes
   it against the same default path the daemon uses."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [anvil.storage.db :as db]
            [anvil.storage.credentials :as creds]))

(defn- read-stdin-secret []
  ;; tools.cli + bash heredoc / pipe.
  (let [b (java.io.BufferedReader. *in*)
        sb (StringBuilder.)]
    (loop []
      (let [line (.readLine b)]
        (when line
          (when-not (zero? (.length sb)) (.append sb "\n"))
          (.append sb line)
          (recur))))
    (.toString sb)))

(defn- ensure-db! []
  (db/init!))

(defn- preview [s]
  (let [n (count s)]
    (cond
      (zero? n) "(empty)"
      (< n 4)   "***"
      :else     (str "****" (subs s (max 0 (- n 4)))))))

;; ---------------------------------------------------------------------------
;; Subcommands
;; ---------------------------------------------------------------------------

(defn- cmd-add [argv]
  (let [opts [["-t" "--type TYPE" "Credential type" :default "string"]
              ["-d" "--description DESC" "Description"]
              ["-h" "--help"]]
        {:keys [arguments options summary errors]} (tools-cli/parse-opts argv opts)]
    (cond
      errors
      (do (run! println errors) 3)

      (or (:help options) (empty? arguments))
      (do (println "Usage: anvil secrets add <id> [--type string|username-password] [--description ...]")
          (println "Value is read from stdin so it never appears in `ps`.")
          (println summary)
          (if (:help options) 0 3))

      :else
      (let [id (first arguments)
            value (str/trim (read-stdin-secret))
            ctype (keyword (:type options))]
        (ensure-db!)
        (creds/add! {:id id
                     :type ctype
                     :value value
                     :description (or (:description options) "")})
        (println (str "Added credential " id " (type: " (name ctype) ")"))
        (println (str "  preview: " (preview value)))
        0))))

(defn- cmd-list [_argv]
  (ensure-db!)
  (let [rows (creds/list-all)]
    (if (empty? rows)
      (do (println "(no credentials stored)") 0)
      (do
        (println (format "%-30s %-22s %-12s %s" "ID" "TYPE" "MASKED" "DESCRIPTION"))
        (println (apply str (repeat 80 "-")))
        (doseq [r rows]
          (println (format "%-30s %-22s %-12s %s"
                           (str (:id r))
                           (name (or (:type r) :string))
                           (or (:masked r) "***")
                           (or (:description r) ""))))
        0))))

(defn- cmd-show [argv]
  (let [id (first argv)]
    (cond
      (nil? id)
      (do (println "Usage: anvil secrets show <id>") 3)

      :else
      (do (ensure-db!)
          (if-let [c (creds/lookup id)]
            (do (println (str "ID:          " (:id c)))
                (println (str "Type:        " (name (or (:type c) :string))))
                (println (str "Description: " (or (:description c) "")))
                (println (str "Masked:      " (preview (str (:value c)))))
                0)
            (do (println (str "credential '" id "' not found")) 4))))))

(defn- cmd-delete [argv]
  (let [id (first argv)]
    (cond
      (nil? id)
      (do (println "Usage: anvil secrets delete <id>") 3)

      :else
      (do (ensure-db!)
          (creds/delete! id)
          (println (str "deleted credential " id))
          0))))

(defn- cmd-rotate-master [argv]
  (let [opts [["-k" "--new-key KEY" "Base64 of new 32-byte master key"]
              ["-h" "--help"]]
        {:keys [options errors]} (tools-cli/parse-opts argv opts)]
    (cond
      errors
      (do (run! println errors) 3)

      (or (:help options) (not (:new-key options)))
      (do (println "Usage: anvil secrets rotate-master --new-key <base64-32-bytes>")
          (println "Decrypts every stored credential with the current key, then re-encrypts with the new key.")
          (if (:help options) 0 3))

      :else
      (do (ensure-db!)
          ;; The rotation logic: walk every credential, decrypt with the
          ;; current key, swap the master-key cache, re-encrypt + write back.
          ;; anvil.storage.credentials/rotate-master! is the lift point;
          ;; if absent, do it inline.
          (let [rotate-fn (resolve 'anvil.storage.credentials/rotate-master!)]
            (cond
              rotate-fn
              (do (rotate-fn (:new-key options))
                  (println "rotated master key + re-encrypted all credentials")
                  0)

              :else
              (let [rows (creds/list-all)
                    decrypted (mapv (fn [r] (assoc r :plaintext (:value (creds/lookup (:id r)))))
                                    rows)]
                ;; Persist the new key to the master.key file before re-encrypting.
                (let [home (System/getProperty "user.home")
                      f (java.io.File. (str home "/.config/anvil/master.key"))]
                  (.mkdirs (.getParentFile f))
                  (spit f (str (:new-key options) "\n")))
                ;; Force the cache to re-read.
                ((requiring-resolve 'anvil.storage.credentials/reset-master-key-cache!))
                (doseq [r decrypted]
                  (creds/add! {:id (:id r)
                               :type (or (:type r) :string)
                               :value (:plaintext r)
                               :description (or (:description r) "")}))
                (println (str "rotated master key + re-encrypted " (count rows) " credentials"))
                0)))))))

;; ---------------------------------------------------------------------------
;; Public entry
;; ---------------------------------------------------------------------------

(defn run [argv]
  (let [[sub & rest] argv]
    (case sub
      "add"            (cmd-add (vec rest))
      "list"           (cmd-list (vec rest))
      "show"           (cmd-show (vec rest))
      "delete"         (cmd-delete (vec rest))
      "rotate-master"  (cmd-rotate-master (vec rest))
      (do (println "Usage: anvil secrets {add|list|show|delete|rotate-master} …") 3))))
