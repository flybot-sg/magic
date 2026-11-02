# Plan: MAGIC hot reload in the Unity Editor

Steps 0 and 1 are done (§5.1); nothing else is implemented.
Line references hold on `develop` as of 2026-10-30, plus `magic-unity/README.md`'s "What a reload changes" and "Save order", which land with this plan.
Open claims are tagged **[inferred]** or **[unverified]**; everything else was read in code or measured.

## 1. Goal

Player builds run MAGIC; the Editor runs ClojureCLR only for its hot reload, a separate port that diverges from MAGIC.
Move the Editor to MAGIC, then drop ClojureCLR, in two gates:

1. **`integrated` switches to MAGIC by default**, with ClojureCLR still installed as a fallback, once:
   - steps 2–7 in §5.2 are done and their checks are green (step 8 belongs to gate 2);
   - every reload behaviour `magic-unity/README.md` documents on ClojureCLR has been checked on MAGIC: the six rows of "What a reload changes" and the two outcomes in "Save order".

   The package cannot make MAGIC the Editor default: Unity's constraint grammar forces ClojureCLR to be the default (rationale beside `plugin-sets` in `bb/magic/unity.clj`).
   So the switch is `integrated` committing `MAGIC_RUNTIME_IN_EDITOR` in its project settings.
2. **ClojureCLR is removed** (`clojure-clr/`, `bb sync-clojure-clr`, the constraint scheme around it) after a period of daily use under gate 1 with no fallback and no open hot-reload bugs.
   The length of that period is still to set.

## 2. Design

| Job | Runs in | Output | Flags |
|---|---|---|---|
| Shipped DLLs | a `nos` subprocess | `Assets/Plugins/Magic`, player-only | `production-flags` |
| Editor runtime and hot reload | the Editor process | in memory, loaded from a `nos`-built dev set in `Library/MagicDev` | `test-flags` |

The Editor never runs nostrand's build tasks and never writes a DLL.
Out of process, the output is byte-identical to CI and the Editor stays responsive during a build.
Running them inside the Editor (the `in-proc` branch) was rejected: it saves only the compiler's boot, about 0.85 s per build, and reusing the Editor's domain broke the build.
Observed in `magic-unity-smoke` on 2026-10-29: with the previous output already loaded, a rebuild skipped compiling and wiped it, and the C# dependency copy proposed deleting a DLL the project needs.

### 2.1 Shipped DLLs

- `nos` with `production-flags` into `Assets/Plugins/Magic`, built only for a player build, since nothing in the Editor loads that set.
- Launch `nos` with its working directory set to the project root: it establishes the project from its cwd (`nostrand/Program.cs:24`).
- Unity's bundled Mono runs `nos`, so consumers need no system Mono. It ran `NostrandMain.exe version` in 2.39 s; a full build under it is **[unverified]**.
- **Trigger, proposed: a build entry point.** A menu action runs `nos`, calls `AssetDatabase.Refresh()`, then `BuildPipeline.BuildPlayer`; CI does the same.
  Not a pre-build hook, which would import DLLs mid-build beside `MagicPreprocessor` and `PlayerCljAssemblies`; whether Unity picks those up is **[unverified]**.
- **Consumer `.clj.dll`s become player-only.** `CljPluginConstraints` adds a separate `!UNITY_EDITOR` entry, which ANDs with the existing one **[inferred]**.
  Today they carry `!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR` (`magic-unity/Editor/CljPluginConstraints.cs:19`), so Unity preloads them in the MAGIC state and their init types would shadow the dev set.

### 2.2 Dev set and roots file

One new `nos` entry point, a task or a `magic.edn` key such as `:dev`, writes both, so they always agree.
`nos build` and `nos test` read only `magic.edn`'s `:build` and `:test` (`nostrand-lib/nostrand/tasks.clj:300-317`).

- **The dev set**: `compile-project` with `:flags test-flags`, `:out "Library/MagicDev"`, `:clean? true`, `:csharp-out "Assets/Plugins/CSharp"`.
  - `test-flags` is `production-flags` with direct linking and strongly-typed invokes off.
    A direct-linked caller calls the callee's `invokeStatic` and never sees a reload (§5.1, step 1); this applies to callees that are neither variadic nor dynamic (`magic-compiler/src/magic/core.clj:1189-1209`).
    The committed stdlib and compiler DLLs are already built without it.
  - `:clean? true` because `compile-file` skips a DLL that already exists (`magic-compiler/src/magic/api.clj:250-256`), so a rerun would keep every stale DLL.
    Cleaning empties only `:out`, never `:csharp-out` (`tasks.clj:209-210`). Every run is a full recompile of the consumer.
  - `:csharp-out` puts dependencies' C# DLLs where Unity loads them. An unchanged one is not rewritten (step 0), so a run causes an import only when a dependency's C# changed.
  - `Library/` is never imported: no `.meta` files, no path to a player build, and writing there triggers no domain reload, even with an `AssetDatabase.Refresh()` behind it (measured in `integrated`).
