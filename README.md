# MAGIC

[![Build](https://img.shields.io/github/actions/workflow/status/flybot-sg/magic/ci.yml?label=build&branch=main)](https://github.com/flybot-sg/magic/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/flybot-sg/magic)](https://github.com/flybot-sg/magic/releases/latest)
[![Clojure](https://img.shields.io/badge/clojure-1.10-blue.svg?logo=clojure&logoColor=white)](https://clojure.org/)
[![.NET](https://img.shields.io/badge/.NET-Framework%204.7.1%20%2F%20netstandard%202.0-512BD4.svg?logo=dotnet&logoColor=white)](https://dotnet.microsoft.com/)
[![Unity](https://img.shields.io/badge/unity-2022.3.62f3-000000.svg?logo=unity&logoColor=white)](https://unity.com/)

Morgan And Grand Iron Clojure

A Clojure compiler targeting the Common Language Runtime (.NET). MAGIC compiles Clojure to MSIL bytecode, enabling Clojure to run in Unity (including IL2CPP/iOS builds) without the DLR.

## Status

Flybot uses MAGIC in production to ship Clojure game logic on Unity, including iOS via IL2CPP. The compiler is feature-complete against the Clojure 1.10 language and standard library, and still maturing (not yet as battle-tested as JVM Clojure). Concretely:

- **Clojure 1.10 stdlib parity.** The marked-1.10 surface runs on the CLR (`ex-message`, `tap>`, `read+string`, `Throwable->map`, the `prepl` family, ...). MAGIC targets 1.10; 1.11 and 1.12 are not ported ([details](./docs/writing-cross-platform-clojure.md)).
- **Runs in Unity, including IL2CPP and iOS.** MAGIC emits fully static MSIL, so it survives AOT compilation where the DLR-based ClojureCLR cannot ([why and how](./docs/why-magic.md)). Ships as a UPM package (see [Install](#install)).
- **Same source on JVM and CLR.** A clean `.cljc` library within the 1.10 stdlib compiles unchanged on both MAGIC and ClojureCLR. `nos` reads `deps-clr.edn` and `.cljr` sources the way `cljr` does, so a library ported the ClojureCLR way needs no MAGIC-specific setup ([details](./docs/clr-dependency-files.md)).
- **IL2CPP-tested.** A standalone Unity smoke project exercises AOT-only regressions on the verified Unity version; green on Mono and Standalone IL2CPP ([smoke suite](./unity-examples/magic-unity-smoke)).

## About this version

This monorepo bundles the six MAGIC repositories ([magic](https://github.com/nasser/magic), [mage](https://github.com/nasser/mage), [Clojure.Runtime](https://github.com/nasser/Clojure.Runtime), [Magic.Runtime](https://github.com/nasser/Magic.Runtime), [nostrand](https://github.com/nasser/nostrand), [Magic.Unity](https://github.com/nasser/Magic.Unity)) originally created by [Ramsey Nasser](https://nas.sr) and contributors (2014-2023).

[Flybot](https://flybot.sg) uses MAGIC to ship Clojure code on Unity. We consolidated the six repositories into this single repository using `git filter-repo` (preserving all commit history and authorship) to make maintenance easier and to streamline contributions, bug fixes, and new features, both for our team and for anyone else building on MAGIC.

This is not a replacement of Ramsey's MAGIC. It's the version Flybot maintains.

## Documentation

- [Why MAGIC](./docs/why-magic.md): why a static CLR compiler is needed (iOS forbids the runtime code generation ClojureCLR's DLR relies on; IL2CPP only AOT-compiles IL that already exists).
- [MAGIC architecture](./docs/architecture.md): the components, what each one does, and how they fit together.

Using MAGIC on your own library:

- [Porting a Clojure library to MAGIC](./docs/porting-libraries-to-magic.md): the four steps, then testing and CI.
- [Writing cross-platform Clojure](./docs/writing-cross-platform-clojure.md): `.cljc` source patterns for code that runs on both the JVM and the CLR.
- [Declaring CLR dependencies](./docs/clr-dependency-files.md): `deps-clr.edn` vs a `:clr` alias, when the CLR needs different deps than the JVM.
- [The `nos` CLI](./docs/nos-cli.md): how a task is found, `nos build` and `nos test`, and the `magic.edn` surface.
- [Loading precompiled native assemblies](./docs/native-assemblies.md): loading a committed C# DLL on the CLR, where `:import` alone does not.
- [Unity integration](./docs/unity-integration.md): compile `.clj.dll` and load them in a Unity project.

Working on MAGIC itself:

- [Development](./docs/development.md): every `bb` task in depth, which rebuild your edit needs, and the tools for inspecting a form.
- [The bootstrap](./docs/bootstrap.md): what is committed and why, which task owns which DLL, and how many passes a change needs.
- [Deterministic compilation and the drift check](./docs/deterministic-compilation.md): why the committed DLLs are byte-diffed against a rebuild, and the contributor workflows that follow.

Per-component reference lives in each component's own README, linked from [Components](#components) below. To contribute, see [CONTRIBUTING.md](./CONTRIBUTING.md).

## Components

| Component | Description | Language |
|-----------|-------------|----------|
| [clojure-runtime](./clojure-runtime) | Clojure's data model in C#: persistent collections, keywords, vars, the reader. Every compiled DLL runs on it. | C# |
| [magic-runtime](./magic-runtime) | Resolves the interop calls whose types are only known at run time, caching each call site. Emits no IL at run time, which is what IL2CPP requires. | C# |
| [mage](./mage) | MSIL as Clojure data, so bytecode can be built and rewritten as plain values before it is emitted. | Clojure |
| [magic-compiler](./magic-compiler) | The compiler: Clojure forms to MSIL. Also holds the standard library sources it compiles. | Clojure |
| [nostrand](./nostrand) | The `nos` CLI: hosts the compiler, resolves dependencies, runs the build, test and REPL tasks. | C# + Clojure |
| [magic-unity](./magic-unity) | The UPM package Unity loads at play time: the prebuilt runtime plus the IL2CPP pre-build step. Ships both Clojure runtimes; a define symbol selects the Editor's. See [Unity integration](./docs/unity-integration.md). | C# |
| [magic-unity-smoke](./unity-examples/magic-unity-smoke) | Unity project that drives compiled output through IL2CPP, catching the AOT-only bugs Mono cannot reach. Run by hand on Unity `2022.3.62f3`. | Clojure + C# |
| [magic-unity-coexist](./unity-examples/magic-unity-coexist) | Unity project that regression-tests which Clojure runtime the Editor loads, in both supported states. Driven by `bb coexist-noise`. | C# |

Each component has its own README with detailed documentation, and [MAGIC architecture](./docs/architecture.md) shows how they fit together.

`clojure-runtime/` is forked from [ClojureCLR](https://github.com/clojure/clojure-clr) (a .NET port of Clojure maintained by David Miller). Its full commit history is preserved here, so David Miller and other ClojureCLR contributors appear in the GitHub contributors view.

## Install

Two shippable artifacts; pick the one(s) your project needs.

**`nos` CLI**: a build-time task runner that compiles Clojure to MSIL. Used by Unity projects (before opening Unity) and by non-Unity Clojure libs that want CLR test runs.

- Built from `nostrand/` + `clojure-runtime/` + `magic-runtime/` + `magic-compiler/` + `mage/`.
- Ships as a GitHub Releases tarball, cut on every `v*` tag by [`release.yml`](.github/workflows/release.yml).
- Consumers install it with `install/nos.sh` (one-line curl; needs `mono`, no .NET SDK).

**`magic-unity` UPM package**: the play-time Clojure runtime plus the IL2CPP build pre-processor, loaded by Unity inside a project. Pin `?path=magic-unity#<tag>` as a UPM git URL in `Packages/manifest.json`. It ships both Clojure runtimes: the `MAGIC_RUNTIME_IN_EDITOR` define symbol selects the Editor's (default: ClojureCLR, for REPL / hot-reload), and player builds always run MAGIC. Details: [Unity integration](./docs/unity-integration.md#choosing-the-editor-runtime).

### Use MAGIC in a Unity project

You need three things: the `nos` CLI (build-time), the `magic-unity` UPM package (Unity loads it at play time), and a small `magic.edn` in your Unity project root that tells `nos` what to compile (or a hand-written `dotnet.clj` for custom build/test tasks).

1. **Install `nos`.** Requires `mono` runtime (macOS: `brew install mono`; Debian/Ubuntu: `sudo apt-get install -y mono-runtime`). No .NET SDK needed.

   ```bash
   # Latest, version resolved from main's version.edn:
   curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | sh

   # Or pin a specific release (tag from https://github.com/flybot-sg/magic/releases):
   curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | MAGIC_VERSION=<tag> sh
   ```

   Defaults install to `$HOME/.local/nostrand/` with the launcher symlinked to `$HOME/.local/bin/nos`. Override with `INSTALL_DIR=` / `INSTALL_LINK=` env vars if needed.

2. **Add the package, compile, open Unity.** The [Unity integration guide](./docs/unity-integration.md) covers the `Packages/manifest.json` pin, choosing the Editor's Clojure runtime, the `deps.edn` / `magic.edn` setup, `nos build`, and IL2CPP.

### Use `nos` for non-Unity Clojure-on-CLR

Same `install/nos.sh` line, no Unity needed. Declare the CLR coordinates in a `deps-clr.edn` (or a `deps.edn` with a `:clr` alias), state what differs from the defaults in an optional `magic.edn`, then `nos build` and `nos test`, which run under the `mono` that hosts `nos`. [Porting a Clojure library to MAGIC](./docs/porting-libraries-to-magic.md) is the ordered walkthrough.

## Development

Consuming MAGIC needs `bash`, `curl`, `tar` and `mono`. Working on it needs:

- [`git`](https://git-scm.com/)
- [`dotnet`](https://dotnet.microsoft.com/en-us/download) SDK 7 and 8 (what CI installs; the callsite generator targets `net8.0`)
- [`mono`](https://www.mono-project.com/) (hosts Nostrand at build time)
- [`bb`](https://github.com/babashka/babashka) (the build tasks stamp the committed DLL timestamps before compiling, so a correct build goes through them)

```bash
git clone https://github.com/flybot-sg/magic.git
cd magic
bb build
```

That takes a few minutes, every time: `bb build` cleans first, so the bootstrap is always redone from scratch. Day to day you want the task that matches your edit instead.

This repo mixes C# (runtimes + host) and Clojure (compiler + stdlib), and the two rebuild on very different timescales. `bb.edn` encodes which rebuild matches which edit:

| You changed | Run | What it costs |
|---|---|---|
| any C# in `clojure-runtime/`, `magic-runtime/` or `nostrand/` | `bb build-runtime` | a C# build |
| a callsite `.mustache` template | `bb dev-callsites` | regen, then a C# build |
| `magic-compiler/src/stdlib/`, outside the `clojure.core` family | `bb refresh-stdlib` | one compile pass over the stdlib |
| `magic-compiler/src/magic/`, `mage/src/`, or the `clojure.core` family | `bb dev-compiler` | two bootstrap passes |

[The development guide](./docs/development.md) covers every task in depth: what each one rebuilds, why they all go through `bb`, how the drift check works, and the `bb pipeline` and prepl tools for inspecting a form.

### The committed binaries

MAGIC is self-hosting, so compiling the compiler needs a working compiler. Two folders of pre-built binaries are tracked in git to break that circle:

- `nostrand/references/*.clj.dll`: 73 DLLs, the compiler and stdlib Nostrand loads at startup. They are what compiles the next compiler.
- `magic-unity/Runtime/magic/`: the two runtime DLLs plus 37 stdlib `.clj.dll`, what Unity loads at play time

That is why a C# edit is cheap and a compiler edit is not. Compiled `.clj.dll` name `Clojure.dll` and `Magic.Runtime.dll` in their assembly references and pick up new bodies at load time, so a C# change never needs a bootstrap. Only the compiler's own source and the `clojure.core` family take the slow path.

Compilation is deterministic: rebuilding unchanged sources reproduces the committed bytes exactly, so `git status` after a rebuild shows only the DLLs a change really affected, and those get committed in a paired refresh commit alongside the source fix. `bb check-drift` byte-diffs the whole set against a rebuild. [The bootstrap](./docs/bootstrap.md) has the ownership rules, and [Deterministic compilation and the drift check](./docs/deterministic-compilation.md) covers what makes the byte diff work.

### Testing

`bb test` runs the compiler suite, `magic-compiler/test/magic/test/*.clj`, entered through the `test/all` var in [magic-compiler/test.clj](magic-compiler/test.clj). Pass namespace names to run a subset. Downstream projects run `nos test` instead ([the `nos` CLI](./docs/nos-cli.md)).

The suite runs with the production flags off, bar a few direct-linking cases in `magic.test.fn`, so it tells you little about how a compiler change behaves under `*direct-linking*` and `*strongly-typed-invokes*` ([what they change](./docs/nos-cli.md#compiler-flags)); build a real consumer project as well. AOT-only bugs need a real Unity build: [magic-unity-smoke](./unity-examples/magic-unity-smoke) drives MAGIC's output through IL2CPP and reports pass/fail in the built player. Run it by hand on the verified Unity version after touching the compiler, the runtimes, or `magic-unity`.

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
