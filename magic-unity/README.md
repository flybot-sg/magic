MAGIC Unity Integration
=======================

[Unity](https://unity.com/) integration for the MAGIC compiler.

This UPM package ships the Clojure runtime DLLs that Unity loads at play time, plus the Editor-side preprocessors that rewrite MAGIC's IL during an IL2CPP build (iOS, Android, consoles). It does not compile Clojure; that step runs outside Unity via `nos dotnet/build` (see [Nostrand](../nostrand)).

## Install

Consume as a UPM package via git URL in `Packages/manifest.json`, pinned to a tag from the [releases page](https://github.com/flybot-sg/magic/releases):

```
"sg.flybot.magic.unity": "https://github.com/flybot-sg/magic.git?path=magic-unity#<tag>"
```

See [magic-unity-smoke](../magic-unity-smoke) for a working IL2CPP regression project that uses this integration.

## What the package ships

- `Runtime/Infrastructure/Export/` - prebuilt Clojure runtime: `Clojure.dll`, `Magic.Runtime.dll`, and the full stdlib as `*.clj.dll` (e.g. `clojure.core.clj.dll`, `clojure.pprint.clj.dll`, ...). Unity loads these as regular .NET assemblies at play time.
- `Runtime/Magic.Unity.cs` - the `Magic.Unity.Clojure` API (Boot/Require/GetVar) that C# scripts call to drive the Clojure runtime. Sets the platform-appropriate code-load order (`InitType` only on IL2CPP, `InitType` + `FileSystem` in the Editor).
- `Editor/MagicPreprocessor.cs` - an `IPreprocessBuildWithReport` hook that runs on every build and drives the IL2CPP-specific rewrites below.
- `Editor/IL2CPPWorkarounds.cs` - walks each candidate assembly with Mono.Cecil and applies `EliminateUnreachableInstructions` (removes dead IL the AOT linker chokes on) and `GenerateGenericWorkaroundMethods` (synthesises reachable instantiations of generic delegate helpers so IL2CPP's generic-sharing pass can find them).
- `Editor/LinkXmlGenerator.cs` - appends MAGIC-required `<preserve>` entries to `Assets/link.xml` so the managed-code stripper does not remove dynamically-referenced types.

## How it works

1. You compile your own Clojure namespaces to `.clj.dll` outside Unity via `nos dotnet/build`, writing them into `Assets/Plugins/Magic/` (see [magic-unity-smoke/dotnet.clj](../magic-unity-smoke/dotnet.clj) for the canonical task definition). The package does not include a compiler.
2. Unity opens the project. The prebuilt runtime + stdlib from `Runtime/Infrastructure/Export/` and your own `.clj.dll`s are both loaded as plain .NET assemblies. `Magic.Unity.Clojure.Boot()` initialises the runtime; `Require` / `GetVar` let C# scripts call into Clojure.
3. On every build, `MagicPreprocessor` runs first. When the build target uses IL2CPP, it rewrites the `.clj.dll` bodies in place so the IL2CPP transpiler can consume them (and writes `link.xml` entries); on a Mono build the preprocessor only sweeps any leftover IL2CPP-only workarounds from a previous build. The runtime DLLs are loaded the same way under either backend.
4. Coexistence with stock ClojureCLR: if a strong-named `Clojure.dll` is found under `Assets` (projects that keep ClojureCLR for Editor work and MAGIC for shipped builds), every MAGIC-compiled `.clj.dll` is imported with Editor loading off, because the stock runtime probe-loads `clojure.core.clj` at init and a MAGIC DLL answering that probe fails to load. With the exclusion active, the Editor logs `Assembly '...clj.dll' will not be loaded due to errors: Assembly is incompatible with the editor` for the package's `Export` DLLs on every domain reload. These lines are expected and benign in this setup: they are Unity reporting the intended Editor exclusion, not a real failure. Player builds are unaffected.

## API

`Magic.Unity.Clojure` static class:

- `void Require(string ns)` - load a Clojure namespace. Must be called before looking up vars in that namespace.
- `clojure.lang.Var GetVar(string ns, string name)` - look up a Clojure var. Dereference with `deref` or invoke with `invoke`.
- `T GetVar<T>(string ns, string name)` - typed variant.
- `void Boot()` - initialize the Clojure runtime. Called automatically by the other methods; rarely needed directly.

## Legal

Copyright © 2020 Ramsey Nasser and contributors. Licensed under the Apache License, Version 2.0.
