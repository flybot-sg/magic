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
- [`mono`](https://www.mono-project.com/)

## Getting Started

```bash
git clone https://github.com/magic-clojure/magic.git
cd magic
dotnet build
```

The full build takes a few minutes on first run. Subsequent builds are incremental.

## Tasks

The build is orchestrated by `Magic.csproj`:

| Task | Description |
|------|-------------|
| `dotnet build` | Full build (all components in dependency order) |
| `dotnet build -t:Nostrand` | Build task runner only |
| `dotnet build -t:Magic` | Compiler bootstrap (requires mono) |
| `dotnet build -t:Bootstrap` | Copy compiler DLLs into Nostrand, rebuild |
| `dotnet build -t:MagicUnity` | Unity package |
| `dotnet build -t:Clean` | Remove build artifacts |

After build, set up the `nos` command:

```bash
ln -s $(pwd)/nostrand/Scripts/nos-framework /usr/local/bin/nos
```

## Testing

Tests run through Nostrand. Projects define test tasks in a `dotnet.clj` file at their root:

```bash
nos dotnet/run-tests
```

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
