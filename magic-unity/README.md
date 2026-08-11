# MAGIC Unity Integration

[Unity](https://unity.com/) integration for the MAGIC compiler.

This file is the developer reference. For the full usage guide about using with Unity, see the [Unity integration guide](https://github.com/flybot-sg/magic/blob/main/docs/unity-integration.md).

This UPM package lets a Unity game run Clojure, in the Editor and in a shipped player on every backend Unity supports, IL2CPP included (iOS, Android, consoles). It ships two Clojure runtimes (MAGIC and ClojureCLR), a small C# API for calling into Clojure, and the Editor build hooks that make MAGIC's IL survive AOT compilation.

It does not compile Clojure — compile your namespaces to `.clj.dll` outside Unity with `nos build` (writes to `magic.edn`'s `:out`, `Assets/Plugins/Magic` by convention), and Unity loads them as plain .NET assemblies.

## Install

Add to `Packages/manifest.json`, pinned to a tag from the [releases page](https://github.com/flybot-sg/magic/releases):

```
"sg.flybot.magic.unity": "https://github.com/flybot-sg/magic.git?path=magic-unity#<tag>"
```

## Runtime API

`Magic.Unity.Clojure` static class, available on all platforms:

- `void Require(string ns)` - load a Clojure namespace. Must be called before looking up vars in that namespace.
- `clojure.lang.Var GetVar(string ns, string name)` - look up a Clojure var. Dereference with `deref` or invoke with `invoke`.
- `T GetVar<T>(string ns, string name)` - typed variant.
- `void Boot()` - initialize the Clojure runtime. Called automatically by the other methods; rarely needed directly.

Nothing calls these for you — a `MonoBehaviour` of yours has to. See [step 5 of the guide](https://github.com/flybot-sg/magic/blob/main/docs/unity-integration.md#steps) for the pattern.

## Editor API

`Magic.Unity.EditorRuntime` static class, Editor-only:

- `bool IsMagicEnabled()` - whether the Editor's Clojure runtime is MAGIC.
- `void UseMagic()` / `void UseClojureCLR()` - set it, on the active build target. It triggers a recompilation, so the switch takes effect on the next Unity invocation.

## Examples

[magic-unity-smoke](https://github.com/flybot-sg/magic/tree/main/unity-examples/magic-unity-smoke) is a working IL2CPP regression project built on this package; [magic-unity-coexist](https://github.com/flybot-sg/magic/tree/main/unity-examples/magic-unity-coexist) is the headless regression for both Editor-runtime states.

## Legal

Copyright © 2020-2023 Ramsey Nasser and contributors.
Copyright © 2026 Flybot Pte. Ltd.

Licensed under the Apache License, Version 2.0.
