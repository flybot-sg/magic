(ns magic.unity
  "Babashka helpers for the MAGIC Unity package: sync its UPM version and drive
   the `magic-unity-coexist` repro."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def ^:private default-pkg "magic-unity")
(def ^:private coexist-proj "unity-examples/magic-unity-coexist")
(def ^:private unity
  "/Applications/Unity/Hub/Editor/2022.3.62f3/Unity.app/Contents/MacOS/Unity")

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
