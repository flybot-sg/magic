(ns magic.unity
  "Helpers for the MAGIC Unity package: write and check the
   constraints for the runtime DLLs."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [magic.log :as log])
  (:import [java.security MessageDigest]))

(def default-pkg "magic-unity")

(def unity-version "2022.3.62f3")

(def unity-app
  (or (System/getenv "MAGIC_UNITY_APP")
      (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
        (str "/Applications/Unity/Hub/Editor/" unity-version
             "/Unity.app/Contents/MacOS/Unity")
        (str (System/getProperty "user.home")
             "/Unity/Hub/Editor/" unity-version "/Editor/Unity"))))

(def clj-extensions
  "The assembly extensions. Mirrors PlayerCljAssemblies.Extensions."
  [".clj.dll" ".cljc.dll" ".cljr.dll"])

;;; Runtime-selection constraints

(def magic-symbol "MAGIC_RUNTIME_IN_EDITOR")

;; The polarity (default ClojureCLR, opt in to MAGIC) is forced by Unity's
;; grammar: entries AND, and a `||` entry is satisfied iff every negated term
;; is absent or any plain term is present.
;; :any-platform is the `Any:` row's enabled value in the meta's platform table:
;; 1 ships to players, 0 never does.
;; :compiler is Editor-only by the Editor/ folder rule and explicitly
;; referenced: it is reached through RT.load, never a C# type reference.
(def ^:private runtime-sets
  {:magic       {:dir          (str default-pkg "/Runtime/magic")
                 :constraints  [(str "!UNITY_EDITOR || " magic-symbol)]
                 :any-platform 1}
   :clojure-clr {:dir          (str default-pkg "/Runtime/clojure-clr")
                 :constraints  ["UNITY_EDITOR" (str "!" magic-symbol)]
                 :any-platform 0}
   :compiler    {:dir          (str default-pkg "/Editor/Compiler")
                 :constraints  [magic-symbol]
                 :any-platform 0}})

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

