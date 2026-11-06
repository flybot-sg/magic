# Plan: MAGIC hot reload in the Unity Editor

Steps 0 to 3 are done (§5.1); nothing else is implemented.
Line references hold on `feat/magic-editor-hot-reload` as of 2026-11-06, #186's commits plus step 3's working tree, and `magic-unity/README.md`'s "What a reload changes" and "Save order", which land with this plan.
Open claims are tagged **[inferred]** or **[unverified]**; everything else was read in code or measured.

## 1. Goal

Player builds run MAGIC; the Editor runs ClojureCLR only for its hot reload, a separate port that diverges from MAGIC.
Move the Editor to MAGIC, then drop ClojureCLR, in two gates:

1. **`integrated` switches to MAGIC by default**, with ClojureCLR still installed as a fallback, once:
   - steps 2–7 in §5 are done and their checks are green (step 8 belongs to gate 2);
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
- Unity's bundled Mono runs `nos`, so consumers need no system Mono. It ran `NostrandMain.exe version` in 2.39 s, and step 3's `nos dev`, a full `test-flags` build of smoke's 14 namespaces, in 3.5 to 4.7 s.
  A `production-flags` build under it, and any build of `integrated`, is **[unverified]**.
- **Trigger, proposed: a build entry point.** A menu action runs `nos`, calls `AssetDatabase.Refresh()`, then `BuildPipeline.BuildPlayer`; CI does the same.
  Not a pre-build hook, which would import DLLs mid-build beside `MagicPreprocessor` and `PlayerCljAssemblies`; whether Unity picks those up is **[unverified]**.
- **Consumer `.clj.dll`s become player-only.** `CljPluginConstraints` adds a separate `!UNITY_EDITOR` entry, which ANDs with the existing one **[inferred]**.
  Today they carry `!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR` (`magic-unity/Editor/CljPluginConstraints.cs:19`), so Unity preloads them in the MAGIC state and their init types would shadow the dev set.

### 2.2 Dev set and roots file

**`nos dev`, decided at step 3**, writes both, so they always agree: `nostrand.tasks/dev` (`nostrand-lib/nostrand/tasks.clj:311`), run from the project root.
A task, not a `magic.edn` key: it takes `:build`'s `:aliases`, `:namespaces`, `:exclude` and `:flags`, so the dev set compiles what ships, and fixes everything else, since the boot step hardcodes both paths (`Magic.Unity.Clojure.RootsFile`, `DevSetDirectory`).
A `:build` flag that turns direct linking back on carries over.

- **The dev set**: `compile-project` with `:flags test-flags`, `:out "Library/MagicDev"`, `:clean? true`, `:csharp-out "Assets/Plugins/CSharp"`.
  - `test-flags` is `production-flags` with direct linking and strongly-typed invokes off.
    A direct-linked caller calls the callee's `invokeStatic` and never sees a reload (§5.1, step 1); this applies to callees that are neither variadic nor dynamic (`magic-compiler/src/magic/core.clj:1198-1201`).
    The committed stdlib and compiler DLLs are already built without it.
  - `:clean? true` because `compile-file` skips a DLL that already exists (`magic-compiler/src/magic/api.clj:250-256`), so a rerun would keep every stale DLL.
    Cleaning empties only `:out`, never `:csharp-out` (`tasks.clj:209-210`). Every run is a full recompile of the consumer.
  - `:csharp-out` puts dependencies' C# DLLs where Unity loads them. An unchanged one is not rewritten (step 0), so a run causes an import only when a dependency's C# changed.
  - `Library/` is never imported: no `.meta` files, no path to a player build, and writing there triggers no domain reload, even with an `AssetDatabase.Refresh()` behind it (measured in `integrated`).
- **The roots file**: `Library/MagicDevRoots.txt`, one absolute path per line; blank lines are skipped.
  It holds `nostrand.core/source-roots`: the load-path roots `establish-project` resolves (`nostrand-lib/nostrand/core.clj:153`), less the project root, which is there to resolve task files.
  So `CLOJURE_LOAD_PATH` entries count, deps and submodule paths follow; in smoke, `Assets/Clojure`, `csharp-lib/src` and `csharp-lib/src_classes`.
  A `nos` started by another `nos` inherits the parent's `CLOJURE_LOAD_PATH`, which `update-load-path` exports (`nostrand-lib/nostrand/core.clj:75`); step 3's test starts its child without it.
  The boot step reads it from the project root, Unity's working directory (`Magic.Unity.Clojure.RootsFile`).
  It sits beside `Library/MagicDev`, not inside it, since `:clean? true` empties that directory.
  It comes from `nos` because resolving deps can clone and expand submodules, which must stay off Unity's main thread.
  Per machine, never committed, never lists `Assets/Plugins/Magic`, or the mtime rule could load the direct-linked production DLLs.
