(ns magic.coexist
  "Babashka driver for the `magic-unity-coexist` regression: launch Unity
   headless in each Editor runtime state and assert the probe fields. The
   constraint metadata is authored and checked in magic.unity; the fixture is
   documented in unity-examples/magic-unity-coexist/README.md."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [magic.log :as log]
            [magic.unity :as unity]))

(def ^:private coexist-proj "unity-examples/magic-unity-coexist")

(defn- coexist-path [& parts]
  (str/join "/" (cons coexist-proj parts)))

(def ^:private tarball (coexist-path "magic-unity.tgz"))

(def ^:private consumer-dir (coexist-path "Assets" "Plugins" "Consumer"))

(def ^:private project-settings (coexist-path "ProjectSettings" "ProjectSettings.asset"))

(def ^:private log-prefix "coexist-noise.")

(defn- log-path [basename]
  (coexist-path "Logs" (str log-prefix basename ".log")))

(defn- report!
  [summary]
  (pp/pprint summary)
  summary)

(defn- field-mismatches [expected fields]
  (for [[k v] (sort expected)
        :when (not= v (get fields k))]
    (str (name k) "=" (pr-str (get fields k)) " (expected " (pr-str v) ")")))

(defn- clj-dlls [dir]
  (mapcat #(fs/glob dir (str "*" %)) unity/clj-extensions))

(defn- shipped-clj-count []
  (+ (count (clj-dlls (unity/runtime-dir :magic)))
     (count (clj-dlls consumer-dir))))

(def ^:private csharp-fields
  "The C# assembly carries no define constraint, so both states report it alike."
  {:csharp-in-domain   "true"
   :csharp-editor-refs "1"})

(def ^:private states
  "The two valid Editor states, keyed by the runtime the Editor loads, each
   with the probe fields that must hold."
  (delay
    (let [n (str (shipped-clj-count))]
      {:clojure-clr {:symbol? false
                     :probe   (merge csharp-fields
                                     {:symbol            "unset"
                                      :preloaded-clj     "0"
                                      :core-clj-loadable "false"
                                      :clojure-versions  "[1.11.0.0]"
                                      :editor-clj-refs   "0"
                                      :player-clj-refs   n})}
       :magic       {:symbol? true
                     :probe   (merge csharp-fields
                                     {:symbol            "set"
                                      :preloaded-clj     n
                                      :core-clj-loadable "true"
                                      :clojure-versions  "[1.0.0.0]"
                                      :editor-clj-refs   n
                                      :player-clj-refs   n})}})))

(defn- pack-tarball!
  "Pack pkg into a UPM tarball at tgz; exclude paths are relative to pkg."
  [pkg tgz & {:keys [exclude]}]
  (let [staging (fs/create-temp-dir {:prefix "magic-coexist-pkg"})
        pkgdir  (str (fs/path staging "package"))]
    (try
      (fs/create-dirs pkgdir)
      (shell "cp" "-R" (str pkg "/.") (str pkgdir "/"))
      (run! #(fs/delete-if-exists (fs/path pkgdir %)) exclude)
      (shell "tar" "czf" tgz "-C" (str staging) "package")
      (finally
        (fs/delete-tree staging)))))

(defn- reset-package-cache! []
  (fs/delete-if-exists (coexist-path "Packages" "packages-lock.json"))
  (let [cache (coexist-path "Library" "PackageCache")]
    (when (fs/exists? cache)
      (run! fs/delete-tree (fs/glob cache "sg.flybot.magic.unity*")))))

(defn- install!
  "Install the package as a consumer gets it: an immutable tarball, resolved
   fresh. Without the cache reset, the previous PackageCache copy is what runs."
  []
  (pack-tarball! unity/default-pkg tarball)
  (reset-package-cache!))

(defn- reset-consumer-metas!
  "Drop the fixture consumer DLLs' generated metas so the next import re-runs
   CljPluginConstraints."
  []
  (run! fs/delete (fs/glob consumer-dir "*.meta")))

;; The symbol goes into ProjectSettings.asset directly: the plugin constraints
;; are evaluated during the cold import, before any -executeMethod could run,
;; so a call to the package's own toggle would land one Unity launch too late.
(def ^:private define-symbols-re
  #"(?m)^  scriptingDefineSymbols:(?:[ \t]*\{\}|(?:\n    [^\n]*)*)\n")

(defn- settings-content
  "The fixture's ProjectSettings.asset, failing if the defines regex no longer
   matches -- a format change fails here, not as a silent no-op write."
  []
  (let [content (slurp project-settings)]
    (when-not (re-find define-symbols-re content)
      (log/fail! "no scriptingDefineSymbols block matched"
                 (str "  " project-settings)
                 "  The ProjectSettings format changed, or the file is not a"
                 "  Unity project settings asset."))
    content))

(defn- write-define-symbol!
  "Set or clear MAGIC_RUNTIME_IN_EDITOR under the Standalone group, the
   fixture's active target."
  [enable?]
  (let [block (if enable?
                "  scriptingDefineSymbols:\n    Standalone: MAGIC_RUNTIME_IN_EDITOR\n"
                "  scriptingDefineSymbols: {}\n")]
    (spit project-settings
          (str/replace-first (settings-content) define-symbols-re block))))

(defn- run-editor!
  "Launch Unity headless on the coexist project, appending args. A non-zero exit
   is tolerated: the log, not the exit code, is the signal."
  [log-file & args]
  (fs/create-dirs (coexist-path "Logs"))
  (fs/delete-if-exists log-file)
  (apply shell {:continue true} unity/unity-app
         "-batchmode" "-quit" "-nographics"
         "-projectPath" coexist-proj "-logFile" log-file args))

;;; Anything the per-check verdicts did not think to look for

(def ^:private log-name-glob (str log-prefix "*.log"))

;; Calibrated against passing runs' logs: none of these appears on a green run.
;; "will not be loaded due to errors" is deliberately absent -- the narration
;; and dedup counters own those lines. So is broken-line: legitimate on cold
;; imports and the upgrade launches, forbidden on the states' reload launch,
;; so per-log counters own it.
(def ^:private error-shapes
  ["Assertion failed" "error CS" "Aborting batchmode" "Exception:"])

;; Unity unloading a plugin whose types failed to resolve -- an unconstrained
;; typed-invoke .clj.dll in a ClojureCLR domain, missing Magic.Runtime.
(def ^:private broken-line "Unloading broken assembly")

(defn- broken-count [log-text]
  (count (filter #(str/includes? % broken-line) (str/split-lines log-text))))

(defn- log-errors [log-text]
  (->> (str/split-lines log-text)
       (filter (fn [line] (some #(str/includes? line %) error-shapes)))
       distinct
       (take 3)
       vec))

(defn- sweep-logs! []
  (run! fs/delete (fs/glob (coexist-path "Logs") log-name-glob)))

(defn- check-logs!
  "Scan every log this run wrote and fail on error-shaped lines: a native
   assertion once sat in these logs through four green runs, because no other
   check was looking for a line nobody had predicted."
  []
  (println)
  (println "=== logs ===")
  (let [found  (into (sorted-map)
                     (for [log-file (fs/glob (coexist-path "Logs") log-name-glob)
                           :let [errs (log-errors (slurp (fs/file log-file)))]
                           :when (seq errs)]
                       [(str (fs/file-name log-file)) errs]))
        [status message]
        (if (seq found)
          [:fail (str "error lines in " (count found) " log(s): " (pr-str found))]
          [:pass "no unexpected error lines in any log"])]
    (report! (array-map :check :logs :status status :message message))))

(defn- marker
  "The first line carrying tag, and its key=value pairs."
  [lines tag]
  (let [line (first (filter #(str/includes? % tag) lines))]
    [line (into {} (map (fn [[_ k v]] [(keyword k) v])
                        (re-seq #"(\S+)=(\S+)" (or line ""))))]))

(defn- parse-log [log-text]
  (let [lines      (str/split-lines log-text)
        containing (fn [substr] (filter #(str/includes? % substr) lines))
        [probe fields] (marker lines "[CoexistenceProbe]")]
    {:narration (count (containing "Assembly is incompatible with the editor"))
     :dedup     (count (containing "Duplicate assembly 'Clojure.dll'"))
     :broken    (count (containing broken-line))
     :probe     probe
     :fields    fields}))

(defn- verdict
  "Classify a parsed run for state as [status message]. A missing probe line is
   inconclusive, never a pass: it usually means the package failed to resolve,
   which would also produce 0 narration lines."
  [state {:keys [narration dedup broken probe fields]}]
  (let [problems (concat (field-mismatches (get-in @states [state :probe]) fields)
                         (when (pos? narration) [(str "narration=" narration " (expected 0)")])
                         (when (pos? dedup) [(str "dedup=" dedup " (expected 0)")])
                         (when (pos? broken) [(str "broken=" broken " (expected 0)")]))]
    (cond
      (nil? probe)
      [:inconclusive "no [CoexistenceProbe] line; did the package resolve?"]
      (seq problems)
      [:fail (str "the " (name state) " Editor state is wrong: " (str/join ", " problems))]
      :else
      [:pass (str "the " (name state) " Editor state holds, silently")])))

;;; The Project Settings > MAGIC toggle

(defn- defines-block
  "The scriptingDefineSymbols block, verbatim. Compared instead of the whole
   file: Unity rewrites unrelated parts of a settings asset on its own schedule."
  []
  (re-find define-symbols-re (settings-content)))

(defn- defines-by-group
  "A defines block as group -> define string, dropping groups that declare
   none: Unity has no API to *remove* a group's entry, only to set it empty, so
   a set-then-cleared group reads back as an inert `Standalone: ` that
   byte-identity would flag."
  [block]
  (into {} (for [line (rest (str/split-lines (or block "")))
                 :let [[_ group defines] (re-matches #"\s{4}([^:]+):\s*(.*)" line)]
                 :when (and group (not (str/blank? defines)))]
             [group (str/trim defines)])))

(defn- toggle-verdict [fields block-before block-after]
  (let [seed          (:seed fields)
        groups-before (defines-by-group block-before)
        groups-after  (defines-by-group block-after)
        problems      (concat (field-mismatches {:after-set           (str seed ";" unity/magic-symbol)
                                                 :after-clear         seed
                                                 :enabled-after-set   "true"
                                                 :enabled-after-clear "false"}
                                                fields)
                              (when-not (= groups-before groups-after)
                                [(str "it changed some group's defines: "
                                      (pr-str groups-before) " -> " (pr-str groups-after))]))]
    (cond
      (nil? seed)
      [:inconclusive "no [EditorRuntimeProbe] line; did the package resolve?"]
      (seq problems)
      [:fail (str "the toggle is wrong: " (str/join ", " problems))]
      :else
      [:pass "the toggle appends and removes the symbol, preserving define order"])))

;;; The upgrade path

(defn- pack-without-constrainer!
  "The package minus CljPluginConstraints.cs, standing in for a release from
   before the import-time constrainer existed."
  [tgz]
  (pack-tarball! unity/default-pkg tgz
                 :exclude ["Editor/CljPluginConstraints.cs"
                           "Editor/CljPluginConstraints.cs.meta"]))

(defn- consumer-metas
  "Each fixture consumer DLL's meta state: :constrained, :unconstrained, or :no-meta."
  []
  (into (sorted-map)
        (for [dll (clj-dlls consumer-dir)
              :let [meta-file (str dll ".meta")]]
          [(str (fs/file-name dll))
           (cond
             (not (fs/exists? meta-file))                         :no-meta
             (str/includes? (slurp meta-file) unity/magic-symbol) :constrained
             :else                                                :unconstrained)])))

(def ^:private constrainer-tag "[Magic.Unity/CljPluginConstraints]")

(defn- reconcile-report
  "What CljPluginConstraints.Reconcile logged on the upgrade launch."
  [log-text]
  (let [lines (filter #(str/includes? % constrainer-tag) (str/split-lines log-text))]
    {:brought (some->> lines
                       (keep #(second (re-find #"brought (\d+) already-imported" %)))
                       first
                       parse-long)
     :refused (first (filter #(str/includes? % "would not take the constraint") lines))
     :lines   (count lines)}))

(defn- upgrade-verdict
  "Classify the upgrade as [status message]. The mechanism is asserted first,
   because the end state alone cannot see it: were a package install to dirty
   Assets/, OnPreprocessAsset would constrain everything on the upgrade launch,
   every other assertion here would pass, and Reconcile would be dead code
   nobody notices. Its own log line is what pins which entry point ran."
  [before after fields reconcile broken]
  (let [not-baseline (remove #(= :unconstrained (val %)) before)
        not-constrained (remove #(= :constrained (val %)) after)
        brought      (:brought reconcile)
        ;; Exact: Consumer/ holds the fixture's only clj assemblies under Assets/.
        mechanism    (cond
                       (nil? brought)
                       (str "no \"" constrainer-tag " brought N already-imported\" line on the "
                            "upgrade launch. Either it did not run, or the import callback got "
                            "there first -- in which case Unity now dirties Assets/ on a package "
                            "install and Reconcile is dead code")
                       (not= (count before) brought)
                       (str "Reconcile brought " brought " assembl" (if (= 1 brought) "y" "ies")
                            " but the baseline left " (count before)
                            " unconstrained, so something else constrained the rest"))
        leaked       (field-mismatches {:preloaded-clj "0" :editor-clj-refs "0"} fields)
        ;; Two mechanisms each keep this at 1: Reconcile pre-constrains the
        ;; metas, and the import callback batches its report behind a delayCall.
        ;; A regression in one is invisible until the other goes too.
        noisy        (when (and brought
                                (nil? (:refused reconcile))
                                (not= 1 (:lines reconcile)))
                       (str "Reconcile logged " (:lines reconcile)
                            " lines where 1 says it, so the import callback is logging per "
                            "assembly through the reconcile's reimports"))]
    (cond
      (empty? before)
      [:inconclusive "no consumer DLL in the fixture to upgrade"]
      (seq not-baseline)
      [:inconclusive (str "the no-constrainer package left " (pr-str (into {} not-baseline))
                          ", so the baseline is not an unconstrained one")]
      (zero? (:upgrade broken))
      [:inconclusive (str "the upgrade launch logged no \"" broken-line "\" line, so nothing "
                          "exercises the broken-load path whose convergence this check asserts; "
                          "the fixture needs a typed-invoke .clj.dll")]
      (nil? (:preloaded-clj fields))
      [:inconclusive "no [CoexistenceProbe] line after the upgrade; did the package resolve?"]
      ;; A refusal also trips not-constrained; its warning is the better message.
      (:refused reconcile)
      [:fail (str "Reconcile could not constrain every assembly: " (:refused reconcile))]
      (seq not-constrained)
      [:fail (str "the upgrade left " (pr-str (into {} not-constrained))
                  ": an already-imported consumer assembly stays visible to a ClojureCLR Editor, "
                  "binding a MAGIC runtime that is not loaded")]
      mechanism
      [:fail (str "the metas are constrained but not by the reconcile under test: " mechanism)]
      (pos? (:converged broken))
      [:fail (str "the launch after the upgrade still logs \"" broken-line "\" ("
                  (:converged broken) " line(s)): the constraint was written but the Editor "
                  "kept loading the assembly")]
      (seq leaked)
      [:fail (str "every meta was constrained but the Editor still holds the assembly: "
                  (str/join ", " leaked)
                  ". Unity recorded the constraint and never re-evaluated eligibility")]
      ;; Last: only the consumer's console is wrong here, not their project.
      noisy
      [:fail (str "the upgrade works but is not quiet: " noisy)]
      :else
      [:pass (str (count after) " already-imported consumer assembl"
                  (if (= 1 (count after)) "y" "ies")
                  " picked up the constraint from Reconcile, and the Editor dropped "
                  (if (= 1 (count after)) "it" "them"))])))

(defn- check-upgrade!
  "Assert an *already imported* consumer DLL picks up the constraint when the
   package that constrains it arrives -- the path every existing consumer takes.
   Installing a package does not dirty assets under Assets/, so the import
   callback never re-runs for them: measured before Reconcile existed, an
   upgrade left the consumer DLL Editor-eligible with no log line at all."
  []
  (println)
  (println "=== upgrade path ===")
  (let [after-log (log-path "upgrade-after")]
    ;; The ClojureCLR state, where an unconstrained consumer DLL is the live hazard.
    (write-define-symbol! false)
    (println "Run 1/3: import under a package with no constrainer...")
    (pack-without-constrainer! tarball)
    (reset-package-cache!)
    (reset-consumer-metas!)
    (run-editor! (log-path "upgrade-before"))
    (let [before (consumer-metas)]
      (println "Run 2/3: upgrade to the real package, consumer metas left in place...")
      ;; install! and deliberately not reset-consumer-metas!: that those metas
      ;; survive the upgrade untouched is the whole question.
      (install!)
      (run-editor! after-log)
      (let [after (consumer-metas)
            after-text (slurp after-log)
            ;; Read from run 2: this is the launch the reconcile runs on, and
            ;; run 3 is a fresh domain that finds nothing left to do.
            reconcile (reconcile-report after-text)
            ;; A launch of its own: the reconcile's reimports reload the domain,
            ;; and a probe racing that reports whichever side it lands on.
            probe-log (log-path "upgrade-probe")
            _ (println "Run 3/3: fresh launch -- did the constraint take effect?")
            _ (run-editor! probe-log "-executeMethod" "CoexistenceProbe.Run")
            probe-text (slurp probe-log)
            broken {:upgrade   (broken-count after-text)
                    :converged (broken-count probe-text)}
            [line fields] (marker (str/split-lines probe-text) "[CoexistenceProbe]")
            [status message] (upgrade-verdict before after fields reconcile broken)]
        (report! (array-map :check :upgrade :status status :message message
                            :before before :after after
                            :reconcile (:brought reconcile) :broken broken
                            :probe line))))))

(defn- check-toggle!
  "Exercise the Project Settings > MAGIC runtime toggle. Nothing else covers
   it: the state runs bypass the toggle, writing the symbol straight into
   ProjectSettings.asset (see define-symbols-re)."
  []
  (println)
  (println "=== Editor Runtime toggle ===")
  (let [log-file (log-path "toggle")
        before   (defines-block)
        _        (run-editor! log-file "-executeMethod" "EditorRuntimeProbe.Run")
        [line fields] (marker (str/split-lines (slurp log-file)) "[EditorRuntimeProbe]")
        after    (defines-block)
        [status message] (toggle-verdict fields before after)]
    ;; The probe restores the block in a finally, but a crashed launch would
    ;; leave the fixture's tracked settings dirty. Put it back either way.
    (when-not (= before after)
      (spit project-settings
            (str/replace-first (settings-content) define-symbols-re
                               (str/re-quote-replacement before))))
    (report! (array-map :check :toggle :status status :message message :probe line))))

(defn- run-state!
  "Drive one Editor state end to end. The probe needs a second launch because
   the first one is what compiles the project against the new define set."
  [state]
  (let [{:keys [symbol?]} (get @states state)
        editor-log        (log-path (str (name state) ".editor"))]
    (println)
    (println (str "=== " (name state) " Editor state: MAGIC_RUNTIME_IN_EDITOR "
                  (if symbol? "set" "unset")))
    (write-define-symbol! symbol?)
    (install!)
    (reset-consumer-metas!)
    (println "Run 1/2: cold import (slow)...")
    (run-editor! (log-path (str (name state) ".import")))
    (println "Run 2/2: domain reload (narration + probe)...")
    (run-editor! editor-log "-executeMethod" "CoexistenceProbe.Run")
    (let [{:keys [narration dedup broken probe fields] :as result} (parse-log (slurp editor-log))
          [status message] (verdict state result)]
      (report! (array-map :state state :status status :message message
                          :narration narration :dedup dedup :broken broken
                          :probe probe :fields fields)))))

(defn- report-outcome!
  "Print one line per check and fail the task on anything that is not a pass."
  [results checks]
  (let [players (distinct (keep #(get-in % [:fields :player-clj-refs]) results))
        moved?  (and (next results) (not= 1 (count players)))]
    (println)
    (doseq [{:keys [state check status message]} checks]
      (println (format "%-11s %-14s %s" (name (or state check)) status message)))
    ;; Each state's expectation already pins its own player-clj-refs count;
    ;; this catches both drifting together.
    (when (next results)
      (println (if moved?
                 (str "player-clj-refs MOVED between states: " (pr-str players))
                 (str "player-clj-refs identical across states: " (first players)))))
    (when (or moved? (some #(not= :pass (:status %)) checks))
      (log/fail! "coexist-noise failed" ""
                 "An Editor state, the upgrade path, the runtime toggle, or the logs"
                 "themselves did not hold. The logs are under"
                 (str "  " (coexist-path "Logs") "/")))))

(defn coexist-noise!
  "Regression-check the Editor runtime states on unity-examples/magic-unity-coexist.
   state-arg is the command-line \"clojure-clr\", \"magic\", or nil for both, then
   the upgrade path and the Editor Runtime toggle.

   The two assert opposite things: `clojure-clr` (symbol unset, where every install
   starts) must keep the MAGIC runtime out of the Editor, `magic` must boot it.
   Both must be silent, and player references must not move between them."
  [state-arg]
  (when-not (fs/exists? unity/unity-app)
    (log/fail! (str "Unity " unity/unity-version " not found")
               (str "  " unity/unity-app)
               "  Override the path with MAGIC_UNITY_APP."))
  (let [state (some-> state-arg keyword)]
    (when (and state (not (contains? @states state)))
      (log/fail! (str "unknown state: " state-arg)
                 (str "  valid states: " (str/join " | " (map name (keys @states))))))
    (sweep-logs!)
    (let [results (mapv run-state! (if state [state] [:magic :clojure-clr]))]
      ;; Order matters: check-upgrade! ends with the real package resolved, which
      ;; check-toggle! reuses instead of repacking, and check-logs! is last
      ;; because it reads what every launch before it wrote.
      (report-outcome! results (into results [(check-upgrade!) (check-toggle!) (check-logs!)])))))
