# Nostrand
Standalone runtime environment and REPL for Clojure on the CLR. Bundled in the [flybot-sg/magic](https://github.com/flybot-sg/magic) monorepo as the host that boots the runtime, loads the MAGIC compiler, and runs your tasks.

This page is the reference for the parts specific to nostrand: how it runs a function, and how it resolves dependencies. For the task surface a project actually uses, `nos build`, `nos test` and `magic.edn`, see [the `nos` CLI](../docs/nos-cli.md).

## Install

One-line install (requires `mono`, no .NET SDK):

```
curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | sh
```

The script resolves the latest MAGIC version from `main`'s `version.edn`, downloads the matching release tarball, extracts it to `~/.local/nostrand`, and symlinks `~/.local/bin/nos`. Verify with `nos version`.

To pin a specific release instead of latest, set `MAGIC_VERSION` to a tag from the [releases page](https://github.com/flybot-sg/magic/releases):

```
curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | MAGIC_VERSION=<tag> sh
```

See the [top-level README](../README.md) for source builds and dev workflow.

## Usage

```
nos FUNCTION [ARG...]
```

Nostrand does one thing: it runs a function.

Functions are Clojure functions. Without a namespace, they resolve to the `nostrand.tasks` namespace which is built in.

```
$ nos version
Nostrand <version>
Clojure.Runtime <version>
Magic.Runtime <version>
Clojure 1.10.0-master-SNAPSHOT
Runtime 4.0.30319.42000 (Unix 25.5.0.0)

$ nos repl
user=>

$ nos where
/Users/me/.local/nostrand/net471

$ nos where Clojure.dll
/Users/me/.local/nostrand/net471/Clojure.dll
```

With a namespace they are searched for using Clojure's normal namespace resolution machinery. The current directory is on the load path by default.

```clojure
$ cat tasks.clj
(ns tasks)

(defn build []
  (binding [*compile-path* "build"]
    (compile 'important.core)
    (compile 'important.util)))

$ nos tasks/build
```

Command line arguments are read as EDN and passed to the function. The whole command line is read at once, so a form may span several shell words; when that fails to read, `nos` retries argument by argument and passes anything still unreadable through as a symbol, which is what lets a filesystem path be an argument.

```clojure
$ cat tasks.clj
(ns tasks)

(defn build [utils?]
  (binding [*compile-path* "build"]
    (compile 'important.core)
    (when utils?
      (compile 'important.util))))

$ nos tasks/build true
```

Your entry namespace can also set up your load path and load assemblies before its own `ns` form runs.

```clojure
$ cat tasks.clj
(assembly-load-from "assemblies/SomeLib.dll")
(ns tasks
  (:import [SomeLib SomeType]))

(defn build [utils?]
  (SomeType/DoThing)
  (binding [*compile-path* "build"]
    (compile 'important.core)
    (when utils?
      (compile 'important.util))))

$ nos tasks/build true
```

### The deps file
Nostrand reads `deps-clr.edn` from the current directory when it is there, and `deps.edn` otherwise, matching what `cljr` does. Whichever it reads is resolved at startup: every dependency is fetched and its source paths, plus the project's own `:paths`, are pushed onto the load path. Which of the two to write is [Declaring CLR dependencies](../docs/clr-dependency-files.md).

The recognized keys are a subset of [tools.deps](https://github.com/clojure/tools.deps):

* `:paths` A vector of source paths. Defaults to `["src"]`.
* `:deps` A map of `lib -> coordinate` (see [Dependencies](#dependencies)).
* `:aliases` A map of alias keyword -> `{:extra-paths :extra-deps :override-deps}`.
* `:nos/aliases` A vector of alias keywords to activate at boot, so an aliased project resolves its build basis once at startup instead of re-resolving in every task.
* `:nos/submodule-paths` Derive `:paths` from `.gitmodules` (see [Submodule paths](#submodule-paths)).

Inspect the resolved basis without compiling with `nos print-basis [:alias ...]`.

### Dependencies

Nostrand resolves git and local coordinates. Maven is not resolved natively, and the three libraries the runtime provides (`org.clojure/clojure`, `org.clojure/spec.alpha`, `org.clojure/core.specs.alpha`) are dropped by name. Resolution is transitive, and each dependency is read the same way the root project is, its own `deps-clr.edn` in preference to its `deps.edn`, with the coordinate closest to the root winning on conflict.

#### Git

A git coordinate is cloned into a content-addressed cache (`$GITLIBS/nostrand` when `GITLIBS` is set, else `~/.nostrand/gitlibs`), checked out at the pinned commit, and verified against the pin. Private repositories authenticate through your git and SSH config, so no tokens live in `deps.edn`.

```clojure
{:deps {flybot-sg/clr.test.check {:git/url "https://github.com/flybot-sg/clr.test.check"
                                  :git/sha "a5a2aca27873539fe366c1e0a09bb06e36026bf6"}}}
```

Coordinates are read the way `tools.deps` and `cljr` read them. URL inference from the lib name, the legacy `:sha`/`:tag` spellings, `:deps/root`, `:exclusions`, conflict resolution and the coord `:paths` extension are all in [Declaring CLR dependencies](../docs/clr-dependency-files.md).

#### Local

```clojure
{:deps {magic/mage {:local/root "../mage"}}}
```

Used in place with no clone. This is also how you live-edit a dependency.

#### Aliases

A project that keeps one `deps.edn` for both runtimes puts the forks the JVM side does not need behind a `:clr` alias:

```clojure
{:aliases   {:clr {:extra-deps    {clr-only/fork {:git/url "..." :git/sha "..."}}
                   :override-deps {jvm/lib       {:git/url "..." :git/sha "..."}}}}
 :nos/aliases [:clr]}
```

`:override-deps` swaps a lib's coordinate wherever it is encountered in the tree (a JVM to CLR fork swap) without itself seeding a root dependency.

#### Submodule paths

A project that pins its dependencies as git submodules can treat `.gitmodules` as the single source of truth for its `:paths`. Set `:nos/submodule-paths` to a path prefix (or `true` for every submodule) and boot derives the source paths from the checked-out submodules. Preview the derived list with `nos gitmodules-paths [prefix]`.

### Build and test tasks

`nos build` and `nos test` ship with the host, so most projects need no task file at all. Both derive the namespaces they work on by scanning the project's own source paths, and an optional `magic.edn` at the root states whatever differs from the defaults. `nos build` also copies the C# assemblies a dependency ships into the output, so a library's hand-written C# travels with the Clojure that imports it ([a library's C# assembly](../docs/native-assemblies.md)).

The pieces they are made of are public in `nostrand.tasks`, so a custom task composes them instead of restating them: `compile-project`, `run-clojure-tests`, `project-namespaces`, and the `production-flags` and `test-flags` maps. [The `nos` CLI](../docs/nos-cli.md) is the full reference for the tasks, the `magic.edn` keys, and the compiler flags each one binds.

## Name
[Nostrand Avenue](https://en.wikipedia.org/wiki/Nostrand_Avenue) is a major street and subway stop in Brooklyn near where Ramsey Nasser lived when he began the project.

## Legal

Copyright © 2016-2023 Ramsey Nasser and contributors.
Copyright © 2026 Flybot Pte. Ltd.

Licensed under the Apache License, Version 2.0.
