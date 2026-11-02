# Master plan: MAGIC hot reload in the Unity Editor

A plan; nothing in it is implemented.
Merged from two cross-reviewed findings docs and two earlier design docs.
Line references hold on `develop` as of 2026-10-30, plus `magic-unity/README.md`'s "What a reload changes" and "Save order", which land with this plan; `in-proc:` marks code that exists only on that branch.
Claims not yet settled are tagged **[inferred]** (reasoned from code or general Unity behaviour) or **[unverified]**;
everything untagged was read in code or measured, and measurements say where.

## 1. Goal and transition

Player builds run MAGIC, so Editor development should too.
ClojureCLR's only remaining value in the Editor is hot reload.
It is a separate port to maintain, and it diverges from MAGIC.

Both Editor runtimes keep shipping until MAGIC hot reload is proven bug-free, then ClojureCLR is dropped, in two gates:

1. **`integrated` switches to MAGIC by default**, with ClojureCLR still installed as a fallback, once:
   - every step in §7 is done and its checks are green;
   - every reload behaviour `magic-unity/README.md` documents on ClojureCLR has been checked on MAGIC: the six rows of "What a reload changes" and the two outcomes in "Save order".

   The package cannot make MAGIC the Editor default: Unity's constraint grammar forces ClojureCLR to be the default (rationale beside `plugin-sets` in `bb/magic/unity.clj`).
   So the switch is `integrated` committing `MAGIC_RUNTIME_IN_EDITOR` in its project settings.
2. **ClojureCLR is removed** (`clojure-clr/`, `bb sync-clojure-clr`, the constraint scheme around it) after a period of daily use under gate 1 with no fallback to ClojureCLR and no open hot-reload bugs.
   The length of that period is still to set.

After the drop the Editor matches players in runtime, not in compile flags (§6).

## 2. Decision: two jobs, two homes

| Job | Runs in | Output | Flags |
|---|---|---|---|
| Shipped DLLs | a `nos` subprocess, trigger to decide (§4.1) | `Assets/Plugins/Magic`, player-only | `production-flags` |
| Editor runtime and hot reload | the Editor process | in memory, loaded from a `nos`-built dev set in `Library/` | `test-flags` |

The Editor never runs nostrand's build tasks and never writes a DLL.

### Rejected: build tasks inside the Editor (`in-proc`)

- **The speed gain does not survive the build.**
  Measured inside `nos version` before #183: 3.75 to 3.86 s wall clock, of which the MAGIC compiler's boot was about 0.85 s
  (`RT.Initialize` 316-373 ms, `magic/api` init 464-483 ms) and loading nostrand's own Clojure from source the remaining ~2.8 s.
  #183 made nostrand's namespaces DLLs, so in-process now saves about 0.85 s per build.
  Writing DLLs into `Assets/` triggers an import and a domain reload, which discards the warm compiler anyway. **[inferred]**
- **Reusing the Editor's domain is where every known hazard comes from**, and a fresh process has none of them:
  - four warm-domain workarounds: `set-load-path` rather than `*load-paths*`, `RT.PostBootstrapInit()` once per domain, a fresh `*loaded-libs*` per call, a binding frame that always pops;
  - emission relative to the cwd;
  - the mid-process `RT._id` reset;
  - `Environment/Exit` in a task file, fatal in the Editor, as in `magic-unity-smoke/dotnet.clj:25` and nostrand's own `test` task on a red suite;
  - keeping `nostrand.cli` and `nostrand.main` out of the Editor's DLL set.
- **Observed in Unity `2022.3.62f3` on 2026-10-29, in `magic-unity-smoke` under `MAGIC_RUNTIME_IN_EDITOR`**, through `in-proc`'s `NostrandEditor.Run`:
  - **A rebuild wiped the output.**
    All 14 `smoke.*` `.clj.dll`s were loaded from `Assets/Plugins/Magic` before any build.
    After an edit to `smoke/check.clj`, `compile-project :namespaces [smoke.runner] :clean? true` printed `Compiling smoke.runner` and `Done.`, with no per-namespace compile output, and left zero `.clj.dll`s on disk.
    The control, the same call after the resulting domain reload had loaded no `smoke.*` assembly, wrote all 14, and `smoke.check.clj.dll` carried the edit.
    Mechanism: `compile` only binds `*compile-files*` and calls `load-one` (`core.clj:6357-6358`).
    Under the Editor's `{InitType, FileSystem}` order, `-load`'s InitType branch runs first (`core.clj:6040-6042`),
    and `TryLoadInitType` finds the namespace's init type in the preloaded copy, since it scans every non-dynamic assembly in the domain (`clojure-runtime/Clojure/CljCompiler/Compiler.cs:1790`).
    So `compile-file` is never called: `:clean? true` deletes the output and writes nothing back, `:clean? false` leaves every DLL stale.
  - **The C# assembly copy saw no dependency.**
    `copy-csharp-assemblies!` copies only the assemblies loaded from under a source path (`nos/loaded-assembly-files`, `nostrand-lib/nostrand/core.clj:120-133`).
    Under `nos`, `smoke-csharp.load-dll` loads `smoke_csharp.dll` from under a source path; in the Editor, Unity had already loaded it from `Assets/Plugins/CSharp/`, the build's own output.
    So the build printed `No dependency ships smoke_csharp.dll any more; delete it from Assets/Plugins/CSharp`, which, if followed, deletes a DLL the project needs.
    Seen with the previous `.clj.dll`s preloaded and without, so it is independent of the first.

  Both are the same pattern: the Editor has loaded the previous build's output, and the build trusts what is loaded.
- **Out of process, output is byte-identical to CI**, and the Editor stays responsive during a build.

## 3. Measured inside Unity so far

The only measurements from inside the Editor, all on Unity `2022.3.62f3`, macOS.
From a throwaway Editor menu in `integrated`, driving `in-proc`'s host:

