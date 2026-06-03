# Nostrand
Standalone runtime environment and REPL for ClojureCLR on Mono. Bundled in the [flybot-sg/magic](https://github.com/flybot-sg/magic) monorepo as the task runner that hosts the MAGIC compiler.

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
Nostrand 0.0.1.6940 (master/a1e4260* Mon Nov 28 03:51:20 EST 2016)
Mono 4.8.0 (mono-4.8.0-branch/f5fbc32 Mon Nov 14 14:18:03 EST 2016)
Clojure 1.7.0-master-SNAPSHOT

$ nos repl
Nostrand 0.0.1.6940 (master/a1e4260* Mon Nov 28 03:51:20 EST 2016)
Mono 4.8.0 (mono-4.8.0-branch/f5fbc32 Mon Nov 14 14:18:03 EST 2016)
Clojure 1.7.0-master-SNAPSHOT
REPL 0.0.0.0:11217
user>
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

Command line arguments are parsed as EDN passed to the function.

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

Your entry namespace can also set up your classpath, load assemblies, and eventually manage dependencies.  

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

### `deps.edn`
If a `deps.edn` is present in the current directory it is resolved at startup: every dependency is fetched and its source paths, plus the project's own `:paths`, are pushed onto the load path. The recognized keys are a subset of [tools.deps](https://github.com/clojure/tools.deps):

* `:paths` A vector of source paths. Defaults to `["src"]`.
* `:deps` A map of `lib -> coordinate` (see [Dependencies](#dependencies)).
* `:aliases` A map of alias keyword -> `{:extra-paths :extra-deps :override-deps}`.
* `:nos/aliases` A vector of alias keywords to activate at boot, so an aliased project resolves its build basis once at startup instead of re-resolving in every task.
* `:nos/submodule-paths` Derive `:paths` from `.gitmodules` (see [Submodule paths](#submodule-paths)).

Inspect the resolved basis without compiling with `nos print-basis [:alias ...]`.

### Dependencies

Nostrand resolves git and local coordinates. Maven is not resolved natively, and libraries that ship inside `Clojure.dll` (`org.clojure/clojure`, `org.clojure/spec.alpha`, `org.clojure/core.specs.alpha`) are skipped. Resolution is transitive: each dependency's own `deps.edn` `:deps` are followed, with the coordinate closest to the root winning on conflict.

#### Git

A git coordinate is cloned over its URL's transport into a content-addressed cache under `~/.nostrand/gitlibs`, checked out at the pinned commit, and verified against the pin. Private repositories authenticate through your git and SSH config, so no tokens live in `deps.edn`.

Pin a `:git/sha`: a cache whose checkout does not match the sha is an error, which is what makes a build reproducible. A `:git/tag` may accompany the sha as a readable label (its commit is checked against the sha, warning on a mismatch). A tag on its own is honoured but only warns if the tag later moves, so prefer a sha.

```clojure
{:deps {flybot-sg/clr.test.check {:git/url "https://github.com/flybot-sg/clr.test.check"
                                  :git/sha "a5a2aca27873539fe366c1e0a09bb06e36026bf6"}
        some/private-lib         {:git/url "git@dev.example.sg:group/private-lib.git"
                                  :git/tag "v1.2.0"
                                  :git/sha "1c3decbb9b6f9b2a0e0e6a4f0b1c2d3e4f5a6b7c"}}}
```

A coordinate may carry an explicit `:paths` vector, used in place of the dependency's own `deps.edn` `:paths`, for a repo that has no `deps.edn` or a non-`src` layout (e.g. a contrib lib under `src/main/clojure`).

#### Local

```clojure
{:deps {magic/mage {:local/root "../mage"}}}
```

Used in place with no clone. This is also how you live-edit a dependency.

#### Aliases

A CLR build typically activates a `:clr` alias carrying the forks the JVM side does not need:

```clojure
{:aliases   {:clr {:extra-deps    {clr-only/fork {:git/url "..." :git/sha "..."}}
                   :override-deps {jvm/lib       {:git/url "..." :git/sha "..."}}}}
 :nos/aliases [:clr]}
```

`:override-deps` swaps a lib's coordinate wherever it is encountered in the tree (a JVM to CLR fork swap) without itself seeding a root dependency.

#### Submodule paths

A project that vendors its dependencies as git submodules can treat `.gitmodules` as the single source of truth for its `:paths`. Set `:nos/submodule-paths` to a path prefix (or `true` for every submodule) and boot derives the source paths from the checked-out submodules. Preview the derived list with `nos gitmodules-paths [prefix]`.

NuGet packages can be packed and pushed with the `tasks/nuget-push` task.

## Name
[Nostrand Avenue](https://en.wikipedia.org/wiki/Nostrand_Avenue) is a major street and subway stop in Brooklyn near where I was living when I began the project.

## Legal
Copyright © 2016-2017 Ramsey Nasser

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

```
http://www.apache.org/licenses/LICENSE-2.0
```

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
