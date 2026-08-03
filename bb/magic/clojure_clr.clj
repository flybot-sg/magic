(ns magic.clojure-clr
  "Vendor the stock ClojureCLR runtime into the Unity package."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.string :as str]
            [magic.log :as log]))

(def ^:private repo "flybot-sg/clojure-clr")

(def stock-dir "magic-unity/Runtime/Infrastructure/Stock")

(defn- newest-release-tag []
  ;; Not /releases/latest as it skips prereleases. /releases is newest-first.
  (-> (shell {:out :string} "gh" "api" (str "repos/" repo "/releases")
             "--jq" ".[0].tag_name")
      :out str/trim))

(defn sync!
  "Take every DLL of release tag, or of the newest release when tag is nil."
  [tag]
  (when-not (fs/which "gh")
    (log/fail! "gh not found"
               "  This task downloads release assets with the GitHub CLI."
               "  Install it and run `gh auth login`."))
  (let [tag (or tag (newest-release-tag))]
    (println "ClojureCLR" tag "->" stock-dir)
    ;; --pattern "*.dll" skips the .nupkg.
    (shell "gh" "release" "download" tag "--repo" repo
           "--dir" stock-dir "--pattern" "*.dll" "--clobber")
    (println "Next: review with git status.")))
