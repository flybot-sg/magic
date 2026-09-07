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
- `clojure.lang.Var GetVar(string ns, string name)` - look up a Clojure var. Dereference with `deref` or invoke with `invoke`.
- `T GetVar<T>(string ns, string name)` - typed variant.
- `void Boot()` - initialize the Clojure runtime. Called automatically by the other methods; rarely needed directly.

## Editor API

`Magic.Unity.EditorRuntime` static class, Editor-only:

- `bool IsMagicEnabled()` - whether the Editor's Clojure runtime is MAGIC.
- `void UseMagic()` / `void UseClojureCLR()` - set it, on the active build target. It triggers a recompilation, so the switch takes effect on the next Unity invocation.

`Magic.Unity.ClojureReloader` class, in the Editor-only assembly `Magic.Unity.Editor.Reload`, which exists only while the Editor runs ClojureCLR (`MAGIC_RUNTIME_IN_EDITOR` unset). Wrap code that uses it in `#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR`.

- `ClojureReloader(IEnumerable<string> roots, Action<string> logger, Action<string> onChanged = null)` - watch the given directories recursively for `.clj`/`.cljc`/`.cljr` saves; typically the directories you put on `CLOJURE_LOAD_PATH`.
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
