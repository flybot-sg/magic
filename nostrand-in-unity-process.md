# In-process nostrand in the Unity Editor

**Implemented.** This file is the record of why, and of what is still unproven. The design
detail that used to be here now lives with the code: `magic-unity/CLAUDE.md` owns the host's
mechanics, `docs/unity-integration.md` the consumer-facing wiring, and each of the four
warm-domain hazards below carries its own comment at the site that handles it.

## Context

A Clojure change for a Unity project was compiled by shelling out to `nos`, which starts a
fresh Mono process, boots the MAGIC runtime, loads nostrand's own Clojure,
resolves the load path, compiles the game namespaces into `Assets/Plugins/Magic`, and exits.
The goal was to do that inside the Editor's own Mono process, so compiling becomes an Editor
action with nostrand's tooling available in-process.

Measured with a `Stopwatch` inside `nos version` in a deps-free directory, three runs, wall
clock 3.75 to 3.86 s: Mono startup to `Main` 70 to 106 ms, `LoadFile` of the shipped `.clj.dll`s
20 to 39 ms, `RT.Initialize` 316 to 373 ms, `magic/api` init 464 to 483 ms, then **loading
`nostrand/core` 1.49 to 1.57 s and `nostrand/tasks` 1.26 to 1.32 s**. So the MAGIC compiler
booted in roughly 0.85 s and nostrand's own Clojure, then compiled into memory at every
startup, was the remaining ~2.8 s. That measurement predates #183, which committed nostrand's
namespaces as `.clj.dll`: those two loads are now DLL loads, so the boot a fresh `nos` pays is
close to the 0.85 s, and the per-build saving of running in-process is that much rather than
3.7 s. It is still paid once per domain reload instead of once per build, which is the reason
boot is lazy rather than `[InitializeOnLoad]`, and the larger point stands: nostrand's tooling
is Clojure, not C#, so getting `compile-project` into the Editor meant shipping that Clojure
and the thin C# host it imports, not reimplementing either.

## What landed

| | |
|---|---|
| Packaging | `magic-unity/Editor/Compiler/` holds the 36 compiler `.clj.dll`s, seven of nostrand's eight (`bb refresh-nostrand` deploys them; `nostrand.repl` imports Mono.Terminal, which Unity cannot resolve) and `Nostrand.dll`, editor-only; `Runtime/magic/` is the 37 stdlib DLLs plus the two C# runtimes. `check-drift` asserts the partition (37 + 43 = 80, the 81 less `nostrand.repl`) |
| Library | `nostrand-lib/` builds `Nostrand.dll`, byte-stable across checkouts. `Runtime` is the host-neutral engine: `Boot`, `LoadNostrand`, `EstablishProject`, `PushTaskBindings`, `Run`. `nostrand/Program.cs` is the CLI shell |
| Editor host | `magic-unity/Editor/NostrandHost/`, two Editor-only asmdefs under `MAGIC_RUNTIME_IN_EDITOR`. `NostrandHost` is Unity-free and takes every path as an argument; `Unity/NostrandEditor` adds package-path resolution, `Debug.Log`, and the reload lock and asset batch, unwound in `finally`. Nothing in the package constructs one and there is no `[InitializeOnLoad]` |
| Coexist | `CoexistenceProbe` reports `compiler-in-domain`, `compiler-player-refs` and `compiler-boots`; `bb coexist-noise` asserts them per state |
| Probes | `magic-unity/Tests~/nostrand/` + `bb nostrand-probes`: csc and mono driving the real `NostrandHost.cs` against the shipped DLL set, no Unity |

### The four warm-domain hazards

A fresh `nos` process cannot hit any of these, and each fails silently rather than throwing.
Named here so the list is findable; the reasoning is at each site.

1. `set-load-path`, not `*load-paths*` directly, or **zero** C# assemblies are copied and the
   build still reports success.
2. No `RT.PostBootstrapInit()`, which reaches `Compiler.load` and `NotSupportedException`.
3. A fresh `(ref (sorted-set))` for `*loaded-libs*` per call, or a dependency edited since the
   last compile is never re-read and its DLL never rewritten.
4. A thread-binding frame that always pops, and `*out*` bound alongside `Console.Out` or a
   task's output never reaches the logger.

