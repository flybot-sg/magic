(ns magic.coexist
  "Babashka driver for the `magic-unity-coexist` regression: prove that both
   Editor runtime states work, headless. One package ships both Clojure runtimes
   and MAGIC_RUNTIME_IN_EDITOR selects the Editor's; this launches Unity in each
   state and asserts the probe fields. See docs/dual-runtimes.md and
   unity-examples/magic-unity-coexist/README.md.

   The constraint metadata the states are selected by is authored and checked in
   magic.unity."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [magic.log :as log]
            [magic.unity :as unity]))

(def ^:private coexist-proj "unity-examples/magic-unity-coexist")
(def ^:private unity-app
  "/Applications/Unity/Hub/Editor/2022.3.62f3/Unity.app/Contents/MacOS/Unity")

(defn- coexist-path [& parts]
  (str/join "/" (cons coexist-proj parts)))

(defn- shipped-clj-count
  "How many *.clj.dll a working MAGIC Editor holds: the package's stdlib plus
   the fixture's consumer DLL, which the stamper brings under the same
   constraint. Counted, not hardcoded, so adding a stdlib namespace does not
   fail the regression."
  []
  (+ (count (fs/glob (str unity/default-pkg "/Runtime/Infrastructure/Export") "*.clj.dll"))
     (count (fs/glob (coexist-path "Assets" "Plugins" "Consumer") "*.clj.dll"))))

(defn- states
  "The two valid Editor states, keyed by the runtime the Editor loads, each with
   the probe fields that must hold."
  []
  (let [n (str (shipped-clj-count))]
    {"stock" {:symbol? false
              :probe   {"symbol"            "unset"
                        "preloaded-clj"     "0"
                        "core-clj-loadable" "false"
                        "clojure-versions"  "[1.11.0.0]"
                        "editor-clj-refs"   "0"
                        "player-clj-refs"   n}}
     "magic" {:symbol? true
              :probe   {"symbol"            "set"
                        "preloaded-clj"     n
                        "core-clj-loadable" "true"
                        "clojure-versions"  "[1.0.0.0]"
                        "editor-clj-refs"   n
                        "player-clj-refs"   n}}}))

(defn- pack-tarball!
  "Pack pkg into a UPM tarball at tgz: a single top-level package/ directory."
  [pkg tgz]
  (let [staging (fs/create-temp-dir {:prefix "magic-coexist-pkg"})
        pkgdir  (str (fs/path staging "package"))]
    (fs/create-dirs pkgdir)
    (shell "cp" "-R" (str pkg "/.") (str pkgdir "/"))
    (shell "tar" "czf" tgz "-C" (str staging) "package")
    (fs/delete-tree staging)))

(defn- reset-package-cache! []
  (fs/delete-if-exists (coexist-path "Packages" "packages-lock.json"))
  (let [cache (coexist-path "Library" "PackageCache")]
    (when (fs/exists? cache)
      (run! fs/delete-tree (fs/glob cache "sg.flybot.magic.unity*")))))

(defn- reset-consumer-metas!
  "Drop the generated metas of the fixture's consumer DLLs so the next import
   re-runs CljPluginConstraints. Re-deriving the stamp every run is what makes
   it a regression rather than one-time setup."
  []
  (run! fs/delete (fs/glob (coexist-path "Assets" "Plugins" "Consumer") "*.meta")))