- **Loading.** No consumer init type is preloaded, so a namespace's first load falls to FileSystem.
  There the mtime rule (`magic-compiler/src/stdlib/clojure/core.clj:5961-5963`) loads the dev DLL, or compiles the source in memory when it is newer.
  A dev DLL binds to the `Clojure`, `Magic.Runtime` and C# dependencies Unity has already loaded (step 0).
- **Reruns.** A rerun of `nos` takes effect only at the next domain reload, so the Editor action that reruns it ends with `EditorUtility.RequestScriptReload`.
  Deleting dev DLLs a domain has loaded is harmless on macOS: the domain keeps running them, and the next one loads the new files (§5.1, step 3).
- **The Editor action, decided at step 3: `MAGIC/Build Dev Set`** (`Magic.Unity.DevSet`, `magic-unity/Editor/DevSet.cs`), compiled only in the MAGIC state.
  Unity's bundled Mono (`MonoBleedingEdge/bin/mono` under `EditorApplication.applicationContentsPath`) runs `NostrandMain.exe dev` in the project root, off the main thread.
  On exit 0 it calls `AssetDatabase.Refresh()`, for a dependency's C# DLL `nos dev` copied, then `RequestScriptReload`; otherwise it logs `nos`'s output and reloads nothing.
  The exe is an `EditorPrefs` path set on `Project Settings > MAGIC`, per machine, else the `NostrandMain.exe` beside the target of `~/.local/bin/nos`, the link the installer writes.
  Not `nos` through PATH: an Editor started from the Dock gets launchd's PATH, which has neither `nos` (`~/.local/bin`) nor a Homebrew `mono`, and the `nos` script calls `mono` from PATH.
  A domain reload while `nos` runs drops the action's handle: `nos` finishes, but nothing logs or reloads.
- **First run, decided at step 2: the boot step fails, naming the cause**, rather than running `nos` itself, a multi-second subprocess on the first `GetVar`, `Require` or save.
  The exception names the roots file's path and `MAGIC/Build Dev Set`.
  The failure is retried on the next call, so writing the file is enough.
  It blocks every call, a stdlib-only `GetVar` included: `clojure.core/+` threw at step 2.
- **Lookup order, decided at step 2: FileSystem-first in the Editor**, `{FileSystem, InitType}`; players keep their order.
  Under InitType-first, `(require 'x :reload)` is a silent no-op for a namespace loaded from the dev set, since `TryLoadInitType` finds its init type, which has already run (`clojure-runtime/Clojure/CljCompiler/Compiler.cs:1821`, `:1785`).
  Under FileSystem-first an edited source is newer than its DLL, so the mtime rule compiles it (§5.1, step 2).
  A `:reload` with no edit still finds the DLL, whose init type has run, and is a no-op **[inferred]**.
  The reloader calls `Compiler.load` directly and is unaffected; a REPL user is not.
- Optional uses of the roots file: the ClojureCLR state's `CLOJURE_LOAD_PATH` during the transition **[inferred]**, and the reloader's watch roots, project-local ones only.
- **[unverified]** whether consumer Clojure needs nostrand in the Editor, through `nostrand.core/reference` or `assembly-load-from` at load time.
  Smoke's did not (step 0); `integrated` is unchecked.

### 2.3 Editor boot step

`Magic.Unity.Clojure.Boot` (`magic-unity/Runtime/Magic.Unity.cs`) boots the runtime once (`BootMagicRuntime`), then, under `#if UNITY_EDITOR && MAGIC_RUNTIME_IN_EDITOR`, the Editor part (`BootEditor`).
Unity has already loaded every `Runtime/magic/` and `Editor/Compiler/` DLL at domain load (step 0), although their metas say `isPreloaded: 0`.
Do not add a loop that loads them: an Editor probe's `Assembly.LoadFile` of `nostrand.core.clj.dll` failed with `TypeLoadException` on `Magic.Runtime`, although Unity had it loaded.
The Editor part, in order:

