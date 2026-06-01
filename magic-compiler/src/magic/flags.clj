(ns magic.flags
  "Every knob that controls MAGIC compilation, as dynamic vars. This is the
  single configuration surface: clients bind these, the compiler reads them.
  Spells are flags too, so there is one mechanism, not three.")

;; Emission

(def ^:dynamic *direct-linking*
  "Invocations of non-variadic non-dynamic fns lower to direct invokeStatic
  calls, bypassing Var lookup."
  false)

(def ^:dynamic *strongly-typed-invokes*
  "Invocations lower to invokeTyped interface calls when the fn's
  Magic.Function type is statically known."
  false)

(def ^:dynamic *elide-meta*
  "Metadata expressions are dropped during analysis."
  false)

(def ^:dynamic *legacy-dynamic-callsites*
  "Use the legacy dynamic callsite implementation instead of CallSite."
  false)

;; Spells. Each spell is a (fn [compilers] compilers') that rewrites the
;; compiler map; a flag toggles whether it is applied. lift-vars and
;; lift-keywords are required for correct, efficient codegen, so default on.

(def ^:dynamic *lift-vars*
  "Apply the lift-vars spell (cache Var references in static fields)."
  true)

(def ^:dynamic *lift-keywords*
  "Apply the lift-keywords spell (cache keyword constants in static fields)."
  true)

(def ^:dynamic *sparse-case*
  "Apply the sparse-case spell (compile case sparsely, avoiding embedded hash
  results that differ across runtimes)."
  false)
