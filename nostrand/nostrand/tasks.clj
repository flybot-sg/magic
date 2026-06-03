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
            [nostrand.deps.nuget :as nuget]
            [nostrand.deps.basis :as basis]
            [nostrand.deps.submodules :as submodules]
            [clojure.string :as string]
            [clojure.pprint :as pprint]
            [clojure.core.server :as clj-server]
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
