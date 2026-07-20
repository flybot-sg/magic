# Porting a Clojure library to MAGIC

How to take an existing Clojure library and compile and test it on the CLR with MAGIC. Assumes `nos` is installed (see the [Install](../README.md#install) section).

The work is in three parts: make the source cross-platform, declare paths and deps in `deps.edn`, and configure build and test in `magic.edn`.

## 1. Make the source cross-platform

MAGIC runs the same source the JVM does, through Clojure [reader conditionals](https://clojure.org/guides/reader_conditionals). Rename `.clj` files to `.cljc` and gate the platform-specific parts (interop, `require`/`import`, type hints) behind `:cljr`:

```clojure
(defn round
  #?(:clj  [n]
     :cljr [^double n])
  #?(:clj  (Math/round n)
     :cljr (Math/Round n)))
```

Only `:clj` and `:cljr` branches are needed; there is no third platform to default to. Keep type hints inside `:cljr` so the JVM stays dynamically typed and your existing tests keep passing. The full flag and type-hint surface is in [`magic-compiler/src/magic/flags.clj`](../magic-compiler/src/magic/flags.clj).

For the source patterns in depth (value-type and reference-type hints, records and protocols, the host APIs that genuinely differ, and the Clojure 1.10 stdlib surface) see [Writing cross-platform Clojure](./writing-cross-platform-clojure.md).

## 2. deps.edn

`nos` resolves `deps.edn` natively (git and local deps; it clones git deps into `$GITLIBS` if set, else `~/.nostrand/gitlibs`, at boot). Declare your source `:paths` and put test sources under a `:test` alias so they only load when testing:

```clojure
{:paths ["src"]
 :deps  {}
 :aliases {:test {:extra-paths ["test"]}}}
```

When a library needs CLR-specific dependencies (a CLR fork of a JVM library, for instance), declare them either in a `deps-clr.edn` or a `:clr` alias. See [Declaring CLR dependencies](./clr-dependency-files.md) for which one fits. If the library ships a precompiled C# assembly alongside its Clojure source, it also needs a small loader namespace to load that DLL on the CLR: see [Loading precompiled native assemblies](./native-assemblies.md).

## 3. magic.edn

`nos` has built-in `build` and `test` tasks. They read an optional `magic.edn` at the project root: a map with `:build` and `:test` option sub-maps. A project states only what differs from the defaults, so most `magic.edn` files are a few lines, and a project that needs no tweaks can omit the file entirely.

```clojure
;; magic.edn
{:build {:aliases [:clr]}
 :test  {:aliases [:clr :test]}}
```

### Options

| Key | build | test | Meaning | Default |
|-----|:-:|:-:|---|---|
| `:aliases`    | yes | yes | deps aliases to activate | none (test: `[:test]`) |
| `:namespaces` | yes | yes | explicit namespaces, overrides derivation | derived from paths |
| `:exclude`    | yes | yes | namespaces to drop from the set | none |
| `:re`         |     | yes | regex string scoping the run (`re-matches`) | the project's own namespaces |
| `:flags`      | yes | yes | flag overrides (see below) | build: production, test: test-friendly |
| `:out`        | yes |     | compile output dir | `"build"` |
| `:clean?`     | yes |     | wipe `:out` first | `true` |

Defaults when a key is omitted:

- `:namespaces`: derived from the paths the aliases contribute (base `:paths` for build; plus the test alias's `:extra-paths` for test).
- `:re`: the test run is scoped to the project's own namespaces (its suites run, its dependencies' bundled suites do not). Write it as a string, e.g. `"my\\.lib\\..*"`; EDN has no regex literal.

### Flags

`:flags` overrides the compilation flags for the run. EDN cannot hold a var, so name each flag as a fully-qualified symbol:

```clojure
{:build {:flags {magic.flags/*elide-meta* true}}}
```

`build` starts from the production flag set (`*direct-linking*`, `*strongly-typed-invokes*`, `*elide-meta*`, `*unchecked-math*`, `*warn-on-reflection*`). `test` starts from the same set with `*direct-linking*` and `*strongly-typed-invokes*` off, since `with-redefs` cannot rebind a direct-linked or strongly-typed call. Your `:flags` merge over that base, so state only what you change.

### More examples

Exclude a namespace that must not compile on the CLR (a ClojureScript-only or JVM-only one):

```clojure
{:build {:exclude [my.lib.frontend.cljs]}
 :test  {:exclude [my.lib.frontend.cljs]}}
```

State the compile roots and output directory explicitly (a Unity plugins build, or a vendored namespace not reachable by `require`):

```clojure
{:build {:namespaces [my.lib.core my.lib.api]
         :out        "Assets/Plugins/Magic"}}
```

### The old way: dotnet.clj

Before the built-in tasks, each project wrote a `dotnet.clj` defining `build`/`run-tests` by hand over `nostrand.tasks`. It stays backward compatible: existing `dotnet.clj` files still work unchanged, and `nos dotnet/build` and `nos build` coexist.

```clojure
(ns dotnet
  (:require [nostrand.tasks :as tasks]))

(defn build     [] (tasks/compile-project :aliases [:clr] :clean? true))
(defn run-tests [] (tasks/run-clojure-tests :aliases [:clr :test]))
```

`magic.edn` exposes the full option set of both tasks, so a ported library needs no `dotnet.clj`; write one only for a project that also defines its own unrelated `nos` tasks.

## 4. Build and test

```bash
nos build    # compiles to ./build (or :out)
nos test     # runs the project's clojure.test suites, exits non-zero on failure
```

`nos test` runs under Mono and does not cover IL2CPP codegen; for Unity, an actual IL2CPP build is the only way to catch AOT-only regressions (see [`magic-unity-smoke`](../unity-examples/magic-unity-smoke)).

## 5. CI

A CI job needs `mono` and `nos` (see [Install](../README.md#install)). Flybot publishes a prebuilt image for this, [`ghcr.io/flybot-sg/ci-clj-clr`](https://github.com/flybot-sg/ci-clj-clr) (JDK, Clojure CLI, Babashka, Mono, and a pinned `nos`), so one container runs both JVM and CLR test jobs; pin the tag that ships the MAGIC version you build against. [flybot-sg/clr.test.check](https://github.com/flybot-sg/clr.test.check) (the `magic` branch) is a worked example, adding this CI on top of David Miller's upstream port. Any image with `mono` plus the `nos` installer works just as well if you would rather not depend on it.

### Caching

Point `GITLIBS` at a path inside the checkout so the runner can persist git deps across pipelines. `nos` honours the same variable JVM tools.deps uses (cloning under `$GITLIBS/nostrand/`, which cannot collide with the JVM entries), so one setting covers both the JVM and CLR jobs. GitLab example:

```yaml
variables:
  GITLIBS: $CI_PROJECT_DIR/.gitlibs

cache:
  paths:
    - .m2/
    - .gitlibs/
    - .cpcache/
```

Use an absolute path (`$CI_PROJECT_DIR`-based, not a bare relative one): a relative `GITLIBS` resolves against the working directory of whichever process reads it.

## Rich-comment-tests on the CLR

If a library's tests are written as [rich comment tests](https://github.com/robertluo/rich-comment-tests) (RCT), they cannot run on the CLR as-is: RCT extracts assertions at runtime using `rewrite-clj` and other JVM-only machinery. [flybot-sg/rct-clr](https://github.com/flybot-sg/rct-clr) bridges this: on the JVM it reads the rich comments and emits a plain `.cljc` test file of ordinary `deftest` forms that assert with [matcho](https://github.com/flybot-sg/matcho). MAGIC then runs that generated file with just `clojure.test` and `matcho.core`.

The split shows up in `magic.edn`: `:exclude` the RCT source namespace (the rich comments plus the JVM-only extraction tooling), leaving the generated `deftest` namespace to run like any other suite. `matcho` is the one extra CLR dependency (see [Declaring CLR dependencies](./clr-dependency-files.md)); this example activates it through a `:clr` alias:

```clojure
{:test {:aliases [:clr :test]
        :exclude [my.lib.rct-test]}}
```

The `clojure.test` assertion count on the CLR will not be one-for-one with a JVM run that executes the rich comments directly: RCT and the generated `deftest`s tally assertions differently (one `=>` expectation can expand to several matcho checks). The count differs; the same expectations are all verified.

## Reference

[flybot-sg/clr.test.check](https://github.com/flybot-sg/clr.test.check) is a fork of `clojure/test.check` ported with this workflow: reader conditionals throughout, cross-platform `deps.edn`, and CLR tests run under `nos`.
