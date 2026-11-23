# MAGIC Unity Integration

[Unity](https://unity.com/) integration for the MAGIC compiler.

This UPM package lets a Unity game run Clojure, in the Editor and in a shipped player on every backend Unity supports, IL2CPP included (iOS, Android, consoles). It ships two Clojure runtimes (MAGIC and ClojureCLR), a small C# API for calling into Clojure, and the Editor build hooks that make MAGIC's IL survive AOT compilation. It does not compile Clojure: namespaces are compiled to plugin DLLs outside Unity with `nos build`, and Unity loads them as plain .NET assemblies.

This file is the API reference. The workflow — project setup, `nos build`, choosing the Editor runtime, IL2CPP — is in the [Unity integration guide](https://github.com/flybot-sg/magic/blob/main/docs/unity-integration.md).

## Install

Add to `Packages/manifest.json`, pinned to a tag from the [releases page](https://github.com/flybot-sg/magic/releases):

```
"sg.flybot.magic.unity": "https://github.com/flybot-sg/magic.git?path=magic-unity#<tag>"
```

## Runtime API

`Magic.Unity.Clojure` static class, available on all platforms. Nothing calls these for you; a `MonoBehaviour` of yours has to. See [step 5 of the guide](https://github.com/flybot-sg/magic/blob/main/docs/unity-integration.md#steps).

- `void Require(string ns)` - load a Clojure namespace. Must be called before looking up vars in that namespace.
- `clojure.lang.Var Var(string ns, string name)` - look up a Clojure var. Dereference with `deref` or invoke with `invoke`.
- `T Deref<T>(string ns, string name)` - deref the var once and cast its value, e.g. to a `Magic.Function` type to invoke a type-hinted fn without boxing.
- `void Boot()` - initialize the Clojure runtime. Called automatically by the other methods; needed only before using `clojure.lang` directly.
- `IReadOnlyList<string> SourceRoots` - the absolute source roots the Editor loads namespaces from (see [Source roots](#source-roots)). Empty in a player.

## Source roots

The ClojureCLR Editor compiles your Clojure from source, so it has to find your namespaces' files. List their roots in `reload.edn`, with paths relative to that file. It is read from the project root, or else from the one top-level directory that has it:

```clojure
{:source-roots ["src" "libs/some-lib/src"]}
```

At boot, `Magic.Unity.Clojure` appends the roots that exist to `CLOJURE_LOAD_PATH`, after any entries already there, so `user.clj` in a root loads too. Each root's layout must match the namespaces (`foo.bar` → `<root>/foo/bar.cljc`) and hold no compiled `.clj.dll`, which ClojureCLR loads in place of a source that is not newer. Without the file nothing is added; a malformed file, or one in several top-level directories, logs an error and boots without roots.

A MAGIC Editor ignores `reload.edn` and takes the source paths of the project's `deps.edn` (or `deps-clr.edn`), resolved as `nos` resolves them, `:nos/aliases` and `:nos/submodule-paths` included. At boot, `Magic.Unity.Clojure` puts the ones that exist ahead of `*load-paths*`; a deps file that fails to resolve logs an error and boots without roots. The Editor loads none of the `.clj.dll` that `nos build` wrote, which are for players only. It loads `nos build :dev`'s output instead, from `magic.edn`'s `:dev :out`, taking each namespace's DLL unless its source is newer, and compiles the rest from these paths with the dev build's flags. Without that folder every namespace compiles from source.

## Editor API

`Magic.Unity.EditorRuntime` static class, Editor-only:

- `bool IsMagicEnabled()` - whether the Editor's Clojure runtime is MAGIC.
- `void UseMagic()` / `void UseClojureCLR()` - set it, on the active build target. It triggers a recompilation, so the switch takes effect on the next Unity invocation.

`Magic.Unity.ClojureReloader` class, in the Editor-only assembly `Magic.Unity.Editor.Reload`, which exists only while the Editor runs ClojureCLR (`MAGIC_RUNTIME_IN_EDITOR` unset). Wrap code that uses it in `#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR`.

- `ClojureReloader(IEnumerable<string> roots, Action<string> logger, Action<string> onChanged = null)` - watch the given directories recursively for `.clj`/`.cljc`/`.cljr` saves; typically `Clojure.SourceRoots`.
- `void Poll()` - call it on each tick of one thread, normally the main thread. It evaluates each source file that has settled.
- `void Dispose()` - stop watching.

Files saved together reload in no particular order; there is no dependency ordering between namespaces. A file that loads before its dependency fails and is not retried, so needs to be saved again.

The XML doc comments on the class carry the per-method contract.

## Examples

[magic-unity-smoke](https://github.com/flybot-sg/magic/tree/main/unity-examples/magic-unity-smoke) is a working IL2CPP regression project built on this package; [magic-unity-coexist](https://github.com/flybot-sg/magic/tree/main/unity-examples/magic-unity-coexist) is the headless regression for both Editor-runtime states.

## Legal

Copyright © 2020-2023 Ramsey Nasser and contributors.
Copyright © 2026 Flybot Pte. Ltd.

Licensed under the Apache License, Version 2.0.
