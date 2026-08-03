(ns magic.unity
  "Babashka helpers for the MAGIC Unity package: author and check the
   runtime-selection define constraints that decide which Clojure runtime the
   Editor loads. See docs/dual-runtimes.md. The headless regression that proves
   both Editor states work lives in magic.coexist."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [magic.log :as log])
  (:import [java.security MessageDigest]))

(def default-pkg "magic-unity")

;;; Runtime-selection constraints

;; The whole Editor/player runtime policy, as plugin metadata. Entries in a
;; block are AND-ed; within an entry `||` means "every negated term absent, or
;; any plain term present", which is why the selector appears un-negated in the
;; MAGIC block. Players define no UNITY_EDITOR, so MAGIC is satisfied there
;; whatever the symbol says and Stock never is. See docs/dual-runtimes.md.
(def ^:private constraint-blocks
  {"Export" ["'!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR'"]
   "Stock"  ["UNITY_EDITOR" "'!MAGIC_RUNTIME_IN_EDITOR'"]})

(defn- define-constraints
  "The defineConstraints entries a plugin meta declares, verbatim."
  [meta-yaml]
  (->> (str/split-lines meta-yaml)
       (drop-while #(not= "  defineConstraints:" (str/trim-newline %)))
       rest
       (take-while #(str/starts-with? % "  - "))
       (mapv #(str/trim (subs % 4)))))

;; The Include Platforms panel, serialized. Redundant with the constraints, but
;; Unity shows both in the Inspector and they read as a contradiction if only
;; one is set. Whatever is written here is permanent: Unity re-normalizes a
;; plugin importer only where the meta is writable, never inside an immutable
;; UPM package. Export therefore copies its committed sibling
;; clojure.core.clj.dll -- a fossil of being authored under Assets/ years ago --
;; rather than the equivalent minimal `Any: enabled: 1`.
(def ^:private platform-data
  {"Export" ["  - first:"
             "      : Any"
             "    second:"
             "      enabled: 0"
             "      settings:"
             "        Exclude Android: 0"
             "        Exclude Editor: 0"
             "        Exclude Linux64: 0"
             "        Exclude OSXUniversal: 0"
             "        Exclude WebGL: 0"
             "        Exclude Win: 0"
             "        Exclude Win64: 0"
             "  - first:"
             "      Android: Android"
             "    second:"
             "      enabled: 1"
             "      settings: {}"
             "  - first:"
             "      Any: "
             "    second:"
             "      enabled: 1"
             "      settings: {}"
             "  - first:"
             "      Editor: Editor"
             "    second:"
             "      enabled: 1"
             "      settings:"
             "        DefaultValueInitialized: true"
             "  - first:"
             "      Standalone: Linux64"
             "    second:"
             "      enabled: 1"
             "      settings: {}"
             "  - first:"
             "      Standalone: OSXUniversal"
             "    second:"
             "      enabled: 1"
             "      settings: {}"
             "  - first:"
             "      Standalone: Win"
             "    second:"
             "      enabled: 1"
             "      settings: {}"
             "  - first:"
             "      Standalone: Win64"
             "    second:"
             "      enabled: 1"
             "      settings: {}"
             "  - first:"
             "      WebGL: WebGL"
             "    second:"
             "      enabled: 1"
             "      settings: {}"
             "  - first:"
             "      Windows Store Apps: WindowsStoreApps"
             "    second:"
             "      enabled: 0"
             "      settings:"
             "        CPU: AnyCPU"]
   "Stock"  ["  - first:"
             "      Any: "
             "    second:"
             "      enabled: 0"
             "      settings: {}"
             "  - first:"
             "      Editor: Editor"
             "    second:"
             "      enabled: 1"
             "      settings:"
             "        DefaultValueInitialized: true"]})

(defn- infrastructure-dir [set-name]
  (str default-pkg "/Runtime/Infrastructure/" set-name))

(defn- infrastructure-dlls [set-name]
  (sort (fs/glob (infrastructure-dir set-name) "*.dll")))

;; A third copy of the MAGIC entry: the Editor postprocessor stamps it onto
;; consumer *.clj.dll under Assets/. Renaming the symbol here but not in the
;; metas would constrain consumer DLLs on a symbol nothing defines, excluding
;; them from the Editor in *both* states, silently.
(def ^:private stamper-path "magic-unity/Editor/CljPluginConstraints.cs")

(defn- stamper-agrees?
  "Whether the import-time stamper's C# constant is the Export entry, unquoted."
  []
  (let [entry (str/replace (first (constraint-blocks "Export")) #"^'|'$" "")]
    (str/includes? (slurp stamper-path) (str "\"" entry "\""))))

;;; Authoring the metas

(defn- guid
  "Deterministic 32-hex asset GUID, so a meta that has to be recreated keeps the
   GUID Unity already recorded."
  [set-name name]
  (->> (.digest (MessageDigest/getInstance "MD5")
                (.getBytes (str "sg.flybot.magic.unity/" set-name "/" name) "UTF-8"))
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

;; isExplicitlyReferenced: 0 (auto-referenced) is load-bearing for Stock: in the
;; symbol-unset default state Magic.Unity.cs compiles against that Clojure.dll.
(defn- plugin-meta [set-name name]
  (str "fileFormatVersion: 2\n"
       "guid: " (guid set-name name) "\n"
       "PluginImporter:\n"
       "  externalObjects: {}\n"
       "  serializedVersion: 2\n"
       "  iconMap: {}\n"
       "  executionOrder: {}\n"
       "  defineConstraints:\n"
       (str/join (for [entry (constraint-blocks set-name)] (str "  - " entry "\n")))
       "  isPreloaded: 0\n"
       "  isOverridable: 0\n"
       "  isExplicitlyReferenced: 0\n"
       "  validateReferences: 1\n"
       "  platformData:\n"
       (str/join (for [line (platform-data set-name)] (str line "\n")))
       "  userData: \n"
       "  assetBundleName: \n"
       "  assetBundleVariant: \n"))

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
  "Never overwrites: the constraint block is policy and the GUID must not churn
   under a consumer, so a wrong-but-present meta is check-constraints!'s to
   report. Returns path when it wrote."
  [path content]
  (when-not (fs/exists? path)
    (spit path content)
    path))

(defn write-metas!
  "Create a plugin .meta for every shipped DLL that lacks one, and a folder meta
   per set directory. Run after vendoring or adding a DLL: one Unity cannot
   import is one with no runtime-selection constraint on it."
  []
  (let [created (doall
                 (concat
                  (for [[set-name] constraint-blocks
                        :let [dir (infrastructure-dir set-name)]
                        :when (fs/exists? dir)
                        :let [path (write-missing-meta! (str dir ".meta")
                                                        (folder-meta set-name))]
                        :when path]
                    path)
                  (for [[set-name] constraint-blocks
                        dll (infrastructure-dlls set-name)
                        :let [path (write-missing-meta! (str dll ".meta")
                                                        (plugin-meta set-name (fs/file-name dll)))]
                        :when path]
                    path)))]
    (if (seq created)
      (do (doseq [path created] (println "  created" path))
          (println (count created) "meta files created."))
      (println "Every shipped DLL already has a .meta."))))

;;; Checking them

(defn check-constraints!
  "Fail unless every shipped DLL has a meta carrying its set's constraint block,
   and the import-time stamper agrees. Leaving one unconstrained is not
   cosmetic: two Editor-eligible Clojure.dll hand runtime selection back to
   Unity's file-name dedup, and an unconstrained *.clj.dll loads into a stock
   Editor with no log line at all.

   Iterates the DLLs, not the metas: a missing meta is the one case a sweep over
   *.dll.meta cannot see, and the likeliest way a vendored file goes
   unconstrained."
  []
  (let [wrong (for [[set-name expected] constraint-blocks
                    dll (infrastructure-dlls set-name)
                    :let [meta-file (str dll ".meta")]
                    :when (or (not (fs/exists? meta-file))
                              (not= expected (define-constraints (slurp meta-file))))]
                (if-not (fs/exists? meta-file)
                  (str "  " dll "\n"
                       "    has no .meta at all; run `bb write-metas`")
                  (str "  " meta-file "\n"
                       "    expected " (pr-str expected) "\n"
                       "    found    " (pr-str (define-constraints (slurp meta-file))))))]
    (when (seq wrong)
      (apply log/fail! "define constraints are wrong"
             (concat ["" "These shipped DLLs do not carry the runtime-selection block"
                      "documented in docs/dual-runtimes.md:" ""]
                     wrong)))
    (when-not (stamper-agrees?)
      (log/fail! "define constraints are wrong"
                 ""
                 (str "  " stamper-path " does not stamp the Export entry")
                 (str "  " (pr-str (first (constraint-blocks "Export"))) " onto consumer *.clj.dll.")
                 "  Consumer DLLs would be constrained on a symbol nothing defines."))
    (println "define constraints OK -"
             (str/join ", " (for [[set-name] constraint-blocks]
                              (str (count (infrastructure-dlls set-name)) " in " set-name "/")))
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