3 has a measured negative control in `magic-unity/Tests~/nostrand/README.md`. A fifth hazard,
`*load-paths*` growing on every call because `update-load-path` concatenated, was fixed at its
root in `nostrand.core` (#182) rather than worked around in the host; Probe 6 pins it.

## Known limitations to document, not solve

- In-process compilation requires `MAGIC_RUNTIME_IN_EDITOR`, and the ClojureCLR hot reloader is
  constrained out of that state, so the two Editor workflows are mutually exclusive. Making
  reload work in the MAGIC state is separate work, scoped in `magic-hot-reload.md` at the repo
  root. Switching runtime then continuing without restarting Unity also leaves
  `CLOJURE_LOAD_PATH` holding whatever the other side wrote.
- **cwd is load-bearing far past config discovery**: `magic.emission/fresh-module` calls
  `DefineDynamicAssembly` with no directory, so Mono captures the cwd as the save directory,
  and `EmitAssembly`/`File/Move` use bare file names (`api.clj:235,238`). Unity's cwd is the
  project root, and writable, in both the GUI and batchmode, so the default is right. The host
  asserts on every call because cwd is a mutable process-global that a task file or another
  Editor tool can move, and it never calls `Directory.SetCurrentDirectory` implicitly.
- After play mode has loaded `Assets/Plugins/Magic/*.clj.dll`, a rebuild overwrites assemblies
  the domain holds. macOS lets the delete and the rewrite through, and the domain keeps the
  old bodies until the reload.
- Even on success the new code is live only via the in-memory module the compile built; the
  DLLs on disk matter after the domain reload that `AssetDatabase.Refresh()` triggers. That
  liveness is also what makes the *next* compile stale, hazard 3 above.
- **What an in-Editor compile leaves live in the domain is direct-linked**, because
  `compile-project` runs `production-flags`: redefining a fn updates its var but not the call
  sites inside its own namespace. Nothing here needs otherwise, since this compiles rather
  than iterates. It is the constraint a reloader or prepl has to design around, measured in
  `magic-hot-reload.md`.
- `magic.api/eval` mints a dynamic assembly per call (`api.clj:271`), measured at 90 to 140
  loaded assemblies over 50 evals, none of them unloadable. Irrelevant to a build and bounded by
  the next domain reload; it becomes load-bearing once something evaluates per save or per
  keystroke, see `magic-hot-reload.md`.
- `magic.api/compile-file` skips an existing DLL (`api.clj:233`), so without `:clean? true` an
  in-Editor rebuild is a silent no-op that looks successful.
- A task file may call `Environment/Exit`, normal for a CLI and fatal in the Editor. The repo's
  own fixture does (`magic-unity-smoke/dotnet.clj:26`). The host cannot prevent it.
- Unity's UPM extractor writes an AppleDouble sidecar beside every file it unpacks on macOS,
  including a `._<name>.clj.dll` for each shipped DLL. `Boot` skips them by name. It cannot be
  fixed at packing time: the tarball carries none and a plain `tar xzf` produces none, but
  `Library/PackageCache` is Unity's and is rebuilt on every resolve.

## Verification

Green:

- `bb nostrand-probes`, Probes 1-8.
- `bb clean && bb build && bb check-drift && bb test`, the documented pre-PR gate.
- `bb coexist-noise`, 5/5. `compiler-in-domain` 43 / 0, `compiler-player-refs` 0 in both states,
  `compiler-boots` true / absent. This is the only evidence so far that the host boots inside
  Unity at all.

Outstanding, all of it manual Unity work:

- Player build of `unity-examples/magic-unity-smoke` on Unity `2022.3.62f3`: compiler absent
  from the build, IL2CPP still succeeds.
- By hand in the consumer: set `MAGIC_RUNTIME_IN_EDITOR`, invoke the host, confirm
  `Assets/Plugins/Magic` repopulates, the C# assemblies are copied, and the result loads.
  `coexist-noise` proves the host boots; nothing yet proves a compile writes correct output
  from inside the Editor, which is what hazard 1 is about.
- By hand, in one domain: invoke the host, edit a namespace that another one requires, invoke it
  again **without letting the domain reload in between**, and confirm the second build picks the
  edit up. The probe pack covers this outside Unity; this is the in-Editor half.