1. **Sets the compiler** with `RT.UseMagicCompiler()` in the fork, called by reflection.
   It runs the `magic/api` init type (loaded but not initialized), throws if it is absent,
   and sets `Compiler.Backend` (#186) to `magic.api/backend`.
   `Nostrand.Runtime.Boot` (`nostrand-lib/Runtime.cs`) calls it too, so the assignment lives in one place.
2. **Replaces `*load-paths*`** with the roots file's entries plus `Library/MagicDev`.
   It held only `Clojure.dll`'s directory (`core.clj:5895`), and MAGIC's `-load` ignores `CLOJURE_LOAD_PATH`, which only nostrand reads (`nostrand-lib/nostrand/core.clj:33`),
   so a file whose `ns` requires a not-yet-loaded namespace failed.
   Appending would not duplicate stdlib types: with `Runtime/magic/` still on the path, a stdlib `require` resolved to the copy Unity had loaded, `-load-assembly`'s `LoadFrom` of the same file returning it (§5.1, step 2).
   Replace anyway: `Runtime/magic/` holds no source, and off the path a stdlib namespace falls through to its init type.
3. **Switches the lookup order** to `{FileSystem, InitType}`, last: core's helper files and `magic.api`'s requires load before it, and `-load` reads `CodeLoadOrder` on every call (`core.clj:5943`).
   `BootMagicRuntime` keeps the reflected `CodeLoadOrder` field and `CodeSource` type in statics for it.

The roots file is checked before anything changes.
`BootMagicRuntime` runs once; the Editor part marks itself done only on success, so a fixed cause, such as a missing roots file, is picked up on the next call.

**Trigger: lazy**, on the first `GetVar`/`Require` or the reloader's first save.
Eager boot on every domain reload was rejected: every C# recompile would pay the Editor part, 0.62 s in smoke (step 2), most of it `magic/api`'s init.
The reloader calls `Boot` by reflection before each `Compiler.load`, keeping its asmdef at a single `Clojure.dll` reference and compilable outside Unity:
- it scans the loaded, non-dynamic assemblies once for `Magic.Unity.Clojure` and its static `Boot()`, caches the result, and invokes it;
- a throwing `Boot` is logged by its `InnerException`, not as `TargetInvocationException`, and the save is not loaded; the next save calls `Boot` again;
- a missing type is logged once, and saves load without a boot.

In the ClojureCLR state the call is harmless: `BootMagicRuntime` skips when the fork is absent.

### 2.4 Hot reload

`ClojureReloader` is reused unchanged (step 1).
Its only Clojure call, `Compiler.load` (`magic-unity/Editor/Reload/ClojureReloader.cs:159`), evaluates each form in memory through `magic.api/eval`, writes nothing, and picks `:cljr` in a `.cljc` file.
The compiler has run inside the Editor once: on 2026-10-29, through `in-proc`'s host in `integrated`, in Unity's Mono `6.13.0`, its `compile-file` output structurally identical to a `nos` build of the same file.
The boot call (§2.3) is in, since step 2; without it, a save before game code touches Clojure would fail without naming the cause.
What remains is to drop `!MAGIC_RUNTIME_IN_EDITOR` from `magic-unity/Editor/Reload/Magic.Unity.Editor.Reload.asmdef`.

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
  Mitigation: `MAGIC/Build Dev Set` (step 3), plus a warning, not built, when any source or those files are newer than the last run.
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

   Probes 3, 4 and 6 called a three-argument `DebouncedFileWatcher` constructor dropped in #173's review; since 2026-11-05 they run at the shipped 200 ms window, and `bb reloader-probes` exits 0 in about 2.5 min.
2. **Editor boot step** (§2.3): `RT.UseMagicCompiler()`, the Editor part of `Magic.Unity.Clojure.Boot`, and the reloader's reflective boot call.
   Probes 8 and 9 failed on the code before it; the Unity checks ran only after it. `bb test` passed after `Nostrand.Runtime.Boot` moved to `RT.UseMagicCompiler()`.
   - **Outside Unity**, 2026-11-02, `bb reloader-probes`, macOS; setup in `magic-unity/Tests~/reloader/README.md`.
     - Probe 8, on ClojureCLR, one `mono` process per build of `ReloaderBootTest.cs`:
       with a stub `Magic.Unity.Clojure`, each of two saves called `Boot` once, before `Compiler.load`, since the file read a var the stub binds;
       a throwing stub was logged by its inner exception, the save was not loaded, and the next save called `Boot` again;
       without the type, both saves loaded and it was logged once.
     - Probe 9, `UseMagicCompilerTest.cs` over a staged `Runtime/magic`: without `Editor/Compiler/` it throws naming `magic/api`; with it, it sets `Compiler.Backend` to `magic.api/backend` and `eval` compiles.
       Rerun 2026-11-05 after #186; on 2026-11-02 it checked the five hook vars #186 removed.
     - Probe 7m stayed green, booting through the new `Nostrand.Runtime.Boot`.
   - **Inside Unity**, 2026-11-02 and again 2026-11-05 after #186, `unity-examples/magic-unity-smoke`, Unity `2022.3.62f3`, macOS, MAGIC state, headless through a throwaway `-executeMethod` probe, since deleted.
     The package was this tree's, its `Runtime/magic/Clojure.dll` refreshed by the `MagicUnity` target on 2026-11-02 and #186's committed one on 2026-11-05; `Assets/Plugins/Magic` was empty, as step 4 will leave it.
     `Library/MagicDev` held step 0's `test-flags` DLLs plus a probe namespace, and the roots file listed `Assets/Clojure` and the probe's source root.
     - With no roots file, the first `GetVar` threw naming it, after booting the runtime; once the file existed, the next call ran the Editor part, and `RT.Initialize`'s `load-file` fn kept its identity, so `BootMagicRuntime` had not rerun.
     - After boot: `Compiler.Backend` was `magic.api/backend` and `eval` compiled (on 2026-11-02, the five hooks bound to `magic.api`), `*load-paths*` the two roots plus `Library/MagicDev`, the order `{FileSystem, InitType}`. The two calls took 388 ms and 617 ms, and 388 ms and 586 ms on the rerun.
     - Core's nine helper assemblies were each loaded once, from `Runtime/magic/`, and no non-dynamic assembly twice.
     - `Compiler.load` of a file requiring `smoke.csharp` and `smoke.compare`, neither loaded: `smoke.csharp` and its requires loaded from `Library/MagicDev`, `smoke.compare`, which has no dev DLL, compiled from source in memory, and the file's `Greeter` call returned.
     - After the switch, `require` of `clojure.zip` left one copy, Unity's. With `Runtime/magic/` appended to `*load-paths*`, `clojure.data` loaded through `-load-assembly` from that directory and also left one copy, Unity's (2026-11-02 only).
     - `probe.reloadme` loaded from its dev DLL; after an edit, `(require 'probe.reloadme :reload)` picked it up under FileSystem-first. With the order set back to InitType-first, a second edit and `:reload` left the first edit's value.
   - Not covered: a package from `Library/PackageCache`, and the GUI.

3. **Dev set and roots file** (§2.2): `nos dev` and `MAGIC/Build Dev Set`.
   - **Outside Unity**, 2026-11-06, `bb test magic.test.nostrand`, macOS: a child `nos dev` in a throwaway project with a `:local/root` dep.
     Both checks failed first with `No task named dev`.
     - A second run after an edit rewrote the namespace's DLL; with `:clean?` set false in the task, the DLL came out byte-identical and the check failed.
     - The roots file listed exactly the project's `src` and the dep's `lsrc`, absolute, in that order.
     The first run listed the parent `nos`'s seven load-path roots too, inherited through `CLOJURE_LOAD_PATH` (§2.2), so the test now starts the child without it.
   - **Inside Unity**, 2026-11-06, `unity-examples/magic-unity-smoke`, Unity `2022.3.62f3`, macOS, MAGIC state, headless through a throwaway `-executeMethod` probe, since deleted.
     The package was this tree's; `Assets/Plugins/Magic` was empty, and `Library/MagicDev` and the roots file were deleted first.
     `nos` was this tree's `NostrandMain.exe`, set through the `EditorPrefs` override.
     - With no roots file, `GetVar` threw naming the file and `MAGIC/Build Dev Set`.
       Unset, the default resolved through `~/.local/bin/nos` to the `magic` checkout's `NostrandMain.exe`. A missing exe logged an error naming the setting, and started nothing.
     - The menu item returned in 17 ms; `nos dev` finished 4.7 s later, and the reload request reloaded the domain, in batch mode too.
       The roots file listed smoke's three roots; `Library/MagicDev` held the 14 namespaces `smoke.runner` reaches; `Assets/Plugins/Magic` stayed empty, and `smoke_csharp.dll` unchanged.
     - `smoke.control-flow` loaded from `Library/MagicDev`. `nos dev` then rebuilt the set with it loaded: exit 0 in 3.6 s, the file replaced.
       That domain then ran the module's `suite`, its fn types first touched after the delete: 18 checks passed, still one copy of the assembly, the same MVID.
     - A second menu run reloaded the domain again; `smoke.control-flow` loaded from the new file and its 18 checks passed.
       Its MVID was unchanged, since the build is deterministic, so this shows a clean load of rebuilt files, not new content taking effect.
     - Afterwards, this tree's `nos build` regenerated `Assets/Plugins/Magic`, byte-identical to the `magic` checkout's build.
   - Not covered: the GUI, including the `Project Settings > MAGIC` field, and a domain reload while `nos dev` runs.

### 5.2 To do

Each step starts with a test that fails on the current code.

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
   - a full `production-flags` `nos build` of `integrated` under Unity's bundled Mono (smoke's dev set built under it at step 3);
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
- `nostrand/README.md`'s task list and `docs/nos-cli.md`: `nos dev`. `magic-unity/README.md`'s `Editor API`: `MAGIC/Build Dev Set` and its `NostrandMain.exe` setting.
- The local `CLAUDE.md` and `magic-unity/CLAUDE.md`: the `Editor/Reload/` layout entry and the constraint description, and their `in-proc` sections once `in-proc` is abandoned.
