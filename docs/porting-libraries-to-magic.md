# Porting a Clojure library to MAGIC

How to take an existing Clojure library and compile and test it on the CLR
with MAGIC. Assumes `nos` is installed (see the [Install](../README.md#install)
section).

The work is in three parts: make the source cross-platform, declare paths and
deps in `deps.edn`, and add a `dotnet.clj` with build and test tasks.

## 1. Make the source cross-platform

MAGIC runs the same source the JVM does, through Clojure
[reader conditionals](https://clojure.org/guides/reader_conditionals). Rename
`.clj` files to `.cljc` and gate the platform-specific parts (interop,
`require`/`import`, type hints) behind `:cljr`:

```clojure
(defn round
  #?(:clj  [n]
     :cljr [^double n])
  #?(:clj  (Math/round n)
     :cljr (Math/Round n)))
```

Only `:clj` and `:cljr` branches are needed; there is no third platform to
default to. Keep type hints inside `:cljr` so the JVM stays dynamically typed
and your existing tests keep passing. The full flag and type-hint surface is in
[`magic-compiler/src/magic/flags.clj`](../magic-compiler/src/magic/flags.clj).

## 2. deps.edn

`nos` resolves `deps.edn` natively (git and local deps; it clones git deps into
`~/.nostrand/gitlibs` at boot). Declare your source `:paths` and put test
sources under a `:test` alias so they only load when testing:

```clojure
{:paths ["src"]
 :deps  {}
 :aliases {:test {:extra-paths ["test"]}}}
```

### Swap JVM dependencies for CLR forks

A dependency (yours or one pulled transitively) may be JVM-only on Maven, while
a CLR fork of it exists as a git repo. `nos` skips Maven coords, so the JVM one
never resolves; point at the fork with `:override-deps` under a `:clr` alias.
`:override-deps` swaps a lib's coord wherever it appears in the tree, including
transitive sightings, without adding it as a root dependency:

```clojure
{:paths ["src"]
 :deps  {org.clojure/test.check {:mvn/version "1.1.1"}}
 :aliases
 {:clr {:override-deps
        {;; direct dep: use the MAGIC fork of test.check on the CLR
         org.clojure/test.check {:git/url "https://github.com/flybot-sg/clr.test.check"
                                 :git/sha "..."}
         ;; transitive dep: a generated test asserts with matcho, whose JVM
         ;; build is Maven-only; the flybot-sg fork is git
         healthsamurai/matcho   {:git/url "https://github.com/flybot-sg/matcho"
                                 :git/sha "..."}}}}}
```

Activate it with `nos` either by listing it in `:nos/aliases [:clr]` (applied at
boot) or by passing `:aliases [:clr ...]` from your `dotnet.clj` task.

## 3. dotnet.clj

Put a `dotnet.clj` at the project root. `nostrand.tasks` provides the build and
test tasks, so a project only states what is specific to it. Pick one of the
three shapes below.

### Derive namespaces from deps.edn (recommended)

With no explicit list, the namespaces are read off the source paths the given
aliases contribute. `build` compiles the base `:paths`; `run-tests` adds the
`:test` alias paths:

```clojure
(ns dotnet
  (:require [nostrand.tasks :as tasks]))

(defn build     [] (tasks/compile-project))
(defn run-tests [] (tasks/run-clojure-tests :aliases [:test]))
```

### Exclude namespaces that must not load on the CLR

A source tree often carries namespaces that cannot load under MAGIC (a
ClojureScript-only namespace, JVM-only test tooling). Keep deriving and drop
them with `:exclude`:

```clojure
(ns dotnet
  (:require [nostrand.tasks :as tasks]))

(def cljs-only
  '[my.lib.frontend.cljs])

(defn build     [] (tasks/compile-project :exclude cljs-only))
(defn run-tests [] (tasks/run-clojure-tests :aliases [:test] :exclude cljs-only))
```

### Hardcode the namespace list

When you want exact control (a single compile root, a vendored namespace not
reachable by `require`, or a test dir where naming the few real suites is
clearer than excluding the rest), pass `:namespaces`:

```clojure
(ns dotnet
  (:require [nostrand.tasks :as tasks]))

(def prod-namespaces '[my.lib.core my.lib.api])
(def test-namespaces '[my.lib.core-test my.lib.api-test])

(defn build     [] (tasks/compile-project :namespaces prod-namespaces))
(defn run-tests [] (tasks/run-clojure-tests
                    :namespaces (concat prod-namespaces test-namespaces)
                    :aliases    [:test]))
```

### Options

| Option        | `compile-project` | `run-clojure-tests` | Meaning                                                        |
|---------------|:-----------------:|:-------------------:|----------------------------------------------------------------|
| `:namespaces` | yes               | yes                 | Explicit namespaces, overrides derivation                      |
| `:exclude`    | yes               | yes                 | Namespaces to drop from the derived (or explicit) set          |
| `:aliases`    | yes               | yes                 | deps.edn aliases to activate, e.g. `[:test]`                   |
| `:flags`      | yes               | yes                 | `var->value` binding map (default `tasks/production-flags`)    |
| `:out`        | yes               | no                  | `*compile-path*` (default `"build"`)                           |
| `:clean?`     | yes               | no                  | Wipe `:out` first (Unity dirs that must not keep stale DLLs)   |
| `:exit?`      | no                | yes                 | `Environment/Exit 1` on any failure or error (default true)    |

`tasks/production-flags` is the flag set shipped projects compile under
(`*direct-linking*`, `*strongly-typed-invokes*`, `*elide-meta*`,
`*unchecked-math*`, `*warn-on-reflection*`). It is a plain map, so a test run
that needs redefinable vars overrides it:

```clojure
(ns dotnet
  (:require [magic.flags :as mflags]
            [nostrand.tasks :as tasks]))

(defn run-tests []
  (tasks/run-clojure-tests
   :aliases [:test]
   :flags   (assoc tasks/production-flags
                   #'mflags/*direct-linking*         false
                   #'mflags/*strongly-typed-invokes* false)))
```

## 4. Build and test

```bash
nos dotnet/build        # compiles to ./build (or :out)
nos dotnet/run-tests    # requires the namespaces, runs clojure.test, exits non-zero on failure
```

`run-tests` executes under Mono and does not cover IL2CPP codegen; for Unity,
an actual IL2CPP build is the only way to catch AOT-only regressions (see
[`magic-unity-smoke`](../magic-unity-smoke)).

## Rich-comment-tests on the CLR

If a library's tests are written as
[rich comment tests](https://github.com/robertluo/rich-comment-tests) (RCT),
they cannot run on the CLR as-is: RCT extracts assertions at runtime using
`rewrite-clj` and other JVM-only machinery.
[flybot-sg/rct-clr](https://github.com/flybot-sg/rct-clr) bridges this: on the
JVM it reads the rich comments and emits a plain `.cljc` test file of ordinary
`deftest` forms that assert with [matcho](https://github.com/flybot-sg/matcho).
MAGIC then runs that generated file with just `clojure.test` and `matcho.core`.

The split shows up in `dotnet.clj`: test only the generated namespace and leave
the RCT source out, since it would drag the JVM-only tooling in. `matcho` is the
one extra runtime dependency, supplied by the `:clr` override above:

```clojure
(def test-namespaces
  ;; the generated deftests; the RCT source ns (rich comments) stays JVM-only
  '[my.lib.generated-test])

(defn run-tests []
  (tasks/run-clojure-tests
   :namespaces (concat prod-namespaces test-namespaces)
   :aliases    [:clr :test]))
```

The `clojure.test` assertion count on the CLR will not be one-for-one with a JVM
run that executes the rich comments directly: RCT and the generated `deftest`s
tally assertions differently (one `=>` expectation can expand to several matcho
checks). The count differs; the same expectations are all verified.

## Reference

[flybot-sg/clr.test.check](https://github.com/flybot-sg/clr.test.check) is a fork
of `clojure/test.check` ported with this workflow: reader conditionals
throughout, and a `dotnet.clj` that derives its namespaces from `deps.edn`.
