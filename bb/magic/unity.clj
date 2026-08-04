(ns magic.unity
  "Helpers for the MAGIC Unity package: write and check the
   constraints for the runtime DLLs."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [magic.log :as log])
  (:import [java.security MessageDigest]))

(def ^:private default-pkg "magic-unity")

;;; Runtime-selection constraints

(def ^:private constraint-blocks
  {"Export" ["!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR"]
   "Stock"  ["UNITY_EDITOR" "!MAGIC_RUNTIME_IN_EDITOR"]})

(def ^:private bare-symbol
  "An entry YAML reads plainly. Unity quotes the rest; a leading `!` is a tag."
  #"[A-Za-z_][A-Za-z0-9_]*")

(defn- yaml-entry [entry]
  (if (re-matches bare-symbol entry)
    entry
    (str "'" entry "'")))

(defn- define-constraints
  "The entries a plugin meta declares, unquoted. Absent and empty both read none."
  [meta-yaml]
  (->> (str/split-lines meta-yaml)
       (drop-while #(not (re-find #"^\s*defineConstraints:" %)))
       rest
       ;; map, not keep: the nil from the first non-item line is what stops
       ;; take-while. keep drops it and runs on into platformData's `- first:`.
       (map #(second (re-matches #"\s*-\s+(.*)" %)))
       (take-while some?)
       (mapv #(or (second (re-matches #"'(.*)'" (str/trim %)))
                  (str/trim %)))))

(defn infrastructure-dir
  "Where a runtime set's DLLs live in the package."
  [set-name]
  (str default-pkg "/Runtime/Infrastructure/" set-name))

(defn- infrastructure-dlls [set-name]
  (sort (fs/glob (infrastructure-dir set-name) "*.dll")))

(def ^:private stamper-path (str default-pkg "/Editor/CljPluginConstraints.cs"))

(defn- stamper-agrees?
  "Whether the import-time stamper's C# constant is the Export entry."
  []
  (str/includes? (slurp stamper-path)
                 (str "\"" (first (constraint-blocks "Export")) "\"")))

;;; Authoring the metas

(defn- guid
  "Deterministic 32-hex GUID, so a recreated meta keeps the one Unity recorded."
  [set-name asset-name]
  (->> (.digest (MessageDigest/getInstance "MD5")
                (.getBytes (str "sg.flybot.magic.unity/" set-name "/" asset-name) "UTF-8"))
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

;; Real Unity-written metas with the two authored fields punched out.
;; Both sets are auto-referenced (isExplicitlyReferenced: 0): Magic.Unity.asmdef
;; names no precompiled references, so it binds whichever Clojure.dll the
;; constraints admit -- Stock in the default Editor, Export otherwise.
(def ^:private template-dir "bb/templates/plugin-meta")

(defn- plugin-meta [set-name dll-name]
  (-> (slurp (str template-dir "/" set-name ".meta.tmpl"))
      (str/replace "{{guid}}" (guid set-name dll-name))
      (str/replace "{{defineConstraints}}"
                   (str/join "\n" (for [entry (constraint-blocks set-name)]
                                    (str "  - " (yaml-entry entry)))))))

(defn- folder-meta [set-name]
  (str "fileFormatVersion: 2\n"
       "guid: " (guid set-name set-name) "\n"
       "folderAsset: yes\n"
       "DefaultImporter:\n"
       "  externalObjects: {}\n"
       "  userData: \n"
       "  assetBundleName: \n"
       "  assetBundleVariant: \n"))

(defn- write-missing-meta!
  "Does not overwrite, as existing files and GUIDs shouldn't change. A wrong
   meta will be reported by check-constraints!. Returns path when it wrote."
  [path content]
  (when-not (fs/exists? path)
    (spit path content)
    path))

(defn write-metas!
  "Create a plugin .meta for every shipped DLL that lacks one, plus a folder meta
   per set."
  []
  (let [sets     (keys constraint-blocks)
        ;; What should exist, as [path content] pairs.
        folders  (for [set-name sets
                       :let [dir (infrastructure-dir set-name)]
                       :when (fs/exists? dir)]
                   [(str dir ".meta") (folder-meta set-name)])
        plugins  (for [set-name sets
                       dll      (infrastructure-dlls set-name)]
                   [(str dll ".meta") (plugin-meta set-name (fs/file-name dll))])
        created  (into [] (keep #(apply write-missing-meta! %))
                       (concat folders plugins))]
    (if (seq created)
      (do (run! #(println "  created" %) created)
          (println (count created) "meta files created."))
      (println "Every shipped DLL already has a .meta."))))

;;; Checking them

(defn check-constraints!
  "Fail unless every shipped DLL's meta carries its set's constraint block and
   the stamper agrees. Two unconstrained Clojure.dlls in the Editor are
   de-duplicated by Unity's file-name mechanism. Finds missing metas too."
  []
  (let [sets  (mapv (fn [[set-name expected]]
                      [set-name expected (infrastructure-dlls set-name)])
                    constraint-blocks)
        wrong (for [[_ expected dlls] sets
                    dll dlls
                    ;; nil = no meta; [] = meta declaring none. `expected` is not empty,
                    ;; so nil or empty metas are caught
                    :let [meta-file (str dll ".meta")
                          found     (when (fs/exists? meta-file)
                                      (define-constraints (slurp meta-file)))]
                    :when (not= expected found)]
                (if (nil? found)
                  (str "  " dll "\n"
                       "    has no .meta at all; run `bb write-metas`")
                  (str "  " meta-file "\n"
                       "    expected " (pr-str expected) "\n"
                       "    found    " (pr-str found))))]
    (when (seq wrong)
      (apply log/fail! "define constraints are wrong"
             (concat ["" "These shipped DLLs do not carry the runtime-selection block" ""]
                     wrong)))
    (when-not (stamper-agrees?)
      (log/fail! "define constraints are wrong"
                 ""
                 (str "  " stamper-path " does not stamp the Export entry")
                 (str "  " (pr-str (first (constraint-blocks "Export"))) " onto consumer *.clj.dll.")))
    (println "define constraints OK -"
             (str/join ", " (for [[set-name _ dlls] sets]
                              (str (count dlls) " in " set-name "/")))
             "+ the import-time stamper")))

(defn sync-upm-version!
  "Sync magic-unity/package.json version with version.edn. UPM manifests are
   not covered by Directory.Build.props, so this keeps them in lockstep."
  []
  (let [version (:version (edn/read-string (slurp "version.edn")))
        path    (str default-pkg "/package.json")
        content (slurp path)
        updated (str/replace content
                             (re-pattern "\"version\":\\s*\"[^\"]+\"")
                             (str "\"version\": \"" version "\""))]
    (if (= content updated)
      (println "magic-unity/package.json version already in sync (" version ")")
      (do (spit path updated)
          (println "Updated magic-unity/package.json version to" version)))))

