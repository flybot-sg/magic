(ns dotnet
  "Run the smoke suites under MAGIC.

  Compiling is `nos build`, configured in magic.edn. This holds the one task
  that has no built-in: the suites are not clojure.test, they are maps a
  SmokeTestRunner MonoBehaviour reads, so `nos test` cannot drive them."
  (:require [nostrand.tasks :as tasks]))

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