- **The roots file**: the source roots `establish-project` resolves (`nostrand-lib/nostrand/core.clj:151`), one absolute path per line; the format is fixed at step 2.
  It comes from `nos` because resolving deps can clone and expand submodules, which must stay off Unity's main thread.
  Per machine, never committed, never lists `Assets/Plugins/Magic`, or the mtime rule could load the direct-linked production DLLs.
- **Loading.** No consumer init type is preloaded, so a namespace's first load falls to FileSystem.
  There the mtime rule (`magic-compiler/src/stdlib/clojure/core.clj:6005-6007`) loads the dev DLL, or compiles the source in memory when it is newer.
  A dev DLL binds to the `Clojure`, `Magic.Runtime` and C# dependencies Unity has already loaded (step 0).
- **Reruns.** A rerun of `nos` takes effect only at the next domain reload, so the Editor action that reruns it ends with `EditorUtility.RequestScriptReload`.
  Deleting dev DLLs a domain has loaded is fine on macOS **[inferred]**.
- **First run.** Without a roots file nothing of the consumer's loads.
  Proposed: the boot step fails, naming the cause and the menu action that runs `nos`,
  rather than running `nos` itself, a multi-second subprocess on the first `GetVar`, `Require` or save.
- **Lookup order, proposed: FileSystem-first in the Editor**, decided at step 2.
  Today the Editor runs `{InitType, FileSystem}` (`magic-unity/Runtime/Magic.Unity.cs:64`); players keep their order either way.
  Under InitType-first, `(require 'x :reload)` is a silent no-op for a namespace loaded from the dev set, since `TryLoadInitType` finds its init type (`clojure-runtime/Clojure/CljCompiler/Compiler.cs:1773`, `:1790`).
  The reloader calls `Compiler.load` directly and is unaffected; a REPL user is not.
- Optional uses of the roots file: the ClojureCLR state's `CLOJURE_LOAD_PATH` during the transition **[inferred]**, and the reloader's watch roots, project-local ones only.
- **[unverified]** whether consumer Clojure needs nostrand in the Editor, through `nostrand.core/reference` or `assembly-load-from` at load time.
  Smoke's did not (step 0); `integrated` is unchecked.

### 2.3 Editor boot step

`Magic.Unity.Clojure.Boot` (`magic-unity/Runtime/Magic.Unity.cs:22-30`) boots only the runtime.
Unity has already loaded every `Runtime/magic/` and `Editor/Compiler/` DLL at domain load (step 0), although their metas say `isPreloaded: 0`.
Do not add a loop that loads them: an Editor probe's `Assembly.LoadFile` of `nostrand.core.clj.dll` failed with `TypeLoadException` on `Magic.Runtime`, although Unity had it loaded.
So the Editor part only has to:

