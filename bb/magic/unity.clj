(ns magic.unity
  "Babashka helpers for the two MAGIC Unity package variants: generate the
   editor-excluded `magic-unity-dual` from `magic-unity`, and drive the
   `magic-unity-coexist` repro that proves the dual variant is silent."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [magic.log :as log])
  (:import [java.security MessageDigest]))

(def ^:private default-pkg "magic-unity")
(def ^:private dual-pkg "magic-unity-dual")
(def ^:private coexist-proj "unity-examples/magic-unity-coexist")
(def ^:private unity
  "/Applications/Unity/Hub/Editor/2022.3.62f3/Unity.app/Contents/MacOS/Unity")

;;; Runtime-selection constraints

(def magic-symbol
  "The scripting define symbol that selects the Editor's Clojure runtime."
  "MAGIC_RUNTIME_IN_EDITOR")

(def ^:private runtime-sets
  {:magic       [(str "!UNITY_EDITOR || " magic-symbol)]
   :clojure-clr ["UNITY_EDITOR" (str "!" magic-symbol)]})

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

(defn runtime-dir
  "Where a runtime set's DLLs live in the package."
  [set-key]
  (str default-pkg "/Runtime/" (name set-key)))

(defn- runtime-dlls [set-key]
  (sort (fs/glob (runtime-dir set-key) "*.dll")))

(def ^:private stamper-path (str default-pkg "/Editor/CljPluginConstraints.cs"))

(defn- stamper-agrees?
  "Whether the import-time stamper's C# constant is the MAGIC entry."
  []
  (str/includes? (slurp stamper-path)
                 (str "\"" (first (runtime-sets :magic)) "\"")))

;;; Authoring the metas

