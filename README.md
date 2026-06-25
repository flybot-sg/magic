# MAGIC

[![Build](https://img.shields.io/github/actions/workflow/status/flybot-sg/magic/ci.yml?label=build&branch=main)](https://github.com/flybot-sg/magic/actions/workflows/ci.yml)
[![Tests](https://img.shields.io/github/actions/workflow/status/flybot-sg/magic/ci.yml?label=tests&branch=main)](https://github.com/flybot-sg/magic/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/flybot-sg/magic)](https://github.com/flybot-sg/magic/releases/latest)
[![Clojure](https://img.shields.io/badge/clojure-1.10-blue.svg?logo=clojure&logoColor=white)](https://clojure.org/)
[![.NET](https://img.shields.io/badge/.NET-Framework%204.7.1%20%2F%20netstandard%202.0-512BD4.svg?logo=dotnet&logoColor=white)](https://dotnet.microsoft.com/)
[![Unity](https://img.shields.io/badge/unity-2022.3.62f3-000000.svg?logo=unity&logoColor=white)](https://unity.com/)

Morgan And Grand Iron Clojure

A Clojure compiler targeting the Common Language Runtime (.NET). MAGIC compiles Clojure to MSIL bytecode, enabling Clojure to run in Unity (including IL2CPP/iOS builds) without the DLR.

## Status

Flybot uses MAGIC in production to ship Clojure game logic on Unity, including iOS via IL2CPP. The compiler is feature-complete against the Clojure 1.10 language and standard library, and still maturing (not yet as battle-tested as JVM Clojure). Concretely:

- **Clojure 1.10 stdlib parity.** The marked-1.10 surface runs on the CLR (`ex-message`, `tap>`, `read+string`, `Throwable->map`, the `prepl` family, ...). MAGIC targets 1.10; the later releases (1.11 and the current 1.12) are not ported ([details](./docs/writing-cross-platform-clojure.md)).
- **Runs in Unity, including IL2CPP and iOS.** MAGIC emits fully static MSIL, so it survives AOT compilation where the DLR-based ClojureCLR cannot ([why and how](./docs/why-magic.md)). Ships as a UPM package in two variants (see [Install](#install)).
- **Same source on JVM and CLR.** A clean `.cljc` library within the 1.10 stdlib compiles unchanged on both MAGIC and ClojureCLR, with no MAGIC-specific patches.
- **IL2CPP-tested.** A standalone Unity smoke project exercises AOT-only regressions on the verified Unity version; green on Mono and Standalone IL2CPP ([smoke suite](./unity-examples/magic-unity-smoke)).

## About this version

This monorepo bundles the six MAGIC repositories ([magic](https://github.com/nasser/magic), [mage](https://github.com/nasser/mage), [Clojure.Runtime](https://github.com/nasser/Clojure.Runtime), [Magic.Runtime](https://github.com/nasser/Magic.Runtime), [nostrand](https://github.com/nasser/nostrand), [Magic.Unity](https://github.com/nasser/Magic.Unity)) originally created by [Ramsey Nasser](https://nas.sr) and contributors (2014-2023).

[Flybot](https://flybot.sg) uses MAGIC to ship Clojure code on Unity. We consolidated the six repositories into this single repository using `git filter-repo` (preserving all commit history and authorship) to make maintenance easier and to streamline contributions, bug fixes, and new features, both for our team and for anyone else building on MAGIC.

This is not a replacement of Ramsey's MAGIC. It's the version Flybot maintains.

## Rationale

The JVM Clojure compiler targets the Java Virtual Machine. MAGIC targets .NET instead, taking full advantage of the CLR's features to produce better bytecode. This enables Clojure in environments where the JVM cannot run, notably Unity game engine (including iOS via IL2CPP).

MAGIC is a self-hosting compiler: it is written in Clojure and compiles itself through Nostrand, its task runner.

## Documentation

- [Why MAGIC](./docs/why-magic.md): why a static CLR compiler is needed (iOS forbids the runtime code generation ClojureCLR's DLR relies on; IL2CPP only AOT-compiles IL that already exists).
- [Writing cross-platform Clojure](./docs/writing-cross-platform-clojure.md): `.cljc` source patterns for code that runs on both the JVM and the CLR.
- [Porting a Clojure library to MAGIC](./docs/porting-libraries-to-magic.md): `deps.edn`, `dotnet.clj`, and CI for a CLR build.
- [Unity integration](./docs/unity-integration.md): compile `.clj.dll` and load them in a Unity project.

Per-component reference lives in each component's own README, linked from [Components](#components) below. To contribute, see [CONTRIBUTING.md](./CONTRIBUTING.md).

## Components

| Component | Description | Language |
|-----------|-------------|----------|
| [clojure-runtime](./clojure-runtime) | Persistent data structures, Keywords, Vars, reader | C# |
| [magic-runtime](./magic-runtime) | Fast dynamic call sites | C# |
| [mage](./mage) | Symbolic MSIL bytecode emitter | Clojure |
| [magic-compiler](./magic-compiler) | The compiler (s-expressions to MSIL) | Clojure |
| [nostrand](./nostrand) | Task runner, dependency manager, REPL | C# + Clojure |
| [magic-unity](./magic-unity) | Unity integration package (IL2CPP support), default variant | C# |
| [magic-unity-dual](./magic-unity-dual) | Coexistence variant of magic-unity with the runtime excluded from the Editor (for projects that keep stock ClojureCLR). Generated by `bb gen-unity-dual`; see [Unity integration](./docs/unity-integration.md). | C# |
| [magic-unity-smoke](./unity-examples/magic-unity-smoke) | Standalone Unity project that exercises MAGIC under IL2CPP. Regression suite for AOT-only bugs, runs by hand on the verified Unity version (`2022.3.62f3`). | Clojure + C# |

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

**`nos` CLI**: a build-time task runner that compiles Clojure to MSIL. Used by Unity projects (before opening Unity) and by non-Unity Clojure libs that want CLR test runs.

- Built from `nostrand/` + `clojure-runtime/` + `magic-runtime/` + `magic-compiler/`.
- Ships as a GitHub Releases tarball, cut on every `v*` tag by [`release.yml`](.github/workflows/release.yml).
- Consumers install it with `install/nos.sh` (one-line curl; needs `mono`, no .NET SDK).

**`magic-unity` UPM package**: the play-time Clojure runtime plus the IL2CPP build pre-processor, loaded by Unity inside a project. It ships in **two variants**, and picking one is the first decision:

- **`sg.flybot.magic.unity`** (default): MAGIC runs everywhere, including the Editor's Play mode. Pin `?path=magic-unity#<tag>`. Use this unless your Editor already runs stock ClojureCLR.
- **`sg.flybot.magic.unity.dual`**: the runtime is excluded from the Editor (`!UNITY_EDITOR`), so the Editor keeps stock ClojureCLR (REPL / hot-reload) and MAGIC ships only in player builds. Pin `?path=magic-unity-dual#<tag>`.

Player builds are identical either way. Both are pinned by git tag and added as a UPM git URL in `Packages/manifest.json`; full comparison in [Unity integration](./docs/unity-integration.md).

### Use MAGIC in a Unity project

You need three things: the `nos` CLI (build-time), the `magic-unity` UPM package (Unity loads it at play time), and a small `dotnet.clj` in your Unity project root that tells `nos` what to compile.

1. **Install `nos`.** Requires `mono` runtime (macOS: `brew install mono`; Debian/Ubuntu: `sudo apt-get install -y mono-runtime`). No .NET SDK needed.

   ```bash
   # Latest, version resolved from main's version.edn:
   curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | sh

   # Or pin a specific release (tag from https://github.com/flybot-sg/magic/releases):
   curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | MAGIC_VERSION=<tag> sh
   ```

   Defaults install to `$HOME/.local/nostrand/` with the launcher symlinked to `$HOME/.local/bin/nos`. Override with `INSTALL_DIR=` / `INSTALL_LINK=` env vars if needed.

2. **Add the package, compile, open Unity.** The [Unity integration guide](./docs/unity-integration.md) covers the `Packages/manifest.json` pin (and which of the two variants to pick: default, or `.dual` for projects that keep stock ClojureCLR in the Editor), the `deps.edn` / `dotnet.clj` templates, `nos dotnet/build`, and IL2CPP.

### Use `nos` for non-Unity Clojure-on-CLR

Same `install/nos.sh` line, no Unity needed. Drop a `deps.edn` + `dotnet.clj` at your library root, then `nos dotnet/run-tests`. Tests execute under Mono via the `nos` you just installed. [Porting a Clojure library to MAGIC](./docs/porting-libraries-to-magic.md) walks through the whole workflow: reader conditionals, `deps.edn`, and the three `dotnet.clj` shapes (derive from aliases, exclude, or hardcode the namespace list). [Writing cross-platform Clojure](./docs/writing-cross-platform-clojure.md) covers the `.cljc` source patterns themselves: type hints, host interop, records and protocols, and the Clojure 1.10 stdlib surface.

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

### Evaluating against a live runtime (prepl)

`bb pipeline` shows the *compiled IL* for a form but never runs it. To see what
a form actually returns at runtime, evaluate it against a warm MAGIC runtime
over a socket prepl (the MAGIC analogue of a Clojure nREPL). Complementary to
`bb pipeline`: pipeline answers "how does this compile", prepl answers "what
does this do".

Start the server in one shell (it blocks, serving connections):

```bash
bb prepl-server            # listens on 127.0.0.1:5555 (override: bb prepl-server 5560)
```

Send forms from another shell. Each prints the structured reply map:

```bash
bb prepl-eval '(+ 1 2)'
#=> {:tag :ret, :val "3", :ns "user", :ms 1.3, :form "(+ 1 2)"}

bb prepl-eval '(def answer 42)'   # global defs persist across calls
bb prepl-eval '(* answer 2)'      #=> {:tag :ret, :val "84", ...}

bb prepl-eval '(/ 1 0)'           # errors come back as data, not a text dump
#=> {:tag :ret, :exception true, :val "{... :cause \"Divide by zero\" :phase :execution}", ...}
```

Reply tags: `:ret` (eval result + `:ms` timing), `:out`/`:err` (captured
stdout/stderr during eval), `:tap` (values from `tap>`); an `:exception true`
`:ret` carries the `Throwable->map` data. The server is MAGIC Clojure
(`clojure.core.server/io-prepl`); the `bb prepl-eval` client is plain babashka,
so each call is fast. This runs only under Mono/nostrand: prepl evaluates at
runtime, so it has no IL2CPP/AOT path.

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

For IL2CPP-specific regressions (AOT-only bugs that the Mono editor cannot catch), [magic-unity-smoke](./unity-examples/magic-unity-smoke) drives MAGIC's compile output through Unity's IL2CPP pipeline and reports pass/fail in the built player. Run by hand on the verified Unity version after touching the compiler, the runtimes, or `magic-unity` itself.

## Contributing

Issue, PR, and commit conventions (including component labels and the paired bootstrap-refresh rule) are in [CONTRIBUTING.md](./CONTRIBUTING.md), along with the local checks to run before opening a PR.

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
