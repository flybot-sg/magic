(ns
 ^{:author "Ramsey Nasser"
   :doc    "Built in nostrand tasks, available from the command line as unqualified functions"}
 nostrand.tasks
  (:import
   [Nostrand Nostrand]
   [System.IO Directory File]
   [System.Threading Thread ThreadStart]
   [System.Reflection AssemblyInformationalVersionAttribute])
  (:require [nostrand.repl :as repl]
            [nostrand.core :as nos]
            [nostrand.deps.nuget :as nuget]
            [nostrand.deps.basis :as basis]
            [nostrand.deps.submodules :as submodules]
            [magic.flags :as mflags]
            [clojure.string :as string]
            [clojure.pprint :as pprint]
            [clojure.core.server :as clj-server]
            [clojure.test :as test]
            clojure.repl))

(defn- msg
  ([header body]
   (Nostrand.Terminal/Message header body))
  ([header body color]
   (Nostrand.Terminal/Message header body color)))

(defn version []
  (msg "Nostrand" (Nostrand/Version) ConsoleColor/Cyan)
  (msg "Clojure.Runtime" (Nostrand/ClojureRuntimeVersion) ConsoleColor/Cyan)
  (msg "Magic.Runtime" (Nostrand/MagicRuntimeVersion) ConsoleColor/Cyan)
  (msg "Clojure" (clojure-version) ConsoleColor/Cyan)
  (msg "Runtime" (str (Environment/get_Version)
                      " (" (Environment/get_OSVersion) ")")
       ConsoleColor/DarkGray))

(defn cli-repl
  ([] (cli-repl nil))
  ([args]
   (repl/cli args)))

(defn socket-repl [args]
  (repl/socket args))

(defn repl
  ([]
   (version)
   #_(repl/repl 11217)
   (cli-repl))
  ([port]
   (version)
   #_(repl/repl port)
   (cli-repl)))

(defn tasks []
  (let [ns-syms
        (->> (Directory/GetFiles "." "*.clj")
             (map #(-> %
                       (string/replace "./" "")
                       (string/replace ".clj" "")
                       symbol)))]
    (doseq [s ns-syms]
      (require s)
      (let [fns (->> s
                     find-ns
                     ns-publics
                     vals)]
        (doseq [f fns]
          ((var clojure.repl/print-doc) (meta f)))))))

