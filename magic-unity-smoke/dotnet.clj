(ns dotnet
  "Compile and test the smoke project under MAGIC.

  Invoked from the repo root via `nos dotnet/build` and `nos dotnet/run-tests`.

  Shape: one root namespace, production compiler flags pinned
  (`*direct-linking*`, `*strongly-typed-invokes*`, `*elide-meta*`),
  transitive deps pulled in by `compile`. Output drops into
  Assets/Plugins/Magic which the Unity project picks up automatically.

  `run-tests` exercises the smoke suites under Mono before opening Unity, so
  pure-CLR (non-IL2CPP) regressions surface without a Unity round-trip."
  (:require [magic.flags :as mflags]))

(def root-namespaces
  "Root namespaces to compile. Everything they `require` is compiled
  transitively. Add a namespace here if the smoke runner needs to
  load it directly."
  '[smoke.runner])

(defn build
  "nos dotnet/build

  Wipes Assets/Plugins/Magic and recompiles every root namespace
  (and its transitive deps) into that folder using the same compiler
  flags as production. Unity sees the new DLLs on next focus."
  []
  (binding [*compile-path*                  "Assets/Plugins/Magic"
            *unchecked-math*                true
            *warn-on-reflection*            true
            mflags/*strongly-typed-invokes* true
            mflags/*direct-linking*         true
            mflags/*elide-meta*             false]
    (println "Compile into DLL to:" *compile-path*)
    (when (System.IO.Directory/Exists *compile-path*)
      (System.IO.Directory/Delete *compile-path* true))
    (System.IO.Directory/CreateDirectory *compile-path*)
    (doseq [ns root-namespaces]
      (println "Compiling" ns)
      (compile ns))
    (println "Done.")))

(defn run-tests
  "nos dotnet/run-tests

  Runs the smoke suites under Mono with the production compiler flags
  pinned. Prints the same report SmokeTestRunner shows in Unity, and
  exits non-zero on any failure so CI / shell scripts can chain on it.

  This does not exercise IL2CPP codegen -- only Unity can do that. Use
  it as a fast gate before launching Unity."
  []
  (binding [*unchecked-math*                true
            *warn-on-reflection*            true
            mflags/*strongly-typed-invokes* true
            mflags/*direct-linking*         true
            mflags/*elide-meta*             false]
    (require 'smoke.runner)
    (let [ok?    ((resolve 'smoke.runner/all-pass?))
          report ((resolve 'smoke.runner/report-text))]
      (println report)
      (when-not ok?
        (Environment/Exit 1)))))