1. **Bind the compiler** with a new `RT.UseMagicCompiler()` in the fork, in the direction of `RT.CompilerHost` (#186).
   It runs the `magic/api` init type (loaded but not initialized), throws naming `Editor/Compiler/` if it is absent,
   and binds `*eval-form-fn*`, `*load-fn*`, `*load-file-fn*`, `*compile-file-fn*` and `*macroexpand-1-fn*`.
   Today those bindings live only in `nostrand-lib/Runtime.cs:11-24`, whose `Boot` then calls the new method instead. C# change: `bb build-runtime`.
2. **Replace `*load-paths*`** with the roots file's entries plus `Library/MagicDev`.
   Today it holds only `Clojure.dll`'s directory (`core.clj:5911`), and MAGIC's `-load` ignores `CLOJURE_LOAD_PATH`, which only nostrand reads (`nostrand-lib/nostrand/core.clj:33`),
   so a file whose `ns` requires a not-yet-loaded namespace fails.
   Replace, not append: with `Runtime/magic/` off the path, a stdlib namespace should fall through to Unity's loaded copy rather than a second `LoadFrom` of its file, which would duplicate its types **[unverified]**; step 2 checks it.
3. **Switch the lookup order**, last: core's helper files and `magic.api`'s requires load while the path still holds `Runtime/magic/`, and `-load` reads `CodeLoadOrder` on every call (`core.clj:5987`).
   It needs the reflected `CodeLoadOrder` field and `CodeSource` type, which `BootMagicRuntime` holds only as locals today.

It goes in `Magic.Unity.Clojure.Boot` under `#if UNITY_EDITOR && MAGIC_RUNTIME_IN_EDITOR`, binding fork-only members by reflection as `BootMagicRuntime` already does.
Split `_booted`, which is set before booting today (`Magic.Unity.cs:24-26`):
`BootMagicRuntime` runs once, and the Editor part marks itself done only on success, so a fixed cause, such as a missing roots file, is picked up on the next call.

**Trigger: lazy**, on the first `GetVar`/`Require` or the reloader's first save.
Eager boot on every domain reload was rejected: every C# recompile would pay `magic/api`'s init, about 0.5 s.
The reloader calls `Boot` by reflection, keeping its asmdef at a single `Clojure.dll` reference and compilable outside Unity:
- it scans the loaded assemblies once for `Magic.Unity.Clojure` and `GetMethod("Boot", Type.EmptyTypes)`, caches the result, and invokes it;
- it logs a throwing `Boot` by its `InnerException`, not as `TargetInvocationException`;
- it logs a missing type once.

In the ClojureCLR state the call is harmless: `BootMagicRuntime` skips when the fork is absent.

### 2.4 Hot reload

`ClojureReloader` is reused unchanged (step 1).
Its only Clojure call, `Compiler.load` (`magic-unity/Editor/Reload/ClojureReloader.cs:154`), evaluates each form in memory through `magic.api/eval`, writes nothing, and picks `:cljr` in a `.cljc` file.
The compiler has run inside the Editor once: on 2026-10-29, through `in-proc`'s host in `integrated`, in Unity's Mono `6.13.0`, its `compile-file` output structurally identical to a `nos` build of the same file.
The changes:
- drop `!MAGIC_RUNTIME_IN_EDITOR` from `magic-unity/Editor/Reload/Magic.Unity.Editor.Reload.asmdef`;
- add the boot call (§2.3). Without it, a save before game code touches Clojure fails without naming the cause.

Consumers guard their reloader code with `#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR`, copied from `magic-unity/README.md`, so the guard changes with the asmdef.

### 2.5 `Editor/Compiler/`

Ships only the compiler: nostrand's nine namespaces come out, 45 DLLs down to 36, which simplifies `check-drift`'s partition.
From `in-proc`, keep the Editor-only compiler set, the define-constraint work and its checks, and the coexist probes;
drop its host, `Nostrand.dll` in the Editor and the warm-domain workarounds (§6).

## 3. Reload semantics and limits

- **Ordinary Clojure, as the ClojureCLR reloader does today.** Every top-level form of the saved file is evaluated in order, side effects included.
  `def`/`defn` rebind roots; `defonce` and `defmulti` are skipped once bound; the `ns` form re-runs but `require` skips loaded libs; dependents are not reloaded.
- **Incremental.** A form that throws leaves the earlier defs applied and the later ones not, until the next good save.
- **Each top-level form defines a dynamic assembly** Mono cannot unload: 90 to 140 over 50 evals, bounded by the next domain reload.
- **Reloaded `deftype`/`defrecord` define new types**; live instances keep the old ones.
- **Direct-linked callers miss reloads**: anything built with `production-flags`, including Clojure DLLs in immutable packages (§4).
- **Play mode.** A save takes effect live in the running scene; do not gate it on `EditorApplication.isPlaying`:
  the consumer owns the `Poll` tick, and the ClojureCLR state has reloaded during play since #157.
  A domain reload, on every C# recompile and by default on entering play mode, erases reloaded edits; the dev set's mtime rule recompiles them at the next load (a cost, §4).
  `Enter Play Mode Options` with `Reload Domain` off keeps them across play-mode entry, a consumer setting the package cannot set.
  Unity defers a recompile during play mode **[unverified]** on `2022.3.62f3`.
  A reload cannot reach a fn already captured in a field or closure, a running coroutine, a delegate handed to Unity, or a `deftype` Unity binds by type.
- **Write-through**, compiling each reloaded file into the dev set from the Editor, is deferred.
  If built: delete before write (`api.clj:256` skips existing DLLs), beware the mid-process `RT._id` reset (`api.clj:180`, the mechanism of #141) **[inferred]**, and suppress it while playing.

## 4. Costs and risks

- **The Editor matches players in runtime, not compile flags** (`test-flags` vs `production-flags`), so `magic-unity-smoke` and a player build stay part of verification.
- **Nothing loads before the first `nos` run**: a fresh clone or a deleted `Library/` needs one. A dev set older than every source compiles everything on load, unmeasured on `integrated`.
- **Edits since the last `nos` run recompile on every domain reload**, a cost that grows with each edit until the next run.
- **The dev set goes stale across namespaces.** The mtime rule compares a DLL with its own source only, so editing a macro, `definline`, protocol, `deftype` or inlined constant in A leaves B's DLL with the old expansion.
  A hot reload of A has the same limit, but a cold start after a C#-triggered domain reload looks fresh and silently mixes new A with stale B.
  The roots file goes stale when `deps.edn`, `deps-clr.edn` or `.gitmodules` changes.
  Mitigation: the Editor action that reruns `nos` and reloads, plus a warning when any source or those files are newer than the last run.
- **Clojure DLLs in immutable UPM packages cannot be made player-only**: `Library/PackageCache` discards `.meta` writes, so the package author's constraint stands (`CljPluginConstraints.cs:219`).
  They stay loaded in the Editor and production-built: under InitType-first their init types shadow a source checkout of the library, and their callers miss reloads.
  Not an issue for `integrated`, whose 109 consumer `.clj.dll`s are all in `Assets/Plugins/Magic`.
- **After the drop, C# naming a Clojure-generated type breaks in the Editor.** In `integrated`, none does (by grep; reflection by name not covered).

## 5. Work

### 5.1 Done

0. **What the Editor's domain holds and binds to.** 2026-11-02, `unity-examples/magic-unity-smoke`, Unity `2022.3.62f3`, macOS, MAGIC state, headless and then in the GUI.
   The package was `develop`'s, as a local `file:` package; the consumer DLLs were removed from `Assets/Plugins/Magic` to match step 4.
   - At domain load, before any boot: all 39 `Runtime/magic/` and all 45 `Editor/Compiler/` DLLs loaded from the package. So no `LoadFile` loop, and `*load-paths*` is replaced.
   - After `Boot`: `*load-paths*` was only `Runtime/magic/`, and the `magic.api` namespace did not exist, so its init type has not run.
   - A `test-flags` `smoke.csharp` built by `nos` into `Library/MagicDev`, `require`d from there, bound to Unity's `Clojure`, `Magic.Runtime` and `smoke_csharp`, one copy each; its three calls into `smoke_csharp.Greeter` passed. So no `AssemblyResolve` handler.
   - The build's `:csharp-out "Assets/Plugins/CSharp"` left `smoke_csharp.dll` byte-identical.
   - `smoke-csharp.load-dll` loaded without nostrand: it calls `assembly-load-from` only under `CLOJURE_LOAD_PATH`, which the Editor leaves unset.
   - Not covered: a package from `Library/PackageCache` (tarball or registry install).
1. **`ClojureReloader` on MAGIC, outside Unity.** 2026-11-02, Probe 7m of `bb reloader-probes`: `ReloaderEvalTest.cs` built with `-define:MAGIC`, check [E13]; setup and pitfalls in `magic-unity/Tests~/reloader/README.md`.
   The unchanged `Editor/Reload/*.cs`, on the fork's runtime booted by `Nostrand.Runtime.Boot`, with `fixture.a` calling `fixture.b`, both loaded from `nos`-built DLLs:
   - built with `test-flags`, `fixture.a` saw the reloaded `fixture.b`; built with `production-flags`, it kept the old body;
   - a `.cljc` reader conditional picked `:cljr`;
   - Probe 7's other checks passed on MAGIC too, with no duplicate assemblies.

   Probes 3, 4 and 6 of the pack do not compile: they call a three-argument `DebouncedFileWatcher` constructor the shipped watcher does not have. `bb reloader-probes` exits 1 until they are fixed or the seam is restored.

### 5.2 To do

Each step starts with a test that fails on the current code.

2. **Editor boot step** (§2.3): `RT.UseMagicCompiler()`, the Editor branch of `Magic.Unity.Clojure.Boot`, and the reloader's reflective boot call.
   In the harness:
   - with a stub `Magic.Unity.Clojure` whose `Boot()` records the call, a save calls it once before `Compiler.load`;
   - without the stub, the save proceeds and the missing type is logged once;
   - a stub `Boot()` that throws is logged by its inner exception;
   - `RT.UseMagicCompiler()` without `magic.api` throws naming `Editor/Compiler/`.

   Inside Unity, in the MAGIC state, through `GetVar`, with a hand-written roots file and step 0's DLLs in `Library/MagicDev/`.
   Call `Compiler.load` directly: the reloader is absent in the MAGIC state until step 5.
   - the Editor branch binds the compiler and sets `*load-paths*`;
   - a file passed to `Compiler.load` whose `ns` requires a not-yet-loaded namespace resolves it;
   - a failed Editor part is retried on the next call, while `BootMagicRuntime` runs once;
   - right after boot, each of core's helper files is loaded once;
   - after the order switch, requiring a stdlib namespace Unity has loaded leaves one copy of its assembly (§2.3).

   Decide here: the first-run behaviour (§2.2) and the lookup order (§2.2), testing that `(require 'x :reload)` picks up an edit under the chosen order.
3. **A new `nos` entry point writes the dev set and the roots file** (§2.2).
   Testable outside Unity, starting from: a second run after editing a namespace rewrites its DLL.
   Check that deleting dev DLLs a domain has loaded is harmless; add the Editor action that reruns `nos` and requests a script reload.
4. **Consumer DLLs player-only** (§2.1).
   Must precede step 5: until then the Editor preloads the direct-linked production DLLs, and a cross-namespace reload fails for the wrong reason.
5. **Drop the reloader's constraint** (§2.4). By hand in `integrated`:
   - right after a domain reload, before any `GetVar` or `Require`, save a file: the reloader boots the runtime and reloads it;
   - reload a `defn` and a `defmethod`, and see a caller in another namespace pick it up;
   - enter play mode with `Reload Domain` on and off.
6. **The shipped build's trigger** (§2.1): a build entry point (proposed), CI, or a pre-build hook, which would first need its mid-build import tested.
   Check whether importing a player-only managed DLL triggers a domain reload.
7. **Trim `Editor/Compiler/`** (§2.5), then `bb check-drift`, and `bb coexist-noise` in both states.
8. **Before dropping ClojureCLR**:
   - a player build of `unity-examples/magic-unity-smoke` on `2022.3.62f3`, compiler absent, IL2CPP succeeding;
   - a full `nos build` of a consumer under Unity's bundled Mono;
   - cold-start time in `integrated`, with a current dev set and with one older than every source;
   - the time of one dev-set build of `integrated`.

## 6. Rejected and pitfalls

- **Build tasks inside the Editor (`in-proc`)** needed four warm-domain workarounds: `set-load-path` rather than `*load-paths*`, `RT.PostBootstrapInit()` once per domain, a fresh `*loaded-libs*` per call, and a binding frame that always pops.
  It also faced emission relative to the cwd, the mid-process `RT._id` reset, and `Environment/Exit` in task files, fatal in the Editor.
- **An Editor-only asmdef for the boot step**: `Magic.Unity`'s runtime asmdef cannot reference one, so the Editor branch lives in `Magic.Unity.cs` under `#if`.
- **`Type.GetType("Magic.Unity.Clojure, Magic.Unity")` in the reloader**: it depends on how Mono resolves an assembly name inside Unity, which nobody has checked; scan the loaded assemblies instead.
- **A second reloader backend or mirror asmdef**: unnecessary, since the existing reloader runs on MAGIC unchanged (step 1).

## 7. Docs to update

- `docs/unity-integration.md`: its `Hot reload` section says the reloader is ClojureCLR-only,
  and "The two plugin folders" changes once `Assets/Plugins/Magic` is player-only and the dev run also writes `Assets/Plugins/CSharp`.
- `magic-unity/README.md`: `Editor API`, its reloader guard, and the "What a reload changes" table, which needs two rows:
  a `defn` body reloads only if the calling namespace was compiled with direct linking off,
  and a reload during play mode takes effect live but cannot reach an object graph the old code already built.
- The runtime-selection table, once consumer DLLs are player-only and again when ClojureCLR is dropped.
- The local `CLAUDE.md` and `magic-unity/CLAUDE.md`: the `Editor/Reload/` layout entry and the constraint description, and their `in-proc` sections once `in-proc` is abandoned.
