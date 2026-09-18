# MAGIC Unity Integration

[Unity](https://unity.com/) integration for the MAGIC compiler.

This UPM package lets a Unity game run Clojure, in the Editor and in a shipped player on every backend Unity supports, IL2CPP included (iOS, Android, consoles). It ships two Clojure runtimes (MAGIC and ClojureCLR), a small C# API for calling into Clojure, and the Editor build hooks that make MAGIC's IL survive AOT compilation. It also ships the MAGIC compiler itself, as Editor-only DLLs that never reach a player, and a host that can run nostrand's tasks against them inside the Editor's own process. Nothing in the package invokes that host: the everyday path is still to compile namespaces to plugin DLLs outside Unity with `nos build` and let Unity load them as plain .NET assemblies ([Editor API](#editor-api)).

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

The XML doc comments on the class carry the per-method contract.

`Magic.Unity.NostrandEditor` class, in the Editor-only assembly `Magic.Unity.Editor.Nostrand.Unity`, which exists only while the Editor runs MAGIC (`MAGIC_RUNTIME_IN_EDITOR` set) — the mirror of the reloader's constraint, so the two are mutually exclusive. Wrap code that uses it in `#if UNITY_EDITOR && MAGIC_RUNTIME_IN_EDITOR`.

- `NostrandEditor(IEnumerable<string> sourceRoots = null, Action<string> logger = null)` - the roots holding your Clojure, relative to the project root or absolute; the project root is always one. The logger defaults to `Debug.Log` and receives the task's output a line at a time. Hold the instance in a static field: booting is about a second and the host expects one instance per domain.
- `void Prewarm()` - boot the runtime and load nostrand without running anything, so the first real call does not. Takes no Editor lock, since it writes nothing.
- `bool Run(string[] command, bool resolveAssemblies = false)` - one nostrand command, the argv you would type after `nos`. Assembly reloading is locked and asset importing batched for the duration, unwound whatever happens, then the asset database is refreshed. False means the name resolved to neither a function nor a file.
- `object Eval(string source, bool resolveAssemblies = false)` - read and evaluate one form under the same project and bindings a task runs under, so a form containing `ns` or `in-ns` is legal.
- `NostrandHost Host` - the Unity-free host underneath, for a caller that wants no Editor policy.

Booting is per domain reload, so it is paid again after every script recompile. The [integration guide](https://github.com/flybot-sg/magic/blob/main/docs/unity-integration.md#compiling-inside-the-editor) has the wiring and the limitations worth knowing first.

### What `Poll` does

A save marks the path dirty on a background watcher thread. A path that has been quiet for 200 ms is drained by the next `Poll`, read into a snapshot, and evaluated under the file's real name, which is what lets `.cljc` reader conditionals pick `:cljr`. A read that loses a race with the save is retried once about 150 ms later (2 attempts total), and is skipped if a newer save is already queued. Eval failures are terminal, since re-reading will not fix source that does not compile. Eval and `onChanged` errors reach your logger and go no further, so `Poll` never throws into the loop you call it from.

### What a reload changes

The running system calls functions through their vars, so a redefinition takes effect at the next invocation. Not every form redefines what you would expect:

| Change | Reloads? |
|---|---|
| A `defn` body | yes |
| A `defmethod` body | yes, it re-registers into the live dispatch table |
| A `defmulti`'s dispatch fn, `:default` or `:hierarchy` | no. `defmulti` is a guarded def: once the var holds a `MultiFn`, re-evaluating it is a no-op. `ns-unmap` the var first, or restart. |
| A `deftype` or `defrecord` method body | no |
| Deleting a def | no. Re-evaluating re-`def`s the vars in the file, never removes ones you deleted, and does not reload dependent namespaces. |
| `(def x other-ns/x)`, re-exporting a value | no. The facade captured the value at boot, so callers keep calling the old fn after `other-ns` reloads. Re-export the var instead, `(def x #'other-ns/x)`: `Var` is itself `IFn` and derefs its root on every invoke. |

### Save order

Files that settle together reload in no particular order, and nothing tracks which namespace depends on which. What sequences two saves is the 200 ms settle window putting them in different drains. Say you edit `a` and `b` together, where `a` uses something from `b`, and `a` reaches the reloader first. Because `b` is already loaded, `a`'s `(:require [b])` is a no-op, so `a` re-evaluates against `b`'s old definitions, and reloading `b` afterwards never re-evaluates `a`. Two outcomes:

- `a` refers to something only the new `b` defines: `a` throws `Unable to resolve symbol`, the failure is logged, and `a` is dropped. Save `a` again.
- `a` uses a macro, `definline`, `deftype`/`defrecord`, or a compile-time-computed constant from `b`: `a` logs a successful reload and silently holds the old expansion. Plain `defn` calls are fine, since they go through vars and pick up the new `b` at call time.

## Examples

[magic-unity-smoke](https://github.com/flybot-sg/magic/tree/main/unity-examples/magic-unity-smoke) is a working IL2CPP regression project built on this package; [magic-unity-coexist](https://github.com/flybot-sg/magic/tree/main/unity-examples/magic-unity-coexist) is the headless regression for both Editor-runtime states.

## Legal

Copyright © 2020-2023 Ramsey Nasser and contributors.
Copyright © 2026 Flybot Pte. Ltd.

Licensed under the Apache License, Version 2.0.
