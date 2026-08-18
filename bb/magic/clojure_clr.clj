(ns magic.clojure-clr
  "Vendor the ClojureCLR runtime into the Unity package."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.string :as str]
            [magic.log :as log]
            [magic.unity :as unity]))

(def ^:private repo "flybot-sg/clojure-clr")

(def ^:private clojure-clr-dir (unity/runtime-dir :clojure-clr))

(defn- newest-release-tag []
  ;; Not /releases/latest as it skips prereleases. /releases is newest-first.
  (-> (shell {:out :string} "gh" "api" (str "repos/" repo "/releases")
             "--jq" ".[0].tag_name")
      :out str/trim))

(defn- clear-dlls!
  "Delete the vendored DLLs. Returns the number deleted."
  []
  (let [dlls (fs/glob clojure-clr-dir "*.dll")]
    (run! fs/delete dlls)
    (count dlls)))

(defn- prune-orphan-metas!
  "Delete every .meta left without its DLL. Returns the paths."
  []
  (let [orphans (remove #(fs/exists? (str/replace (str %) #"\.meta$" ""))
                        (fs/glob clojure-clr-dir "*.dll.meta"))]
    (run! fs/delete orphans)
    orphans))

(defn sync!
  "Take every DLL of release tag, or of the newest release when tag is nil."
  [tag]
  (when-not (fs/which "gh")
    (log/fail! "gh not found"
               "  This task downloads release assets with the GitHub CLI."
               "  Install it and run `gh auth login`."))
  (let [tag (or tag (newest-release-tag))]
    (println "ClojureCLR" tag "->" clojure-clr-dir)
    (println "  cleared" (clear-dlls!) "vendored DLLs")
    ;; --pattern "*.dll" skips the .nupkg.
    (shell "gh" "release" "download" tag "--repo" repo
           "--dir" clojure-clr-dir "--pattern" "*.dll" "--clobber")
    ;; Run after the download, so meta files of DLLs that are unchanged are kept as-is
    (doseq [orphan (prune-orphan-metas!)]
      (println "  pruned" orphan "- no longer in the release"))
    (println "Next: review with git status.")))
