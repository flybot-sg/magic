(ns dotnet
  "Compile and test the smoke project under MAGIC.

  Invoked from the repo root via `nos dotnet/build` and `nos dotnet/run-tests`.

  Shape: one root namespace, production compiler flags pinned
  (`*direct-linking*`, `*strongly-typed-invokes*`, `*elide-meta*`),
  transitive deps pulled in by `compile`. Output drops into
  Assets/Plugins/Magic which the Unity project picks up automatically.

  `run-tests` exercises the smoke suites under Mono before opening Unity, so
  pure-CLR (non-IL2CPP) regressions surface without a Unity round-trip."
  (:require [nostrand.tasks :as tasks]))

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
  (tasks/compile-project :namespaces root-namespaces :out "Assets/Plugins/Magic" :clean? true))

(defn run-tests
  "nos dotnet/run-tests

  Runs the smoke suites under Mono with the production compiler flags
  pinned. Prints the same report SmokeTestRunner shows in Unity, and
  exits non-zero on any failure so CI / shell scripts can chain on it.

  This does not exercise IL2CPP codegen -- only Unity can do that. Use
  it as a fast gate before launching Unity."
  []
  (with-bindings tasks/production-flags
    (require 'smoke.runner)
    (let [ok?    ((resolve 'smoke.runner/all-pass?))
          report ((resolve 'smoke.runner/report-text))]
      (println report)
      (when-not ok?
        (Environment/Exit 1)))))