(defn clojure-socket-repl [args]
  (print "Starting Clojure socket repl...")
  (let [opts (merge
              {:accept `clj-server/repl
               :name "Clojure socket repl"}
              args)]
    (clj-server/start-server opts)
    (println "done ")
    (println "Started socket repl with Options: " opts)
    (repl)))

(defn print-basis
  "Resolve ./deps.edn with the given alias keywords and pretty-print the
  basis (paths + resolved libs) without compiling. Clones git deps into
  the cache as a side effect.
  Usage: nos print-basis            ; base :deps only
         nos print-basis :clr :test ; with aliases"
  [& aliases]
  (let [{:keys [paths libs classpath-paths]} (basis/create-basis "deps.edn" (vec aliases))]
    (pprint/pprint
     {:aliases         (vec aliases)
      :paths           paths
      :classpath-count (count classpath-paths)
      :libs            (into (sorted-map)
                             (map (fn [[lib {:keys [coord paths]}]]
                                    (let [{:git/keys [sha tag] :local/keys [root]} coord]
                                      [lib (cond-> {:paths (vec paths)}
                                             sha  (assoc :git/sha sha)
                                             tag  (assoc :git/tag tag)
                                             root (assoc :local/root root))])))
                             libs)})))

(defn gitmodules-paths
  "Inspect the deps.edn :paths a submodule layout would contribute: derive a
  flat vector from ./.gitmodules and print it as {:paths [...]}. An optional
  root-prefix restricts the result to submodules under that path (e.g. a vendor
  dir holding the dependency source). Previews or materializes the same :paths
  the :nos/submodule-paths boot key derives dynamically.
  Usage: nos gitmodules-paths        ; every submodule
         nos gitmodules-paths libs   ; only submodules under libs/"
  ([] (gitmodules-paths nil))
  ([root-prefix]
   (if (File/Exists ".gitmodules")
     (pprint/pprint
      {:paths (submodules/submodule-paths (slurp ".gitmodules")
                                          (when root-prefix (str root-prefix)))})
     (println "No .gitmodules in" (Directory/GetCurrentDirectory)))))

(defn nuget-push
  "Pack and Push NuGet Package to git host repo.
  - `git-host`     : 'github' or 'gitlab'
  - `with-build?`  : true by default
  - `configuration`: 'Release' by default."
  [git-host-type with-build? configuration]
  (binding [*compile-path* "build"]
    (nuget/pack-and-push-nuget git-host-type
                               :with-build? with-build?
                               :configuration configuration)))

(def production-flags
  "The compilation flags shipped MAGIC projects build under, as a var->value
  map ready for `clojure.core/with-bindings` or the `:flags` option of
  `compile-project` / `run-clojure-tests`. Kept open on purpose: a task that
  needs to deviate assoc's onto it (test runs that rely on redefinable vars
  set :direct-linking and :strongly-typed-invokes false), or passes its own
  map. Shared so consumer dotnet.clj tasks do not each restate the set."
  {#'*unchecked-math*                true
   #'*warn-on-reflection*            true
   #'mflags/*strongly-typed-invokes* true
   #'mflags/*direct-linking*         true
   #'mflags/*elide-meta*             false})

(defn- file-namespace
  "The namespace a Clojure source file declares, or nil if its first form is
  not an `ns` form. Reads only that first form; :read-cond :preserve keeps
  reader conditionals (common in .cljc) from tripping the reader."
  [path]
  (let [form (binding [*read-eval* false]
               (read-string {:read-cond :preserve} (slurp path)))]
    (when (and (seq? form) (= 'ns (first form)))
      (second form))))

(defn- paths-namespaces
  "Namespace symbols declared by the .clj/.cljc files under the given source
  dirs, recursively. Missing dirs are skipped."
  [paths]
  (->> paths
       (filter #(System.IO.Directory/Exists %))
       (mapcat (fn [dir]
                 (concat (System.IO.Directory/GetFiles dir "*.clj"  System.IO.SearchOption/AllDirectories)
                         (System.IO.Directory/GetFiles dir "*.cljc" System.IO.SearchOption/AllDirectories))))
       (keep file-namespace)
       distinct
       vec))

(defn project-namespaces
  "Namespace symbols on the source paths deps.edn contributes for the given
  aliases (base :paths plus each alias's :extra-paths) -- the set a task would
  compile or test instead of hand-listing it. Read-only; does not touch the
  load path.
      (tasks/project-namespaces [:test])"
  ([aliases] (project-namespaces "deps.edn" aliases))
  ([deps-file aliases]
   (paths-namespaces (:paths (basis/create-basis deps-file (vec aliases))))))

(defn- task-namespaces
  "Resolve the namespace set a task runs over: an explicit `namespaces`
  override wins; otherwise derive from the source paths `aliases` contributes,
  reusing an already-resolved basis when one is in hand. `exclude` drops
  namespaces from either source (e.g. test-dir tooling that must not load)."
  [namespaces aliases exclude basis]
  (->> (or namespaces
           (paths-namespaces (:paths (or basis (basis/create-basis "deps.edn" (vec aliases))))))
       (remove (set exclude))))

(defn compile-project
  "Compile a project's namespaces (and their transitive requires) into a DLL
  dir. With no :namespaces the set is derived from the source paths :aliases
  contributes (see `project-namespaces`); pass :namespaces to state it instead
  (e.g. a single root, or a vendored lib not reachable by `require`). Options:
    :namespaces  explicit namespaces to compile (overrides derivation)
    :exclude     namespaces to drop from the set
    :aliases     deps.edn aliases to activate, e.g. [:clr]
    :out         *compile-path* (default \"build\")
    :clean?      wipe :out first (default false; for Unity dirs that must not
                 carry stale DLLs)
    :flags       var->value binding map (default `production-flags`)
  Drop-in for a consumer's `nos dotnet/build`:
      (defn build [] (tasks/compile-project :aliases [:clr]))"
  [& {:keys [namespaces exclude aliases out clean? flags]
      :or   {out "build" clean? false flags production-flags}}]
  (let [basis (when (seq aliases) (nos/establish-deps-edn "deps.edn" aliases))
        nses  (task-namespaces namespaces aliases exclude basis)]
    (with-bindings (assoc flags #'*compile-path* out)
      (println "Compiling into DLL dir:" *compile-path*)
      (when (and clean? (System.IO.Directory/Exists *compile-path*))
        (System.IO.Directory/Delete *compile-path* true))
      (System.IO.Directory/CreateDirectory *compile-path*)
      (doseq [ns nses]
        (println "Compiling" ns)
        (compile ns))
      (println "Done."))))

(defn run-clojure-tests
  "Require a project's namespaces and run every loaded clojure.test suite.
  With no :namespaces the set is derived from the source paths :aliases
  contributes (see `project-namespaces`); pass :namespaces to state it instead,
  or :exclude to drop test-dir tooling that should not load. Options:
    :namespaces  explicit namespaces to require (overrides derivation)
    :exclude     namespaces to drop from the set
    :aliases     deps.edn aliases to activate, e.g. [:clr :test] (so the test
                 source paths land on the load path before `require`)
    :flags       var->value binding map (default `production-flags`)
    :exit?       `Environment/Exit 1` on any failure/error so CI can chain
                 (default true)
  Returns the clojure.test summary map.
  Drop-in for a consumer's `nos dotnet/run-tests`:
      (defn run-tests [] (tasks/run-clojure-tests :aliases [:clr :test]))"
  [& {:keys [namespaces exclude aliases flags exit?]
      :or   {flags production-flags exit? true}}]
  (let [basis (when (seq aliases) (nos/establish-deps-edn "deps.edn" aliases))
        nses  (task-namespaces namespaces aliases exclude basis)]
    (with-bindings flags
      (doseq [ns nses]
        (require ns))
      (let [{:keys [fail error] :as summary} (test/run-all-tests)]
        (when (and exit? (or (pos? fail) (pos? error)))
          (Environment/Exit 1))
        summary))))
