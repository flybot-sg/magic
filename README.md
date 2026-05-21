# MAGIC

Morgan And Grand Iron Clojure

A Clojure compiler targeting the Common Language Runtime (.NET). MAGIC compiles Clojure to MSIL bytecode, enabling Clojure to run in Unity (including IL2CPP/iOS builds) without the DLR.

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

Each component has its own README with detailed documentation.

## Architecture

```
clojure-runtime    C# data structures, Vars, Keywords
       |
magic-runtime      C# dynamic call sites
       |
nostrand           Task runner, links against both runtimes
       |
magic-compiler     Clojure compiler, built via mono + Nostrand
       |                (depends on mage at build time)
bootstrap          Copies compiler DLLs back into Nostrand, rebuilds it
       |
magic-unity        Unity package, copies runtime + compiler DLLs
```

## Prerequisites

- [`git`](https://git-scm.com/)
- [`dotnet`](https://dotnet.microsoft.com/en-us/download) (v7+)
- [`mono`](https://www.mono-project.com/) (only needed at build time to host Nostrand; will go away once we drop net471)
- [`bb`](https://github.com/babashka/babashka) (optional, for the dev workflows in [Development](#development))

## Getting Started

```bash
git clone https://github.com/magic-clojure/magic.git
cd magic
bb build         # or: dotnet build
```

The full build takes a few minutes on first run. Subsequent builds are incremental.

After build, set up the `nos` command on your PATH (used by Clojure projects that consume MAGIC):

```bash
ln -s $(pwd)/nostrand/Scripts/nos-framework /usr/local/bin/nos
```

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

The `bb dev-*` tasks auto-revert any changes to these folders so day-to-day iteration commits stay clean. A maintainer refreshes them on purpose, usually after a batch of compiler or runtime fixes, by running either `bb build` (full path) or `bb build-magic` followed by `bb build-bootstrap` (faster: bootstrap + deploy). Both paths refresh `nostrand/references/` and `magic-unity/Runtime/Infrastructure/Export/` together. A fresh clone runs the test suite without needing to build first.

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

Projects downstream of MAGIC (e.g. application code compiled with `nos`) define their own test tasks in a `dotnet.clj` at their root and run them via `nos dotnet/run-tests`. The MAGIC compiler itself uses the `test/all` entrypoint in [magic-compiler/test.clj](magic-compiler/test.clj).

## Unity

Add to your Unity project via `manifest.json`:

```json
{
  "dependencies": {
    "com.nasser.magic": "https://github.com/magic-clojure/magic.git?path=magic-unity"
  }
}
```

See the [magic-compiler README](magic-compiler/README.md) for compilation instructions.

## Git History

This monorepo consolidates 6 repositories using [git-filter-repo](https://github.com/newren/git-filter-repo). All commits, authors, and dates are preserved. Scope history to any component:

```bash
git log -- nostrand/
git log -- magic-compiler/
git blame magic-compiler/src/magic/core.clj
```

## License

Copyright © 2015-2026 Ramsey Nasser and contributors

Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).
