MAGIC Unity Integration
=======================

[Unity](https://unity.com/) integration for the MAGIC compiler.

This UPM package ships the Clojure runtime DLLs that Unity loads at play time, plus the Editor-side preprocessors that rewrite MAGIC's IL during an IL2CPP build (iOS, Android, consoles). It does not compile Clojure; that step runs outside Unity via `nos dotnet/build` (see [Nostrand](../nostrand)).

## Install

Consume as a UPM package via git URL in `Packages/manifest.json`, pinned to a tag from the [releases page](https://github.com/flybot-sg/magic/releases):

```
"sg.flybot.magic.unity": "https://github.com/flybot-sg/magic.git?path=magic-unity#<tag>"
```

That is the whole install, for every project. **Player builds always run MAGIC.** In the **Editor**, this package loads *stock ClojureCLR* by default; if you want MAGIC in the Editor too (Play mode, edit-mode tooling), turn it on from `MAGIC > Editor Runtime > Use MAGIC in the Editor` — see [Choosing the Editor runtime](#choosing-the-editor-runtime).

See [magic-unity-smoke](../unity-examples/magic-unity-smoke) for a working IL2CPP regression project that uses this integration.

## What the package ships

- `Runtime/Infrastructure/Export/` - prebuilt MAGIC Clojure runtime: `Clojure.dll`, `Magic.Runtime.dll`, and the full stdlib as `*.clj.dll` (e.g. `clojure.core.clj.dll`, `clojure.pprint.clj.dll`, ...). Unity loads these as regular .NET assemblies at play time.
- `Runtime/Infrastructure/Stock/` - stock ClojureCLR 1.11.0 (net462) and the DLR it needs, Editor-only. This is what the Editor loads unless you opt into MAGIC. Third-party, under EPL-1.0 / Apache-2.0; see [Third Party Notices.md](./Third%20Party%20Notices.md).
- `Runtime/Magic.Unity.cs` - the `Magic.Unity.Clojure` API (Boot/Require/GetVar) that C# scripts call to drive the Clojure runtime. Sets the platform-appropriate code-load order (`InitType` only on IL2CPP, `InitType` + `FileSystem` in the Editor).
- `Editor/MagicPreprocessor.cs` - an `IPreprocessBuildWithReport` hook that runs on every build and drives the IL2CPP-specific rewrites below.
- `Editor/IL2CPPWorkarounds.cs` - walks each candidate assembly with Mono.Cecil and applies `EliminateUnreachableInstructions` (removes dead IL the AOT linker chokes on) and `GenerateGenericWorkaroundMethods` (synthesises reachable instantiations of generic delegate helpers so IL2CPP's generic-sharing pass can find them).
- `Editor/LinkXmlGenerator.cs` - appends MAGIC-required `<preserve>` entries to `Assets/link.xml` so the managed-code stripper does not remove dynamically-referenced types.

## How it works

1. You compile your own Clojure namespaces to `.clj.dll` outside Unity via `nos dotnet/build`, writing them into `Assets/Plugins/Magic/` (see [magic-unity-smoke/dotnet.clj](../unity-examples/magic-unity-smoke/dotnet.clj) for the canonical task definition). The package does not include a compiler.
2. Unity opens the project. The prebuilt runtime + stdlib from `Runtime/Infrastructure/Export/` and your own `.clj.dll`s are both loaded as plain .NET assemblies. `Magic.Unity.Clojure.Boot()` initialises the runtime; `Require` / `GetVar` let C# scripts call into Clojure.
3. On every build, `MagicPreprocessor` runs first. When the build target uses IL2CPP, it rewrites the `.clj.dll` bodies in place so the IL2CPP transpiler can consume them (and writes `link.xml` entries); on a Mono build the preprocessor only sweeps any leftover IL2CPP-only workarounds from a previous build. The runtime DLLs are loaded the same way under either backend.
4. Which runtime the Editor loads is decided by a define constraint on every shipped DLL, evaluated before anything is loaded - see below. Player builds are not affected by that choice.

## Choosing the Editor runtime

One package, both runtimes. A single scripting define symbol, `MAGIC_RUNTIME_IN_EDITOR`, decides which one the **Editor** loads:

| | symbol unset (default) | symbol set | player build |
|---|---|---|---|
| Stock ClojureCLR (`Stock/`) | loaded | excluded | excluded |
| MAGIC runtime (`Export/`) | excluded | loaded | **loaded, always** |
| MAGIC in Editor Play mode | not available | works | - |
| Editor REPL / hot-reload against stock | works | not available | - |

Flip it from the menu: **`MAGIC > Editor Runtime > Use MAGIC in the Editor`**. It writes the symbol to every build-target group, because the Editor compiles with the *active* group's symbols and a project that switches platform would otherwise silently switch runtime. From CI, call `Magic.Unity.EditorRuntime.UseMagic()` / `UseStock()`.

Leave it unset so that you can run a REPL and hot-reload with ClojureCLR.

Two things to know:

- **Leave it unset and the Editor needs API Compatibility Level `.NET Framework`** (`Project Settings > Player`). Stock ClojureCLR is net462 and always initialises through the DLR, which references `System.Configuration`, `System.Runtime.Remoting` and `System.Xaml` - none of them in the .NET Standard profile. The package warns if it finds this wrong; set the level yourself in `Project Settings > Player`.
- **In the default state, `Clojure.Boot` / `Require` / `GetVar` drive stock, not MAGIC.** The call sites are identical but the meaning is not: stock loads namespaces from `.clj` source through the DLR, MAGIC loads AOT-compiled `InitType`. So Editor behaviour when using ClojureCLR can diverge from MAGIC on the player.

Do not vendor your own `Clojure.dll` under `Assets`. Unity dedups managed plugins by file name and an unconstrained copy wins over the package's. Your own compiled `*.clj.dll` are fine anywhere under `Assets` - the package stamps the matching constraint onto them on import.

## API

`Magic.Unity.Clojure` static class:

- `void Require(string ns)` - load a Clojure namespace. Must be called before looking up vars in that namespace.
- `clojure.lang.Var GetVar(string ns, string name)` - look up a Clojure var. Dereference with `deref` or invoke with `invoke`.
- `T GetVar<T>(string ns, string name)` - typed variant.
- `void Boot()` - initialize the Clojure runtime. Called automatically by the other methods; rarely needed directly.

## Legal

Copyright © 2020 Ramsey Nasser and contributors. Licensed under the Apache License, Version 2.0.