- The compiler ran in the Editor's own process: `Unity Child Domain`, Mono `6.13.0`, `mscorlib` from `Unity.app/Contents/MonoBleedingEdge/lib/mono/unityjit-macos`.
- `compile-file` output was structurally identical to a `nos` build of the same file (`hjd/event/state.cljc`, `hjd/prop/utils.cljc`).
  The three typedefs that differed were named after the driver's namespace, fixed by #194.
- `establish-project` found the project's `deps.edn` and expanded `:nos/submodule-paths` into 33 roots.
- Writing DLLs to `Library/MagicProbe/` triggered no import and no domain reload (`domain reloads=0`), even with an `AssetDatabase.Refresh()` behind it.

From `bb coexist-noise`, which runs `unity-examples/magic-unity-coexist` headless, with `in-proc`'s additions to its `CoexistenceProbe`:

- 5/5: `compiler-in-domain` 43 in the MAGIC state and 0 in the ClojureCLR state, `compiler-player-refs` 0 in both.

## 4. Design

### 4.1 Shipped DLLs: `nos`, out of process

- `production-flags` into `Assets/Plugins/Magic`.
  Nothing in the Editor loads that set, so it is built only for a player build.
- **Proposed: a build entry point, not a pre-build hook.** A menu action runs `nos`, calls `AssetDatabase.Refresh()`, then `BuildPipeline.BuildPlayer`; CI does the same steps.
  Running `nos` inside an `IPreprocessBuildWithReport` callback would make DLLs appear mid-build,
  in the same callback chain as `MagicPreprocessor`'s IL2CPP rewrite (`BuildPreprocessor`, `callbackOrder` 0, `magic-unity/Editor/MagicPreprocessor.cs:13-15`)
  and `PlayerCljAssemblies`, which reads `CompilationPipeline.GetAssemblies(AssembliesType.PlayerWithoutTestAssemblies)` (`magic-unity/Editor/PlayerCljAssemblies.cs:34`).
  Whether a plugin imported during a build is picked up is **[unverified]**.
- **Launch `nos` with its working directory set to the project root.** It establishes the project from its cwd (`Runtime.EstablishProject(Directory.GetCurrentDirectory())`, `nostrand/Program.cs:24`).
  `Process.Start` would inherit Unity's cwd, the project root, but set `WorkingDirectory` explicitly.
- **Unity's bundled Mono runs `nos`.** `Unity.app/Contents/MonoBleedingEdge/bin/mono` ran `NostrandMain.exe version` in 2.39 s, system `mono` in 2.25 s.
  Only `version` was tried; a full build under it is **[unverified]**. If it holds, consumers need no system Mono.
- **Consumer `.clj.dll`s become player-only** (`!UNITY_EDITOR`) in `CljPluginConstraints`.
  Today they carry `!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR` (`magic-unity/Editor/CljPluginConstraints.cs:19`), so Unity preloads them in the MAGIC state, as observed in `magic-unity-smoke`.
  With nothing of the consumer's preloaded, InitType precedence cannot occur in the Editor.
  A separate `!UNITY_EDITOR` entry ANDs with the existing one under Unity's grammar. **[inferred]**
  Once ClojureCLR is dropped, `MAGIC_RUNTIME_IN_EDITOR` loses its meaning, which simplifies the constraint scheme.

### 4.2 Dev set and roots file: `nos`, into `Library/`

One `nos` run writes both, so they always agree.
That needs a new `nos` entry point, a task or a `magic.edn` key such as `:dev`:
`nos build` reads only `magic.edn`'s `:build` (`(:build (read-magic-edn))`, `tasks.clj:300-302`) and `nos test` only `:test` (`:315-317`),
so no existing invocation builds a second set, and nothing writes a roots file today.

- **The dev set**: `compile-project` with `test-flags` (`nostrand-lib/nostrand/tasks.clj:82`, `production-flags` with direct linking and strongly-typed invokes off),
  with `:out "Library/MagicDev"`, `:clean? true` and `:csharp-out "Assets/Plugins/CSharp"`.
  `:clean? true` because `compile-project` defaults it to false (`tasks.clj:204`, while `build` defaults it to true at `:301`),
  and `compile-file` writes only when no DLL exists at the target (`(if-not (File/Exists final-path) ...)`, else `[compile-file] file already exists`, `magic-compiler/src/magic/api.clj:250-256`),
  so a rerun into a populated dev directory would keep every old DLL and report success.
  Cleaning empties only `:out` (`tasks.clj:209-210`), never `:csharp-out`, and costs nothing in `Library/`, which has no `.meta` files or GUIDs to lose.
  It makes every run a full recompile of the consumer, unmeasured on `integrated`.
  Without `:csharp-out` the dependencies' C# DLLs land in `Library/` beside the dev set, since `compile-project` copies them to `(or csharp-out out)` (`tasks.clj:215`), and Unity never loads them there.
  `Assets/Plugins/CSharp` is unconstrained (in `integrated`, e.g. `fast_edn.dll.meta` has `defineConstraints: []`), so the Editor loads it.
  `pending-copies` skips a file whose content is unchanged, so the dev run causes an import, and a domain reload, only when a dependency's C# really changed.
- **The roots file**: the source roots `establish-project` resolved (`nostrand-lib/nostrand/core.clj:151`), not the dev set's directory, which the boot step adds itself (§4.3).
  Its format is fixed at §7 step 2, which writes the first one by hand.
  Invariant: it never lists `Assets/Plugins/Magic`, or the mtime rule could load the direct-linked production DLLs.

Why `test-flags`:
with `*direct-linking*`, the call site `magic-function-static-invoke-compiler` (`magic-compiler/src/magic/core.clj:1189-1209`)
emits a direct `call` to the callee's `invokeStatic`, for callees that are neither variadic nor dynamic, bypassing the Var.
Measured on a `probe.dl` where `outer` calls `inner`, then redefining `inner`:

```
compiled with *direct-linking* false (the default)   (outer) => outer sees NEW   (inner) => NEW
compiled with *direct-linking* true  (production)    (outer) => outer sees OLD   (inner) => NEW
```

`test-flags` exists so `with-redefs` can rebind calls; reloading is the same mechanism.
Reuse it rather than minting a third flag map.
Only the consumer's namespaces need it: neither `build.clj` nor `refresh.clj` binds `*direct-linking*` or `*strongly-typed-invokes*`, so the committed stdlib and compiler DLLs are already compiled under the `magic.flags` defaults, direct linking off.

Why `Library/`: it is never imported, so the set needs no `.meta` or define constraint and cannot reach a player build.
Writing there was measured not to trigger a domain reload (§3).

Why the roots come from `nos`:
a consumer cannot list them by hand, since `establish-project` resolves deps, possibly cloning through `procure` (`nostrand-lib/nostrand/deps/basis.clj:115`), and expands `:nos/submodule-paths`.
Running it in the Editor would put git and network on Unity's main thread,
and bring back warm-domain state such as the `-base-load-paths` `defonce` (`nostrand-lib/nostrand/core.clj:39`).
The roots are absolute paths into the local git cache, so the file is per-machine and never committed.

Loading: the Editor's `CodeLoadOrder` is `{InitType, FileSystem}` (`magic-unity/Runtime/Magic.Unity.cs:64`).
No consumer init type is preloaded, so a consumer namespace's first load falls to FileSystem,
where the mtime rule (`magic-compiler/src/stdlib/clojure/core.clj:6005-6007`) loads the dev DLL, or compiles the source in memory when it is newer.

**A dev DLL loaded from `Library/` binds to what Unity has already loaded**, so the boot step needs no `AssemblyResolve` handler for it.
`-load-assembly` does `(System.Reflection.Assembly/LoadFrom full-path)`, then `Compiler/InitAssembly` (`core.clj:5964-5969`).
The DLL references `Clojure` and `Magic.Runtime`, which Unity loaded from the package's `Runtime/magic/`,
and, through its namespaces, the consumer's C# dependencies, which Unity loaded from `Assets/Plugins/CSharp`.
Observed in §7 check 0: `smoke.csharp.clj.dll`, which references all three, loaded from `Library/MagicDev/` through `require`,
and its calls into `smoke_csharp` ran, with one copy of each assembly in the domain.

A rerun of `nos` does not change the running domain: dev DLLs already loaded stay loaded, and writing to `Library/` triggers no domain reload (§3).
So the rebuilt set takes effect only at the next domain reload, and the Editor action that reruns `nos` should end by requesting one (`EditorUtility.RequestScriptReload`).
Deleting dev DLLs the domain has loaded is expected to be fine on macOS, where the unlinked file stays mapped until the domain goes. **[inferred]**