(defn- guid
  "Deterministic 32-hex GUID, so a recreated meta keeps the one Unity recorded."
  [set-key asset-name]
  (->> (.digest (MessageDigest/getInstance "MD5")
                (.getBytes (str "sg.flybot.magic.unity/" (name set-key) "/" asset-name) "UTF-8"))
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

;; Real Unity-written metas with the two authored fields punched out.
;; Both sets are auto-referenced (isExplicitlyReferenced: 0): Magic.Unity.asmdef
;; names no precompiled references, so it binds whichever Clojure.dll the
;; constraints admit -- ClojureCLR in the default Editor, MAGIC otherwise.
(def ^:private template-dir "bb/templates/plugin-meta")

(defn- plugin-meta [set-key dll-name]
  (-> (slurp (str template-dir "/" (name set-key) ".meta.tmpl"))
      (str/replace "{{guid}}" (guid set-key dll-name))
      (str/replace "{{defineConstraints}}"
                   (str/join "\n" (for [entry (runtime-sets set-key)]
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
   the stamper agrees. Two unconstrained Clojure.dlls in the Editor are
   de-duplicated by Unity's file-name mechanism. Finds missing metas too."
  []
  (let [sets  (mapv (fn [[set-key expected]]
                      [set-key expected (runtime-dlls set-key)])
                    runtime-sets)
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
                 (str "  " stamper-path " does not stamp the MAGIC entry")
                 (str "  " (pr-str (first (runtime-sets :magic))) " onto consumer *.clj.dll.")))
    (println "define constraints OK -"
             (str/join ", " (for [[set-key _ dlls] sets]
                              (str (count dlls) " in " (name set-key) "/")))
             "+ the import-time stamper")))

;;; Dual variant generation

(defn- exclude-from-editor [meta-yaml]
  (str/replace meta-yaml
               "  defineConstraints: []"
               "  defineConstraints:\n  - '!UNITY_EDITOR'"))

(defn- rename-package [package-json]
  (-> package-json
      (str/replace "\"name\": \"sg.flybot.magic.unity\""
                   "\"name\": \"sg.flybot.magic.unity.dual\"")
      (str/replace "\"displayName\": \"MAGIC Unity Integration\""
                   "\"displayName\": \"MAGIC Unity Integration (dual: stock editor + MAGIC players)\"")))

(defn- edit-file!
  "Rewrite path through f. Throws when f changes nothing, the signal that the
   source format drifted from what the generator expects."
  [path f]
  (let [content (slurp path)
        edited  (f content)]
    (when (= content edited)
      (throw (ex-info (str "gen-unity-dual: no substitution applied to " path) {:path path})))
    (spit path edited)))

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

(defn gen-dual!
  "Regenerate magic-unity-dual as a byte-for-byte copy of magic-unity with the
   runtime clj.dll plugins constrained to !UNITY_EDITOR and the package renamed.
   Returns the number of constrained plugins."
  []
  (fs/delete-tree dual-pkg)
  (shell "cp" "-R" default-pkg dual-pkg)
  (let [metas (fs/glob (str dual-pkg "/Runtime/magic") "*.clj.dll.meta")]
    (when (empty? metas)
      (throw (ex-info "gen-unity-dual: no runtime *.clj.dll.meta in copy" {})))
    (run! #(edit-file! (str %) exclude-from-editor) metas)
    (edit-file! (str dual-pkg "/package.json") rename-package)
    (count metas)))

;;; Coexistence repro

(def ^:private variants
  {"dual"       {:pkg dual-pkg    :dependency "sg.flybot.magic.unity.dual" :expected 0}
   "magic-only" {:pkg default-pkg :dependency "sg.flybot.magic.unity"      :expected 37}})

(defn- coexist-path [& parts]
  (str/join "/" (cons coexist-proj parts)))

(defn- pack-tarball!
  "Pack pkg into a UPM tarball at tgz: a single top-level package/ directory."
  [pkg tgz]
  (let [staging (fs/create-temp-dir {:prefix "magic-coexist-pkg"})
        pkgdir  (str (fs/path staging "package"))]
    (fs/create-dirs pkgdir)
    (shell "cp" "-R" (str pkg "/.") (str pkgdir "/"))
    (shell "tar" "czf" tgz "-C" (str staging) "package")
    (fs/delete-tree staging)))

(defn- write-manifest!
  "Pin the coexist project to dependency, which must equal the tarball's
   package.json name or UPM refuses to resolve it."
  [dependency]
  (spit (coexist-path "Packages" "manifest.json")
        (str "{\n  \"dependencies\": {\n    \"" dependency
             "\": \"file:../magic-unity.tgz\"\n  }\n}\n")))

(defn- reset-package-cache! []
  (fs/delete-if-exists (coexist-path "Packages" "packages-lock.json"))
  (let [cache (coexist-path "Library" "PackageCache")]
    (when (fs/exists? cache)
      (run! fs/delete-tree (fs/glob cache "sg.flybot.magic.unity*")))))

(defn- run-editor!
  "Launch Unity headless on the coexist project, logging to log; extra args
   (e.g. -executeMethod) are appended. A non-zero exit is tolerated because the
   log, not the exit code, is the signal."
  [log & args]
  (fs/create-dirs (coexist-path "Logs"))
  (fs/delete-if-exists log)
  (apply shell {:continue true} unity
         "-batchmode" "-quit" "-nographics"
         "-projectPath" coexist-proj "-logFile" log args))

(defn- parse-log [log-text]
  (let [lines      (str/split-lines log-text)
        containing (fn [substr] (filter #(str/includes? % substr) lines))]
    {:narration (count (containing "Assembly is incompatible with the editor"))
     :dedup     (count (containing "Duplicate assembly 'Clojure.dll'"))
     :probe     (first (containing "[CoexistenceProbe]"))}))

(defn- verdict
  "Classify a parsed run for the variant as [status message]."
  [variant {:keys [narration probe]}]
  (let [silent?      (zero? narration)
        probe-fixed? (boolean (some-> probe (str/includes? "core-clj-loadable=false")))]
    (cond
      (and (= variant "dual") silent? probe-fixed?)
      [:pass "dual variant is silent; probe confirms #25 stays fixed (core-clj-loadable=false)"]
      (and (= variant "dual") silent?)
      [:inconclusive "0 narration but probe did not confirm core-clj-loadable=false; did the package resolve?"]
      (= variant "dual")
      [:fail (str "dual variant narrated " narration " lines; the !UNITY_EDITOR constraint is missing or ineffective")]
      (pos? narration)
      [:reproduced "reproduced the noise on the magic-only variant (expected); this is what the dual variant fixes"]
      :else
      [:unexpected "magic-only produced 0 narration lines"])))

(defn- result-summary
  "Report map for a parsed run: status and message from the verdict, plus the
   raw counts and the probe line."
  [variant expected {:keys [narration dedup probe] :as result}]
  (let [[status message] (verdict variant result)]
    (array-map
     :variant   variant
     :status    status
     :message   message
     :narration narration
     :expected  expected
     :dedup     dedup
     :probe     probe)))

(defn coexist-noise!
  "Reproduce / regression-check the coexistence noise for variant \"dual\" (the
   shipped fix, expect 0 narration lines) or \"magic-only\" (the default, expect
   37). Packs the variant into an immutable tarball, resolves it fresh, then runs
   Unity headless twice: a cold import, then a domain reload that runs
   CoexistenceProbe. The probe runs only on the second pass because a flip
   applies to the next domain load, so a same-session probe reads stale state."
  [variant]
  (let [{:keys [pkg dependency expected]}
        (or (variants variant)
            (throw (ex-info (str "unknown variant: " variant " (dual|magic-only)") {})))]
    (when-not (fs/exists? unity)
      (throw (ex-info (str "Unity 2022.3.62f3 not found: " unity) {})))
    (let [editor-log (coexist-path "Logs" "coexist-noise.editor.log")]
      (println "Variant:" variant "- packing" pkg "(expecting" expected "narration lines)")
      (pack-tarball! pkg (coexist-path "magic-unity.tgz"))
      (write-manifest! dependency)
      (reset-package-cache!)
      (println "Run 1/2: cold import (slow)...")
      (run-editor! (coexist-path "Logs" "coexist-noise.import.log"))
      (println "Run 2/2: domain reload (narration + probe)...")
      (run-editor! editor-log "-executeMethod" "CoexistenceProbe.Run")
      (pp/pprint (result-summary variant expected (parse-log (slurp editor-log)))))))