(defn- any-platform-enabled
  "The `Any:` row's enabled value in a plugin meta's platform table, as a long.
   nil when the row is absent."
  [meta-yaml]
  (some-> (->> (str/split-lines meta-yaml)
               (drop-while #(not (re-matches #"\s*Any:\s*" %)))
               (some #(second (re-matches #"\s*enabled:\s*(\d+)\s*" %))))
          parse-long))

(defn runtime-dir
  "Where a runtime set's DLLs live in the package."
  [set-key]
  (get-in runtime-sets [set-key :dir]))

(defn- runtime-dlls [set-key]
  (sort (fs/glob (runtime-dir set-key) "*.dll")))

(def ^:private constrainer-path (str default-pkg "/Editor/CljPluginConstraints.cs"))

(defn- constrainer-agrees?
  "Whether the import-time constrainer's C# constant is the MAGIC entry."
  []
  (str/includes? (slurp constrainer-path)
                 (str "\"" (first (get-in runtime-sets [:magic :constraints])) "\"")))

(defn- defeated-term?
  "Whether a term is false in a ClojureCLR Editor: UNITY_EDITOR is defined there
   and the MAGIC symbol is not. Any other term counts as satisfied."
  [term]
  (contains? #{magic-symbol "!UNITY_EDITOR"} (str/trim term)))

(defn- gated-in-clojure-clr-editor?
  "Whether a constraint block keeps a plugin out of a ClojureCLR Editor: entries
   AND and terms OR, so one entry with every term defeated suffices."
  [entries]
  (boolean
   (some (fn [entry]
           (let [terms (remove str/blank? (str/split (str entry) #"\|"))]
             (and (seq terms) (every? defeated-term? terms))))
         entries)))

;;; The Clojure-output extension lists

(defn- quoted-strings [s]
  (map second (re-seq #"\"([^\"]+)\"" s)))

(def ^:private extension-sources
  "Each copy of the Clojure-output extension list: a defining form to anchor
   on, and how a listed extension maps to an assembly extension."
  {(str default-pkg "/Editor/PlayerCljAssemblies.cs")
   {:form #"Extensions = \{([^}]*)\}" :dll identity}
   "magic-compiler/src/magic/api.clj"
   {:form #"source-extensions \[([^\]]*)\]" :dll #(str % ".dll")}
   "nostrand/nostrand/tasks.clj"
   {:form #"clj-assembly-suffixes[^\[]*\[([^\]]*)\]" :dll identity}})

(defn- extension-mismatches []
  (for [[path {:keys [form dll]}] extension-sources
        :let [found (some->> (re-find form (slurp path))
                             second
                             quoted-strings
                             (map dll)
                             set)]
        :when (not= (set clj-extensions) found)]
    (str "  " path "\n"
         "    expected " (pr-str (sort clj-extensions)) "\n"
         "    found    " (pr-str (sort (or found #{}))))))

;;; Authoring the metas

(defn- guid
  "Deterministic 32-hex GUID, deliberately distinct from any GUID Unity
   recorded for a copy of these DLLs in a consumer's Assets/."
  [set-key asset-name]
  (->> (.digest (MessageDigest/getInstance "MD5")
                (.getBytes (str "sg.flybot.magic.unity/" (name set-key) "/" asset-name) "UTF-8"))
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

;; Real Unity-written metas with the two authored fields punched out; one
;; template per set, named for the set key.
;; The two runtime sets are auto-referenced (isExplicitlyReferenced: 0):
;; Magic.Unity.asmdef names no precompiled references, so it binds whichever
;; Clojure.dll the constraints admit -- ClojureCLR in the default Editor, MAGIC
;; otherwise. :compiler is explicitly referenced instead, so its 37 DLLs are not
;; pushed onto every auto-referencing assembly in the consumer's project.
(def ^:private template-dir "bb/templates/plugin-meta")

(defn- plugin-meta [set-key dll-name]
  (-> (slurp (str template-dir "/" (name set-key) ".meta.tmpl"))
      (str/replace "{{guid}}" (guid set-key dll-name))
      (str/replace "{{defineConstraints}}"
                   (str/join "\n" (for [entry (get-in runtime-sets [set-key :constraints])]
                                    (str "  - " (yaml-entry entry)))))))

(defn- folder-meta [set-key]
  (str "fileFormatVersion: 2\n"
       "guid: " (guid set-key (name set-key)) "\n"
       "folderAsset: yes\n"
       "DefaultImporter:\n"
       "  externalObjects: {}\n"
       "  userData: \n"
       "  assetBundleName: \n"
       "  assetBundleVariant: \n"))

(defn- write-missing-meta!
  "Does not overwrite, as existing files and GUIDs shouldn't change. Returns
   path when it wrote."
  [path content]
  (when-not (fs/exists? path)
    (spit path content)
    path))

(defn write-metas!
  "Create a plugin .meta for every shipped DLL that lacks one, plus a folder meta
   per set."
  []
  (let [sets     (keys runtime-sets)
        folders  (for [set-key sets
                       :let [dir (runtime-dir set-key)]
                       :when (fs/exists? dir)]
                   [(str dir ".meta") (folder-meta set-key)])
        plugins  (for [set-key sets
                       dll      (runtime-dlls set-key)]
                   [(str dll ".meta") (plugin-meta set-key (fs/file-name dll))])
        created  (into [] (keep #(apply write-missing-meta! %))
                       (concat folders plugins))]
    (if (seq created)
      (do (run! #(println "  created" %) created)
          (println (count created) "meta files created."))
      (println "Every shipped DLL already has a .meta."))))

;;; Checking them

(defn check-constraints!
  "Fail unless every shipped DLL's meta carries its set's constraint block and
   the constrainer agrees; a newly added DLL arrives with an unconstrained meta."
  []
  (let [sets  (mapv (fn [[set-key {:keys [constraints any-platform]}]]
                      [set-key constraints any-platform (runtime-dlls set-key)])
                    runtime-sets)
        wrong (for [[_ expected _ dlls] sets
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
    ;; The other half of "never reaches a player": the platform table's Any row.
    (when-let [wrong-any (seq (for [[_ _ any-platform dlls] sets
                                    dll  dlls
                                    :let [meta-file (str dll ".meta")
                                          found     (when (fs/exists? meta-file)
                                                      (any-platform-enabled (slurp meta-file)))]
                                    :when (and (fs/exists? meta-file)
                                               (not= any-platform found))]
                                (str "  " meta-file "\n"
                                     "    expected Any: enabled " any-platform "\n"
                                     "    found    " (pr-str found))))]
      (apply log/fail! "define constraints are wrong"
             (concat ["" "These shipped DLLs carry the wrong Any platform value" ""]
                     wrong-any)))
    ;; The block a set authors must also pass the semantic gate the constrainer
    ;; applies to consumer DLLs; only sets that ship Clojure output are gated.
    (when-let [ungated (seq (for [[_ expected _ dlls] sets
                                  dll  dlls
                                  :when (some #(str/ends-with? (str dll) %) clj-extensions)
                                  :when (not (gated-in-clojure-clr-editor? expected))]
                              (str "  " dll ".meta\n"
                                   "    " (pr-str expected)
                                   " does not keep it out of a ClojureCLR Editor")))]
      (apply log/fail! "define constraints are wrong"
             (concat ["" "These Clojure-output DLLs are not gated on the Editor's runtime" ""]
                     ungated)))
    (when-not (constrainer-agrees?)
      (log/fail! "define constraints are wrong"
                 ""
                 (str "  " constrainer-path " does not constrain with the MAGIC entry")
                 (str "  " (pr-str (first (get-in runtime-sets [:magic :constraints]))) " onto consumer *.clj.dll.")))
    (when-let [mismatched (seq (extension-mismatches))]
      (apply log/fail! "the Clojure-output extension lists disagree"
             (concat ["" "These copies of the extension list have drifted apart" ""]
                     mismatched)))
    (println "define constraints OK -"
             (str/join ", " (for [[set-key _ _ dlls] sets]
                              (str (count dlls) " in " (name set-key) "/")))
             "+ the import-time constrainer + the extension lists")))

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

