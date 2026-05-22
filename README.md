# MAGIC

Morgan And Grand Iron Clojure

A Clojure compiler targeting the Common Language Runtime (.NET). MAGIC compiles Clojure to MSIL bytecode, enabling Clojure to run in Unity (including IL2CPP/iOS builds) without the DLR.

## About this version

This monorepo bundles the six MAGIC repositories ([magic](https://github.com/nasser/magic), [mage](https://github.com/nasser/mage), [Clojure.Runtime](https://github.com/nasser/Clojure.Runtime), [Magic.Runtime](https://github.com/nasser/Magic.Runtime), [nostrand](https://github.com/nasser/nostrand), [Magic.Unity](https://github.com/nasser/Magic.Unity)) originally created by [Ramsey Nasser](https://nas.sr) and contributors (2014-2023).

[Flybot](https://flybot.sg) uses MAGIC to ship Clojure code on Unity. We consolidated the six repositories into this single repository using `git filter-repo` (preserving all commit history and authorship) to make maintenance easier and to streamline contributions, bug fixes, and new features, both for our team and for anyone else building on MAGIC.

This is not a replacement of Ramsey's MAGIC. It's the version Flybot maintains.

## Rationale

The JVM Clojure compiler targets the Java Virtual Machine. MAGIC targets .NET instead, taking full advantage of the CLR's features to produce better bytecode. This enables Clojure in environments where the JVM cannot run, notably Unity game engine (including iOS via IL2CPP).

MAGIC is a self-hosting compiler: it is written in Clojure and compiles itself through Nostrand, its task runner.

## Components

| Component | Description | Language |
|-----------|-------------|----------|
| [clojure-runtime](./clojure-runtime) | Persistent data structures, Keywords, Vars, reader | C# |
| [magic-runtime](./magic-runtime) | Fast dynamic call sites | C# |
| [mage](./mage) | Symbolic MSIL bytecode emitter | Clojure |
| [magic-compiler](./magic-compiler) | The compiler (s-expressions to MSIL) | Clojure |
| [nostrand](./nostrand) | Task runner, dependency manager, REPL | C# + Clojure |
| [magic-unity](./magic-unity) | Unity integration package (IL2CPP support) | C# |
| [magic-unity-smoke](./magic-unity-smoke) | Standalone Unity project that exercises MAGIC under IL2CPP. Regression suite for AOT-only bugs, runs by hand on the verified Unity version (`2022.3.62f3`). | Clojure + C# |

Each component has its own README with detailed documentation.

`clojure-runtime/` is forked from [ClojureCLR](https://github.com/clojure/clojure-clr) (a .NET port of Clojure maintained by David Miller). Its full commit history is preserved here, so David Miller and other ClojureCLR contributors appear in the GitHub contributors view.

## Architecture

```
Runtimes (C#, ship as DLLs)
  clojure-runtime   Clojure data: PersistentMap, Vars, Keywords (forked ClojureCLR)
  magic-runtime     dynamic call sites for Clojure-on-CLR

Compiler (Clojure, AOT-compiles .clj -> .clj.dll)
  mage              symbolic MSIL emitter
  magic-compiler    Clojure compiler proper (uses mage)

Host (C#)
  nostrand          CLI task runner; links the runtimes, hosts the compiler

Packaging
  magic-unity       UPM package: bundles runtime + compiler DLLs for Unity
```

`bootstrap` is a build phase, not a component: compile `magic-compiler` with the previous `nos`, copy the resulting `.clj.dll`s back into `nostrand/references/`, rebuild `nos` against them. See [Development](#development).

## Install

Two shippable artifacts; pick the one(s) your project needs.

| Artifact | What it is | Built from | Ships via | Consumer pulls via |
|---|---|---|---|---|
| **`nos` CLI** | Build-time task runner that compiles Clojure to MSIL. Used by Unity projects (at build time, before opening Unity) and by non-Unity Clojure libs that want CLR test runs | `nostrand/` + `clojure-runtime/` + `magic-runtime/` + `magic-compiler/` | GitHub Releases tarball, cut on every `v*` tag by [`.github/workflows/release.yml`](.github/workflows/release.yml) | `install/nos.sh` (one-line curl; requires `mono` runtime, no .NET SDK) |
| **`magic-unity` UPM package** | Play-time Clojure runtime + IL2CPP build pre-processor. Loaded by Unity inside a Unity project; not a standalone artifact | `magic-unity/` subdir of this repo (source + tracked playtime DLLs under `magic-unity/Runtime/Infrastructure/Export/`) | This repo, pinned by git tag | Unity's `Packages/manifest.json` UPM git URL with `?path=magic-unity#<tag>` |

### Use MAGIC in a Unity project

You need three things: the `nos` CLI (build-time), the `magic-unity` UPM package (Unity loads it at play time), and a small `dotnet.clj` in your Unity project root that tells `nos` what to compile.

1. **Install `nos`.** Requires `mono` runtime (macOS: `brew install mono`; Debian/Ubuntu: `sudo apt-get install -y mono-runtime`). No .NET SDK needed.

   ```bash
   # Latest, version resolved from main's version.edn:
   curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | sh

   # Or pin a specific release:
   curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | MAGIC_VERSION=v0.1.0 sh
   ```

   Defaults install to `$HOME/.local/nostrand/` with the launcher symlinked to `$HOME/.local/bin/nos`. Override with `INSTALL_DIR=` / `INSTALL_LINK=` env vars if needed.

2. **Add the Unity package** to your `Packages/manifest.json`:

   ```json
   {
     "dependencies": {
       "sg.flybot.magic.unity": "https://github.com/flybot-sg/magic.git?path=magic-unity#v0.1.0"
     }
   }
   ```

3. **Add `project.edn` and `dotnet.clj`** at your Unity project root. Copy the templates from [`magic-unity-smoke/`](./magic-unity-smoke/) and edit the namespace list to match your Clojure sources. Production-pinned compiler flags (`*direct-linking*`, `*strongly-typed-invokes*`) live in `dotnet.clj` so your build matches what ships.

4. **Compile your Clojure** before opening Unity:

   ```bash
   nos dotnet/build
   ```

   Drops your `.clj.dll` files into `Assets/Plugins/Magic/` where Unity will load them.

5. **Open Unity, hit Play.** For CI, define a `nos dotnet/run-tests` task in your `dotnet.clj` to run Mono-side tests independent of Unity (see [`magic-unity-smoke/dotnet.clj`](magic-unity-smoke/dotnet.clj)). IL2CPP-only regressions need an actual Unity build; [`magic-unity-smoke`](./magic-unity-smoke) is the reference pattern.

### Use `nos` for non-Unity Clojure-on-CLR

Same `install/nos.sh` line, no Unity needed. Drop a `project.edn` + `dotnet.clj` at your library root (see [`magic-unity-smoke/dotnet.clj`](magic-unity-smoke/dotnet.clj) for the shape — the same task definitions work outside Unity), then `nos dotnet/run-tests`. Tests execute under Mono via the `nos` you just installed.

## Prerequisites for working on MAGIC itself

Consumer install needs `bash`, `curl`, `tar`, and `mono` runtime. Contributing to MAGIC needs:

- [`git`](https://git-scm.com/)
- [`dotnet`](https://dotnet.microsoft.com/en-us/download) (v7+)
- [`mono`](https://www.mono-project.com/) (only needed at build time to host Nostrand; will go away once we drop net471)
- [`bb`](https://github.com/babashka/babashka) (optional, for the dev workflows in [Development](#development))

```bash
git clone https://github.com/flybot-sg/magic.git
cd magic
bb build         # or: dotnet build
```

The full build takes a few minutes on first run. Subsequent builds are incremental. Day-to-day workflows are in [Development](#development) below.

## Development

This repo mixes C# (runtimes + host) and Clojure (compiler + stdlib). Different edits need different rebuilds; doing the wrong one wastes minutes per iteration. The `bb` task runner encodes which rebuild matches which edit.

### What needs what

| You changed                          | Run                | Why                                                                                  |
|--------------------------------------|--------------------|--------------------------------------------------------------------------------------|
| `clojure-runtime/**/*.cs`            | `bb dev-runtime`   | `.clj.dll` references runtime DLLs by name; new bodies load without re-bootstrap     |
| `magic-runtime/Magic.Runtime/*.cs`   | `bb dev-runtime`   | Same                                                                                 |
| `*.mustache` (callsite templates)    | `bb dev-callsites` | Regenerates `.g.cs` first, then rebuilds runtime                                     |
| `nostrand/*.cs` (host code)          | `bb build-runtime` | Rebuilds nostrand (and runtimes transitively via `ProjectReference`)                 |
| `magic-compiler/src/**/*.clj`        | `bb dev-compiler`  | Compiler is bootstrapped; cold tests need re-bootstrap                               |
| `magic-compiler/src/stdlib/**/*.clj` | `bb dev-compiler`  | Same                                                                                 |
| Fresh checkout                       | `bb build`         | Full bootstrap                                                                       |

For interactive iteration on compiler `.clj` files, prefer `bb repl` plus `(require '... :reload)` over re-running `bb dev-compiler` each time.

### The bootstrap chicken-and-egg

MAGIC is self-hosting: the compiler is Clojure code that emits CLR bytecode, and it needs a previous version of itself to compile. The repo ships pre-built `.clj.dll` files in `nostrand/references/` for this reason. They ARE the compiler that compiles the new compiler.

Practical consequence: `bb dev-runtime` does not need a bootstrap. Already-compiled `.clj.dll` files reference `Magic.Runtime.dll` and `Clojure.dll` by name and pick up new bodies at load time. Only `bb dev-compiler` (changes to compiler or stdlib `.clj` source) triggers the slow re-bootstrap path.

### Refreshing the bootstrap binaries

Two folders of pre-built binaries are tracked in git:

- `nostrand/references/*.clj.dll`: the compiler Nostrand loads at startup
- `magic-unity/Runtime/Infrastructure/Export/*.dll`: the prebuilt runtime that Unity loads at play time

The `bb dev-*` tasks auto-revert any changes to these folders so day-to-day iteration commits stay clean. A maintainer refreshes them on purpose, usually after a batch of compiler or runtime fixes, by running either `bb build` (full path) or `bb build-magic` followed by `bb build-bootstrap` (faster: bootstrap + deploy). For stdlib-only edits (any `magic-compiler/src/stdlib/**/*.clj`), `bb refresh-stdlib` recompiles only the affected `.clj.dll` files and updates the SHA256 manifest (`magic-compiler/stdlib-manifest.edn`) that `bb check-drift` uses. Both paths refresh `nostrand/references/` and `magic-unity/Runtime/Infrastructure/Export/` together.

### Common workflows

```bash
bb tasks         # list all tasks with their docs
bb build         # full build (minutes, first time)
bb build-runtime # incremental C# runtimes + nostrand (seconds)
bb test          # run magic-compiler test suite
bb dev-runtime   # build-runtime + test  (after C# runtime edits)
bb dev-callsites # regen + build-runtime + test  (after .mustache edits)
bb dev-compiler  # build-magic + test  (after compiler .clj edits)
bb check-drift   # regen and fail if .g.cs files are stale (CI uses this)
bb repl          # nostrand CLI REPL in magic-compiler/
bb clean         # remove bin/ and bootstrap/
```

### Inspecting the compiler pipeline

Walk a form through the stages: form → macroexpand → AST → symbolic IL.

```bash
bb pipeline '(let [x 1] (+ x 1))'
```

Prints to stdout:

| Section          | What it shows                                                                                                                             |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `FORM`           | The input                                                                                                                                 |
| `MACROEXPAND`    | Shown only when expansion differs from input                                                                                              |
| `AST (skeleton)` | Analyzer output with bookkeeping keys (`:env`, source-position, `:raw-forms`) stripped                                                    |
| `TYPES`          | Every AST node carrying type info; useful for diagnosing intrinsic rewrites, static vs dynamic call-site selection, and numeric promotion |
| `SYMBOLIC IL`    | Flat instruction listing in emission order                                                                                                |

Also writes EDN dumps under `magic-compiler/target/`:

| File                   | What it is                                                                                                                                                                        |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `pipeline-ast.edn`     | Full AST, pprinted                                                                                                                                                                |
| `pipeline-il.edn`      | Flat instruction list, pprinted (usually what you want)                                                                                                                           |
| `pipeline-il-tree.edn` | Nested IL tree as mage emits it. The `nil` placeholders are structural alignment that mage filters at byte-emit time; the flat dump is the same instructions without the nesting. |

Forms that synthesize CLR types (`fn`, `defn`, `deftype`, `defrecord`) work too. No destination assembly is needed.

Options (`:key value` pairs after the form):

| Key         | Default                                 | Effect                                                                                              |
|-------------|-----------------------------------------|-----------------------------------------------------------------------------------------------------|
| `:out`      | `target`                                | Output directory for EDN dumps                                                                      |
| `:sections` | all eight                               | Subset of `#{:form :macroexpand :ast :types :il :ast-edn :il-edn :tree-edn}` controlling what renders |

```bash
bb pipeline '(let [x 1] x)' :out /tmp/inspect
bb pipeline '(let [x 1] x)' :sections '#{:ast :il}'           # stdout-only, no files
bb pipeline '(.Length "hi")' :sections '#{:il-edn}' :out /tmp # just dump the flat IL
```

### Before opening a PR

```bash
bb clean
bb build
bb check-drift   # catches forgotten regen after .mustache edits
bb test
```

### MSBuild targets (underlying)

The actual build is orchestrated by `Magic.csproj`. The `bb` tasks call into these; invoke them directly if you'd rather not install bb.

| Target                       | Description                                              |
|------------------------------|----------------------------------------------------------|
| `dotnet build`               | Full build (all components in dependency order)          |
| `dotnet build -t:Nostrand`   | Build task runner only                                   |
| `dotnet build -t:Magic`      | Magic compiler bootstrap (requires mono)                 |
| `dotnet build -t:Bootstrap`  | Copy compiler DLLs into Nostrand, rebuild                |
| `dotnet build -t:MagicUnity` | Unity package                                            |
| `dotnet build -t:Clean`      | Remove build artifacts                                   |

## Testing

```bash
bb test
# or directly:
cd magic-compiler && mono ../nostrand/bin/Release/net471/NostrandMain.exe test/all
```

The MAGIC compiler itself uses the `test/all` entrypoint in [magic-compiler/test.clj](magic-compiler/test.clj). Downstream projects use their own `nos dotnet/run-tests` (see the Getting Started section above for the pattern).

For IL2CPP-specific regressions (AOT-only bugs that the Mono editor cannot catch), [magic-unity-smoke](./magic-unity-smoke) drives MAGIC's compile output through Unity's IL2CPP pipeline and reports pass/fail in the built player. Run by hand on the verified Unity version after touching the compiler, the runtimes, or `magic-unity` itself.

## Git History

This monorepo consolidates 6 repositories using [git-filter-repo](https://github.com/newren/git-filter-repo). All commits, authors, and dates are preserved. Scope history to any component:

```bash
git log -- nostrand/
git log -- magic-compiler/
git blame magic-compiler/src/magic/core.clj
```

## License & attribution

MAGIC was created and developed by [Ramsey Nasser](https://nas.sr) and contributors from 2014 to 2023.

This monorepo version is maintained by [Flybot Pte. Ltd.](https://flybot.sg) from 2026.

- Most components: Apache License 2.0
- `clojure-runtime/`: [Eclipse Public License 1.0](./clojure-runtime/epl-v10.html), derived from [ClojureCLR](https://github.com/clojure/clojure-clr) by David Miller and contributors