;; The symbol goes into ProjectSettings.asset rather than through the package's
;; own menu toggle: the plugin constraints are evaluated during the cold import,
;; before any -executeMethod could run, so a toggle call would land one Unity
;; launch too late.
(def ^:private define-symbols-re
  #"(?m)^  scriptingDefineSymbols:(?:[ \t]*\{\}|(?:\n    [^\n]*)*)\n")

(defn- write-define-symbol!
  "Set or clear MAGIC_RUNTIME_IN_EDITOR in the fixture's PlayerSettings, under
   the Standalone group: the fixture's active target, so the one the Editor
   compiles with."
  [enable?]
  (let [path    (coexist-path "ProjectSettings" "ProjectSettings.asset")
        content (slurp path)
        block   (if enable?
                  "  scriptingDefineSymbols:\n    Standalone: MAGIC_RUNTIME_IN_EDITOR\n"
                  "  scriptingDefineSymbols: {}\n")]
    ;; Asserted on the match, not on the content changing: the stock state
    ;; writes the block the fixture is already committed with, so "nothing
    ;; changed" is the normal case there, not a failed substitution.
    (when-not (re-find define-symbols-re content)
      (log/fail! "could not write the define symbol"
                 (str "  " path)
                 "  No scriptingDefineSymbols block matched. The ProjectSettings"
                 "  format changed, or the file is not a Unity project settings asset."))
    (spit path (str/replace-first content define-symbols-re block))))

(defn- run-editor!
  "Launch Unity headless on the coexist project, appending args. A non-zero exit
   is tolerated: the log, not the exit code, is the signal."
  [log & args]
  (fs/create-dirs (coexist-path "Logs"))
  (fs/delete-if-exists log)
  (apply shell {:continue true} unity-app
         "-batchmode" "-quit" "-nographics"
         "-projectPath" coexist-proj "-logFile" log args))

(defn- parse-log [log-text]
  (let [lines      (str/split-lines log-text)
        containing (fn [substr] (filter #(str/includes? % substr) lines))
        probe      (first (containing "[CoexistenceProbe]"))]
    {:narration (count (containing "Assembly is incompatible with the editor"))
     :dedup     (count (containing "Duplicate assembly 'Clojure.dll'"))
     :probe     probe
     ;; key=value pairs off the marker line, so the verdict compares fields
     ;; rather than matching one hardcoded sentence.
     :fields    (into {} (map (juxt second #(nth % 2))
                              (re-seq #"(\S+)=(\S+)" (or probe ""))))}))

(defn- verdict
  "Classify a parsed run for state as [status message]. A missing probe line is
   inconclusive, never a pass: it usually means the package failed to resolve,
   which would also produce 0 narration lines."
  [state {:keys [narration dedup probe fields]}]
  (let [expected (get-in (states) [state :probe])
        wrong    (for [[k v] (sort expected)
                       :when (not= v (get fields k))]
                   (str k "=" (pr-str (get fields k)) " (expected " (pr-str v) ")"))
        noisy    (concat (when (pos? narration) [(str "narration=" narration " (expected 0)")])
                         (when (pos? dedup) [(str "dedup=" dedup " (expected 0)")]))]
    (cond
      (nil? probe)
      [:inconclusive "no [CoexistenceProbe] line; did the package resolve?"]
      (seq (concat wrong noisy))
      [:fail (str "the " state " Editor state is wrong: " (str/join ", " (concat wrong noisy)))]
      :else
      [:pass (str "the " state " Editor state holds, silently")])))

(defn- result-summary
  "The verdict plus the raw counts, the probe line, and its parsed fields, which
   the cross-state check reads."
  [state {:keys [narration dedup probe fields] :as result}]
  (let [[status message] (verdict state result)]
    (array-map
     :state     state
     :status    status
     :message   message
     :narration narration
     :dedup     dedup
     :probe     probe
     :fields    fields)))

(defn- run-state!
  "Drive one Editor state end to end and return its result summary. Two Unity
   launches: a cold import, then a domain reload that runs CoexistenceProbe. The
   probe needs the second pass because the first one is what compiles the
   project against the new define set."
  [state]
  (let [{:keys [symbol?]} (get (states) state)
        editor-log        (coexist-path "Logs" (str "coexist-noise." state ".editor.log"))]
    (println)
    (println (str "=== " state " Editor state: MAGIC_RUNTIME_IN_EDITOR "
                  (if symbol? "set" "unset")))
    (write-define-symbol! symbol?)
    (pack-tarball! unity/default-pkg (coexist-path "magic-unity.tgz"))
    (reset-package-cache!)
    (reset-consumer-metas!)
    (println "Run 1/2: cold import (slow)...")
    (run-editor! (coexist-path "Logs" (str "coexist-noise." state ".import.log")))
    (println "Run 2/2: domain reload (narration + probe)...")
    (run-editor! editor-log "-executeMethod" "CoexistenceProbe.Run")
    (let [summary (result-summary state (parse-log (slurp editor-log)))]
      (pp/pprint summary)
      summary)))

(defn coexist-noise!
  "Regression-check the Editor runtime states on unity-examples/magic-unity-coexist.
   state is \"stock\", \"magic\", or nil for both.

   The two assert opposite things: `stock` (symbol unset, where every install
   starts) must keep the MAGIC runtime out of the Editor, `magic` must boot it.
   Both must be silent, and player references must not move between them."
  [state]
  (when-not (fs/exists? unity-app)
    (throw (ex-info (str "Unity 2022.3.62f3 not found: " unity-app) {})))
  (when (and state (not (contains? (states) state)))
    (throw (ex-info (str "unknown state: " state " (" (str/join "|" (keys (states))) ")") {})))
  (let [results (mapv run-state! (if state [state] ["magic" "stock"]))
        players (distinct (keep #(get-in % [:fields "player-clj-refs"]) results))]
    (println)
    (doseq [{:keys [state status message]} results]
      (println (format "%-6s %-14s %s" state status message)))
    ;; Cross-state: the player set is what ships, and it must not move with the
    ;; Editor selection. Each state's expectation already pins its own count;
    ;; this is what would catch both drifting together.
    (when (> (count results) 1)
      (println (if (= 1 (count players))
                 (str "player-clj-refs identical across states: " (first players))
                 (str "player-clj-refs MOVED between states: " (pr-str players)))))
    (when (or (some #(not= :pass (:status %)) results)
              (and (> (count results) 1) (not= 1 (count players))))
      (log/fail! "coexist-noise failed" ""
                 "One or more Editor states did not hold. The per-state logs are under"
                 (str "  " (coexist-path "Logs") "/")))))