**Proposed: FileSystem-first in the Editor**, decided at §7 step 2.
Under InitType-first, `(require 'x :reload)` is a silent no-op for a namespace loaded from the dev set:
its init type is in the domain, and `TryLoadInitType` returns true even when `InvokeInitType` short-circuits on an already-initialized type
(`clojure-runtime/Clojure/CljCompiler/Compiler.cs:1773`, `:1790`; `-load`'s InitType branch, `core.clj:6040-6042`), so FileSystem is never reached.
`ClojureReloader` is unaffected, since it calls `Compiler.load` directly, but a REPL user's `:reload` is not.
FileSystem-first fixes the usual case: a reload follows an edit, so the source is newer than its dev DLL and is compiled in memory.
A namespace already compiled from source is unaffected either way: it lives in a dynamic assembly, which `TryLoadInitType` skips.
The switch is made in the Editor branch of `Boot`, under `#if UNITY_EDITOR && MAGIC_RUNTIME_IN_EDITOR` (§4.3), so players keep their order.
The order also applies to the stdlib, if `Runtime/magic/` stays on `*load-paths*` (§4.3): under FileSystem-first, requiring a namespace whose DLL Unity has already loaded from there reaches `LoadFrom` on that same file.
If Mono returns a second copy rather than Unity's, its types are duplicated and its init runs again, breaking `deftype` and protocol identity. **[unverified]**: check 0 loaded only files Unity had not, so it does not settle `LoadFrom` of a file Unity has.
It does not arise: Unity loads every `Runtime/magic/` DLL, so the boot step replaces `*load-paths*` and that `LoadFrom` is never reached (§4.3).

**The first `nos` run.** Without a roots file `*load-paths*` holds only `Runtime/magic/` (§4.3), so nothing of the consumer's loads, not even from source.
That is the state of a fresh clone, or of a deleted `Library/`.
Proposed: the boot step fails with a message naming the cause and the menu action that runs `nos`, rather than starting a multi-second subprocess on the first `GetVar`, `Require` or save (§4.3).

Optional uses of the roots file:
- **During the transition, the ClojureCLR state's `CLOJURE_LOAD_PATH`**, so both runtimes share one load path. ClojureCLR reads the variable when its `RT` initializes, so the Editor would have to set it before ClojureCLR boots. **[inferred]**
- **The reloader's watch roots**, which consumers pass by hand today. Only the roots under the project root, not the dependency directories in the git cache.

**[unverified]** `establish-project` also sets the assembly search path.
The Editor likely needs none, since Unity has already loaded every C# DLL under `Assets/`,
but consumer Clojure that calls `nostrand.core/reference` or `assembly-load-from` while loading would need nostrand in the Editor.
`smoke-csharp.load-dll` calls `assembly-load-from` only under `CLOJURE_LOAD_PATH`, which the Editor leaves unset, and in check 0 it loaded without nostrand. Not checked for `integrated`.

### 4.3 Editor boot step

`Magic.Unity.Clojure.Boot` (`BootMagicRuntime`, `magic-unity/Runtime/Magic.Unity.cs:41`) boots the runtime only, since players ship no compiler.
Without the compiler bound, `clojure.core/eval` throws `NotSupportedException` "Bind clojure.core/*eval-form-fn* to enable evaluation" (`core.clj:3252`).
An Editor-only boot step must:

- **Load `magic.api` and apply the five bindings**: `*eval-form-fn*` to `magic.api/eval`, plus `*load-fn*`, `*load-file-fn*`, `*compile-file-fn*` and `*macroexpand-1-fn*`.
  On `develop` they live only in `nostrand-lib/Runtime.cs:11-24`, in `Nostrand.dll`, and `RT.CompilerHost` (#186) does not exist.
- **Set `*load-paths*`** from the roots file plus the dev set's directory.
  Today the MAGIC Editor's `*load-paths*` is only `Clojure.dll`'s directory (`core.clj:5911`),
  and MAGIC's `-load` does not read `CLOJURE_LOAD_PATH` (only `nostrand-lib/nostrand/core.clj:33` does),
  so a reloaded file whose `ns` requires a not-yet-loaded namespace fails.
  **Replace, not append**: §7 check 0 found every `Runtime/magic/` DLL already in the domain,
  so a stdlib namespace is not found on disk and `-load` falls through to InitType and Unity's copy, and the double load in §4.2 cannot happen.

**The `Editor/Compiler/` DLLs are in the domain before anything requires `magic.api`**, so the boot step needs no `LoadFile` loop of its own.
Check 0 found all 45, and all 39 of `Runtime/magic/`, loaded from the package directory at domain load, before `Magic.Unity.Clojure.Boot` had run and with no host in the project.
So Unity's plugin loader loads them although their metas say `isPreloaded: 0`,
which also explains the earlier in-Editor probe's 27 `magic.*`/`mage.*` assemblies: Unity had loaded them, not `in-proc`'s host.
`magic.api` is loaded but not initialized: its namespace did not exist after `Boot`, so `RT.UseMagicCompiler()` still has to run its init type (§4.3, below).
A loop would have needed an `AssemblyResolve` handler: `Assembly.LoadFile` (as in `nostrand-lib/Runtime.cs:13`) loads with no binding context,
and an Editor probe loading `nostrand.core.clj.dll` that way died with `TypeLoadException: Could not load file or assembly 'Magic.Runtime, Version=1.0.0.0'`, although Unity had it loaded.

**Where it lives.**

- **The five bindings go in the fork's `RT`**, as one method, e.g. `RT.UseMagicCompiler()`, in the direction of #186.
  It loads the `magic/api` init type, throws naming `Editor/Compiler/` if it is absent, then applies the bindings.
  `nostrand-lib/Runtime.cs`'s `Boot` calls it too, so the bindings exist once.
  `Clojure.dll` also ships to players, which have no compiler, so the method runs only when called.
  A change to `clojure-runtime` C# needs `bb build-runtime`, no bootstrap.
- **The rest of the boot step goes in `Magic.Unity.Clojure.Boot`** (`magic-unity/Runtime/Magic.Unity.cs:22-30`), under `#if UNITY_EDITOR && MAGIC_RUNTIME_IN_EDITOR`:
  after `BootMagicRuntime`, call `RT.UseMagicCompiler()`, set `*load-paths*`, then switch to the lookup order decided at §7 step 2.
  The switch comes last, not at `:64`: core's init loads its helper files, among them `core_deftype` and `core/protocols` (`core.clj:6983-6989`), inside `RT.Initialize`,
  and `magic.api`'s requires load stdlib namespaces, both while `*load-paths*` still holds `Runtime/magic/`.
  `-load` reads `CodeLoadOrder` on every call (`core.clj:5987`), so a late switch takes effect; `:64` stays `{InitType, FileSystem}`.
  The switch needs the reflected `CodeLoadOrder` field and `CodeSource` type, which `BootMagicRuntime` holds as locals today.
  Players compile none of it.
  `Magic.Unity.cs` binds fork-only members by reflection, because Unity may compile it against a ClojureCLR `Clojure.dll` (comment at `:32-40`); the new call follows the same pattern.
  An Editor-only asmdef would not work for this: `Magic.Unity`'s runtime asmdef (`magic-unity/Runtime/Magic.Unity.asmdef`) cannot reference an Editor-only one.

**What triggers it: lazily, on whichever comes first**, the first `GetVar`/`Require`, which already call `Boot()`, or the reloader's first save.
`ClojureReloader.Attempt` calls `Magic.Unity.Clojure.Boot()` before `Compiler.load`, by reflection:
it scans `AppDomain.CurrentDomain.GetAssemblies()` for the type `Magic.Unity.Clojure` and its `Boot` method (`GetMethod("Boot", Type.EmptyTypes)`, so a later overload cannot make it ambiguous), once, caches the result, and invokes it when present.
A scan, not `Type.GetType("Magic.Unity.Clojure, Magic.Unity")`, so it does not depend on how Mono resolves an assembly name inside Unity, which nobody has checked.
That keeps `Magic.Unity.Editor.Reload.asmdef` at its single `Clojure.dll` reference and `noEngineReferences`, changes no consumer API,
and keeps the reloader compilable outside Unity, where `Magic.Unity.cs` cannot go: it calls `UnityEngine.Debug.LogWarning` (`Magic.Unity.cs:55`).
Two details:
- `MethodInfo.Invoke` wraps what `Boot` throws in a `TargetInvocationException`; the reloader logs its `InnerException`, so a missing roots file or compiler is named, not buried.
- A missing type is logged once, at lookup, so a rename does not fail silently and a host without `Magic.Unity`, such as the harness, is not flooded.

In the ClojureCLR state the same call is harmless: `BootMagicRuntime` skips silently when the fork is absent (`Magic.Unity.cs:49-61`), and ClojureCLR initializes on the first `RT.var`.
Nothing pays the boot until Clojure is used; the first save after a domain reload pays it instead.
Eager boot on every domain reload (`[InitializeOnLoad]`) was rejected: every C# recompile would pay it, even in sessions that never touch Clojure.
Measured in `nos`, `magic/api`'s init alone was 464-483 ms (§2).

`Boot()` sets `_booted` before booting (`Magic.Unity.cs:24-26`), so a boot that throws, for a missing roots file or a missing compiler, is never retried in that domain.
Split it: `BootMagicRuntime` runs once and marks itself done;
the Editor part (`RT.UseMagicCompiler()`, the load paths, then the order switch, which cannot fail but must follow the load paths) marks itself done only on success, so a fixed cause is picked up at the next `GetVar`, `Require` or save.
Rerunning all of `Boot()` would mostly work, but the split is cleaner:
`RT.Initialize` adds `AssemblyResolve += ResolveAssembly` on every call (`RT.cs:607`), harmless since `ResolveAssembly` returns null for anything that is not an embedded resource (`RT.cs:572-586`);
it re-interns `in-ns` and `load-file`, which `core.clj` does not redefine;
and `TryLoadInitType("clojure/core")` stops early on an initialized core (`Compiler.cs:1773`).

### 4.4 Hot reload: `ClojureReloader`, plus a boot call

- `ClojureReloader.Attempt`'s only Clojure call is `clojure.lang.Compiler.load(TextReader, sourcePath, sourceName, relativePath)` (`magic-unity/Editor/Reload/ClojureReloader.cs:154`).
  Since #192 the fork has upstream's body and signature (`clojure-runtime/Clojure/CljCompiler/Compiler.cs:1859`): it reads one form at a time and hands each to `clojure.core/eval`,
  which goes to `magic.api/eval` (`magic-compiler/src/magic/api.clj:286`), a fresh in-memory module with `:write-files false`.
  Nothing is written, so no import and no domain reload.
- It binds `*file*` to `relativePath`, and enables reader conditionals only when `sourceName` ends in `.cljc` (`Compiler.cs:1848-1850`), so the reloader's `Path.GetFileName(path)` still picks `:cljr`.
  `magic.test.load` covers `load-file`, `load-string` and the `.cljc` rule; through the reloader, Probe 7m's [E4] measured it (§7 step 1).
- **The changes are dropping `!MAGIC_RUNTIME_IN_EDITOR`** from `magic-unity/Editor/Reload/Magic.Unity.Editor.Reload.asmdef`, and the reflective `Boot()` call in `Attempt` (§4.3).
  Its only precompiled reference stays `Clojure.dll`, which resolves to whichever runtime the state loads.
  No second backend, mirror asmdef or shared watcher.
  Measured outside Unity on 2026-11-02 (§7 step 1): the unchanged `Editor/Reload/*.cs`, compiled against the fork's `Clojure.dll`, passed every check of Probe 7 on MAGIC, and a reload reached a caller in another namespace built with `test-flags` but not one built with `production-flags`.
- **The reloader boots on its first save** (§4.3), so a save before any game code touches Clojure works.
  Today, with no boot step, such a save fails in one of two ways, and neither names the cause; the boot call removes both:
  - **The runtime has not booted.** `Magic.Unity.Clojure.Boot` is lazy, on the first `GetVar` or `Require` (`magic-unity/Runtime/Magic.Unity.cs:22-30`), `RT` has no static constructor, and the reloader references only `Clojure.dll`.
    A save right after a domain reload, before game code has touched Clojure, reaches `RT.var("clojure.core", "eval")` (`Compiler.cs:1864`) with core never loaded,
    and invoking that unbound var throws "Attempting to call unbound fn" (`clojure-runtime/Clojure/Lib/Var.cs:91`). **[inferred]**
  - **The runtime has booted but the compiler is not bound.** `clojure.core/eval` hits the default `*eval-form-fn*` and throws `core.clj:3252`'s `NotSupportedException`, which names the var but not the fix.
- A failing form surfaces as `CompilerException` with its source and line (`Compiler.cs:1896-1904`), carrying `ex-data` and `:clojure.error/phase` (#197).
- Consumers guard their reloader code with `#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR`, copied from `magic-unity/README.md` (e.g. `CljSystem.Reload.cs`), so the guard changes with the asmdef.

### 4.5 What ships in `Editor/Compiler/`

Only the compiler DLLs.
On `develop` it holds 45 `.clj.dll`s: the 36 compiler DLLs and nine of nostrand's eleven namespaces; `in-proc` adds `Nostrand.dll`.
Under this plan nostrand's namespaces and `Nostrand.dll` come out, which simplifies `check-drift`'s partition.

From `in-proc`, keep the Editor-only compiler set, the define-constraint work and its checks, and the coexist probes.
Drop `NostrandHost`/`NostrandEditor`, `Nostrand.dll` in the Editor, and the warm-domain workarounds.

## 5. Reload semantics and limits

- **Ordinary Clojure, as the ClojureCLR reloader does today.**
  `Compiler.load` evaluates every top-level form of the saved file, in order, side effects included.
  `def`/`defn` rebind roots; `defonce` and `defmulti` are skipped once bound (`defmulti`'s guard is `hasRoot` plus `instance? MultiFn`, `core.clj:1823`);
  the `ns` form re-runs but `require` skips loaded libs; dependents are not reloaded.
- **`(require 'x :reload)` from a REPL** is a no-op for a namespace loaded from the dev set unless the Editor is FileSystem-first (§4.2).
- **Incremental, in source order.** A form that throws leaves the earlier defs applied and the later ones not.
  The reloader treats an eval failure as terminal (`ClojureReloader.cs:157-161`), so the half-updated namespace stands until the next good save.
- **Each top-level form defines a dynamic assembly** Mono cannot unload. Measured at 90 to 140 loaded assemblies over 50 evals. Bounded by the next domain reload.
- **Reloaded `deftype`/`defrecord` define new types**; live instances keep the old ones.
- **Direct-linked callers miss reloads**: anything built with `production-flags`, including Clojure DLLs in immutable packages (§6).

### Play mode

- **A `.clj` save triggers nothing in Unity.** The reload takes effect live in the running scene, with no domain reload.
- **Do not gate the reload on `EditorApplication.isPlaying`.** The consumer owns the `Poll` tick, and the ClojureCLR state has reloaded during play since #157.
- **A domain reload is the eraser.** Unity triggers one on every C# recompile and, by default, on entering play mode, so a hot-reloaded edit is lost on the next play.
  While play mode runs, Unity defers a recompile until it ends. **[unverified]** on `2022.3.62f3`.
  Mitigations that compose:
  - the dev set's mtime rule recompiles the edited file at the next load (§4.2);
  - `Enter Play Mode Options` with `Reload Domain` off keeps the whole runtime, reloaded definitions included, across play-mode entry.
    This is what makes the workflow feel like a REPL, and it is a consumer project setting the package cannot set.
- **What a mid-play reload cannot reach**: a fn value already in a field or closure, a coroutine mid-iteration, a delegate already handed to Unity,
  and anything Unity binds by type identity, such as a `deftype` used as a `MonoBehaviour`.
  Those are restart-scope changes; a domain reload destroys the object graph they live in rather than fixing them.

### Write-through: deferred, possibly never

Writing each reloaded file into the dev set from the Editor would spare the recompile at the next domain reload.
If it is ever built:
- `compile-file` skips a DLL that already exists (`api.clj:256`), so it needs delete-before-write until that is fixed;
- `:write-files true` resets `RT._id` to 10000 mid-process (`reset-global-gensym-counter!`, `api.clj:180`, called at `:227`),
  so a macro that defs a gensym-named var could reuse a name an earlier eval defined, the mechanism of #141. **[inferred]**;
- suppress it while `isPlaying`;
- `runtime-compile-file` (`api.clj:306-312`) is the existing entry point, but it binds no `*compile-path*` and writes to `.` unless the caller binds it;
- `compile-file`'s `println` output reaches the Console as one trace-laden `Debug.Log` per line, so pass `:suppress-print-forms true`.

## 6. Costs and risks

- **Development matches player builds in runtime, not in compile flags.**
  The Editor runs `test-flags`, players `production-flags`.
  A much smaller gap than ClojureCLR vs MAGIC, but not zero, so `magic-unity-smoke` and a player build stay part of verification after ClojureCLR is gone, as they must for IL2CPP-only issues anyway.
- **Nothing loads before the first `nos` run** (§4.2), so a fresh clone or a deleted `Library/` needs one before the Editor can run consumer Clojure.
  A dev set older than every source would compile everything from source on load, possibly slow on `integrated`. Unmeasured.
- **Edits since the last `nos` run recompile on every domain reload**, bounded by the number edited.
- **The dev set goes stale across namespaces.**
  The mtime rule compares a namespace's DLL with its own source only.
  Edit a macro, `definline`, protocol, `deftype` or inlined constant in A, and B's cached DLL still loads with the old expansion.
  A hot reload of A has the same limit, but a cold start after a C#-triggered domain reload looks fresh and silently mixes new A with stale B.
  The roots file goes stale the same way when `deps.edn`, `deps-clr.edn` or `.gitmodules` changes.
  Mitigation: an Editor action that reruns `nos`, a full clean rebuild, then requests a script reload so the rebuilt set is loaded (§4.2),
  and a warning when any source or those three files are newer than the last run.
- **Clojure DLLs in immutable UPM packages cannot be made player-only.**
  `Library/PackageCache` discards `.meta` writes, so the package author's MAGIC constraint stands (`CljPluginConstraints.cs:219`).
  They stay Editor-eligible and production-built: a source checkout of such a library is ignored under InitType-first, and its direct-linked callers miss reloads.
  Does not apply to `integrated`: all 109 consumer `.clj.dll`s are in `Assets/Plugins/Magic`, built from `clojure-libs` on the load path;
  the only other set, `Packages/unity-builder/.libs/nostrand`, is in a dot-folder Unity does not import.
- **After the drop, C# that names a Clojure-generated type breaks in the Editor.**
  During the transition this costs nothing new, since the ClojureCLR state already excludes consumer DLLs from the Editor.
  In `integrated` no `.cs` under `Assets/` or `Packages/` names a type under the Clojure roots `hjd`, `flybot`, `editscript`, `fast_edn`, `robertluo`, other than the C# package `hjd.render_pipeline`;
  C# reaches Clojure through `clojure.lang.*` and the `Magic.Unity.Clojure` API. Found by grep; reflection by type name is not covered.

## 7. Work order

Steps 0 and 1 are checks; each later step starts with a test that fails on the current code.

0. **What the Editor's domain holds and binds to**, inside Unity in the MAGIC state:
   - before anything requires `magic.api`, list the loaded assemblies from `Editor/Compiler/` and `Runtime/magic/` (§4.3).
     Decides whether the boot step needs a `LoadFile` loop, and whether it replaces `*load-paths*` or appends to it;
   - `LoadFrom` one dev DLL from `Library/MagicDev/` whose namespace uses a type from a C# dependency, and call a fn in it (§4.2).
     A `TypeLoadException` naming `Magic.Runtime` or the C# dependency means the boot step needs an `AssemblyResolve` handler.
     Step 3's task does not exist yet, so build the DLL by hand: a one-off `compile-project` with `:flags test-flags`, `:out "Library/MagicDev"` and `:clean? true`.

   **Done 2026-11-02**, in `unity-examples/magic-unity-smoke` on Unity `2022.3.62f3`, macOS, headless (`-batchmode -quit -nographics -executeMethod Check0Probe.Run`), then repeated from the menu in the GUI Editor with the same results.
   The project was on `develop`'s package (`file:` to `magic-unity/`, so `Local`), with `MAGIC_RUNTIME_IN_EDITOR` set for Standalone.
   Its consumer `.clj.dll`s had been deleted from `Assets/Plugins/Magic`, so none was preloaded, as after step 4.
   The probe was a throwaway Editor script, an `[InitializeOnLoad]` listing plus a menu item, deleted afterwards.
   The dev set was `smoke.csharp`, `smoke.check` and `smoke-csharp.load-dll`, built by the repo's `nos` with `:csharp-out "Assets/Plugins/CSharp"`.
   `smoke_csharp.dll` there stayed byte-identical, as `pending-copies` predicts.
   - At domain load, before `Boot` (`_booted` false): `Runtime/magic/` 39 of 39 loaded, `Editor/Compiler/` 45 of 45, all from the package directory. No `LoadFile` loop; replace `*load-paths*` (§4.3).
   - After `Boot`: `*load-paths*` was `[".../magic-unity/Runtime/magic"]`, and the `magic.api` namespace did not exist.
   - With `Library/MagicDev` appended, `require` of `smoke.csharp` loaded all three dev DLLs from there,
     one copy each of `Clojure` and `Magic.Runtime` from `Runtime/magic/` and of `smoke_csharp` from `Assets/Plugins/CSharp`,
     and `smoke.csharp/suite`'s three calls into `smoke_csharp.Greeter` passed. No duplicate non-dynamic assembly; no error in `Editor.log`. No `AssemblyResolve` handler.
   - Not covered: a package from `Library/PackageCache` (a tarball or registry install, where the AppleDouble sidecars appear).
1. **`ClojureReloader` on MAGIC, on Mono outside Unity.** The check that could invalidate §4.4.
   Load `A` calling `B`, both built with `test-flags`; `Compiler.load` a new `B`; assert `A` sees the new body, and that built with `production-flags` it does not.
   Also: a `.cljc` conditional picks `:cljr`.
   Drive the real `Editor/Reload/*.cs`. The `Tests~/reloader/` harness, which stages a runtime directory and drives it under `csc` and `mono`, exists only on `in-proc-hot-reload`; port it, staging `Runtime/magic`, `Editor/Compiler` and the nostrand build's `Nostrand.dll` in place of `Runtime/clojure-clr`.
   The probe to port is Probe 7, `ReloaderEvalTest.cs`, the only one that evaluates through `ClojureReloader`.
   It boots ClojureCLR with `RT.Init()` (`in-proc-hot-reload:magic-unity/Tests~/reloader/ReloaderEvalTest.cs:104`), which the fork's `RT` does not have, so its boot is replaced.
   `Magic.Unity.Clojure` is absent there, so the harness boots with `Nostrand.Runtime.Boot(dir)`, which calls `RT.UseMagicCompiler()` once step 2 adds it (§4.3).
   On `develop` it does `LoadFile` of every `.clj.dll` in the staged directory, `RT.Initialize`, core and `magic/api`, then the five bindings (`nostrand-lib/Runtime.cs:11-24`).
   Outside Unity every referenced DLL sits in the executable's own directory, so probing finds it, unlike the `LoadFile` failure of §4.3.
   Identity is another matter: `Boot` loads the stdlib with `LoadFile`, the fixtures come in through `LoadFrom`, and a fixture reference to a stdlib assembly by name could load a second copy.
   nostrand's own resolver (`nostrand/Program.cs:22`, `resolve-assembly-load`) only loads from the assembly path, so it would not prevent that either.
   Not observed: in both processes no non-dynamic assembly was loaded twice, checked after the fixtures loaded and again after the reload, and nothing threw `TypeLoadException`.
   A `TypeLoadException` or duplicated types in the harness is a finding to report, not something to work around.
   The fixtures `A` and `B` are built twice by `nos`, `compile-project` with `:flags test-flags` into one directory and with `production-flags` into another, `:clean? true` both times.
   Run each in its own `mono` process: both sets define the same namespaces and assembly names, so in one domain the second set's `require` is skipped as already loaded, or binds to the first set's assemblies.
   `Boot` sets no `CodeLoadOrder`, so the harness runs the fork's default, `{FileSystem, InitType, EmbeddedResource}` (`RT.cs:4343`).
   Replace `*load-paths*` with only the DLL directory under test, not the fixtures' sources.
   Replacing drops the default, `Clojure.dll`'s directory, here the staged runtime directory; `-load` then falls through to the init types `Boot` already loaded, as in §4.3's "replace, not append", which holds here by construction.
   Not the sources, because a source on the load path newer than its DLL is compiled in memory under the `magic.flags` defaults, direct linking off, which would make the `production-flags` control pass for the wrong reason.
   The new `B` reaches `Compiler.load` by path, so it needs no load-path entry.

   **Done 2026-11-02**, as Probe 7m of `bb reloader-probes` (`magic-unity/Tests~/reloader/`, system `mono` on macOS, fixtures built by the repo's `NostrandMain.exe`).
   Probe 7's source runs unchanged on both runtimes, with `-define:MAGIC` selecting the `Nostrand.Runtime.Boot` boot; the new check is [E13], `fixture.a` calling `fixture.b`, both loaded from the set under test, one assembly each.
   - `test-flags`: `(fixture.a/call-b)` returned `"old"`, then `"new"` after the reloader evaluated an edited `fixture.b`. Pass.
   - `production-flags`: `"old"` before and after, although `fixture.b/value` itself returned `"new"`. Pass.
   - `.cljc`: [E4]'s `#?(:cljr ...)` read `"cljr"`. Pass.
   - Probe 7's other checks, [E1]–[E12], also passed on MAGIC: the reloader's retry, supersede, containment and dispose paths do not depend on the runtime.

   The harness was stale when ported: Probes 3, 4 and 6 call a three-argument `DebouncedFileWatcher` constructor, a settle-window test seam, which neither `develop` nor `in-proc-hot-reload` has.
   They do not compile, so `bb reloader-probes` exits 1 on them; left as is, since step 1 forbids changing `Editor/Reload/`.
2. **Editor boot step** (§4.3): `RT.UseMagicCompiler()`, the Editor branch of `Magic.Unity.Clojure.Boot`, and the reloader's reflective boot call.
   No single place can run all of it before step 5: inside Unity the reloader is absent in the MAGIC state until its constraint goes, and the harness cannot compile `Magic.Unity.cs`. So split the tests.
   In the harness:
   - with a stub `Magic.Unity.Clojure` whose `Boot()` records the call, a save calls it once before `Compiler.load`;
   - without the stub, the save proceeds and the missing type is logged once;
   - a stub `Boot()` that throws is logged by its inner exception, not as `TargetInvocationException`;
   - `RT.UseMagicCompiler()` without `magic.api` throws naming `Editor/Compiler/`.

   The scan finds the stub whatever assembly it is compiled into.

   Inside Unity, in the MAGIC state, a step 0–style check through `GetVar`,
   with a hand-written roots file until step 3's task writes one, listing the project's source roots, one absolute path per line; step 0's hand-built DLLs are already in `Library/MagicDev/`, where the boot step expects the dev set.
   This fixes the roots file's format.
   - the Editor branch of `Boot` binds the compiler and sets `*load-paths*`;
   - a file passed to `Compiler.load` (the reloader's call, made directly, since the reloader is absent here until step 5) whose `ns` requires a namespace not yet loaded resolves it, through the load paths that branch set;
   - when the Editor part fails, it is retried on the next call, while `BootMagicRuntime` runs only once.
   Decide the first-run behaviour here (§4.2): with no roots file, the boot step either fails naming the cause and the menu action (proposed) or runs `nos` itself; test whichever is chosen.
   Decide the lookup order here (§4.2): under FileSystem-first, `(require 'x :reload)` after editing a namespace loaded from a DLL picks up the edit; under InitType-first it does not.
   Under FileSystem-first, also check, as confirmation if step 0 led to replacing the load path, that requiring a stdlib namespace Unity has already loaded from `Runtime/magic/` leaves one copy of its assembly in the domain (§4.2).
   Run the same count right after boot, for core's helper files, to confirm that switching last kept each to one copy (§4.3).
3. **A new `nos` entry point writes the dev set and the roots file** (§4.2), the roots file in the format step 2's boot step reads: `test-flags`, `:out "Library/MagicDev"`, `:clean? true`, C# dependencies to `Assets/Plugins/CSharp`, never `Assets/Plugins/Magic` among the roots.
   Testable outside Unity, starting from: a second run after editing a namespace rewrites its DLL.
   Check that deleting dev DLLs a domain has loaded is harmless, and add the Editor action that reruns `nos` and then requests a script reload.
4. **Consumer DLLs player-only** (§4.1).
   Must precede step 5: until then the Editor loads the direct-linked production DLLs from `Assets/Plugins/Magic`, and a cross-namespace reload check fails for the wrong reason.
5. **Drop the reloader's constraint** (§4.4). By hand in `integrated`:
   - right after a domain reload, before any `GetVar` or `Require`, save a file: the reloader boots the runtime and reloads it (§4.3);
   - reload a `defn` and a `defmethod`, and see a caller in another namespace pick it up;
   - enter play mode with `Reload Domain` on and off.
6. **The shipped build's trigger** (§4.1): decide here between a build entry point (proposed: `nos` with the project root as working directory, refresh, `BuildPipeline.BuildPlayer`), CI, or a pre-build hook, which would first need its mid-build import tested.
   Check whether importing a player-only managed DLL triggers a domain reload; it affects how disruptive a player build is, not whether it works.
7. **Trim `Editor/Compiler/`** (§4.5), then `bb check-drift`, and `bb coexist-noise` in both states, since changing the reloader's constraint changes what each state loads.
8. **Before dropping ClojureCLR**:
   - a player build of `unity-examples/magic-unity-smoke` on `2022.3.62f3`, compiler absent, IL2CPP succeeding;
   - a full `nos build` of a consumer under Unity's bundled Mono;
   - cold-start time in `integrated`, with a current dev set and with one older than every source;
   - the time of one dev-set build of `integrated`, since every run is a full recompile.

## 8. Docs to update

- `docs/unity-integration.md`: its `Hot reload` section says the reloader is ClojureCLR-only,
  and "The two plugin folders" (`:55`) changes once `Assets/Plugins/Magic` is player-only and the dev run also writes `Assets/Plugins/CSharp`.
- `magic-unity/README.md`: `Editor API`, its `#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR` guard for reloader code, and the "What a reload changes" table, which needs two rows:
  a `defn` body reloads only if the calling namespace was compiled with direct linking off,
  and a reload during play mode takes effect live but cannot reach an object graph the old code already built.
- The runtime-selection table, once consumer DLLs are player-only and again when ClojureCLR is dropped.
- The local, gitignored `CLAUDE.md` and `magic-unity/CLAUDE.md`: the `Editor/Reload/` layout entry and the constraint description,
  and, once `in-proc` is abandoned, their `in-proc` sections, which describe the rejected design.
