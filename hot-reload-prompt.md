# Prompt: MAGIC hot reload, step 5 (drop the reloader's constraint)

You are continuing MAGIC hot reload in the Unity Editor. Steps 0–4 are done; your job is step 5.

## Read first

`master-plan-for-hot-reload.md`, all of it, then again §2.4, §2.2's "Optional uses of the roots file", §3 and §5.2 step 5.
Claims are tagged **[inferred]** or **[unverified]** unless read in code or measured.
Line references held as of 2026-11-06, before the 2026-11-13 rebase onto `fix/magic-unity-boot` (#208, #211, #213); re-find every line you cite, and keep the "Line references hold" header current.
Then `magic-unity/README.md`'s "Editor API", "What a reload changes" and "Save order", `magic-unity/Tests~/reloader/README.md`, and `unity-examples/magic-unity-coexist/README.md`.

## Where to work

- `/Users/parth/gh-repos/magic-2`, branch `feat/magic-editor-hot-reload`: `fix/magic-unity-boot` (#208, #211, #213), then `wip` commits.
  Do not rebase, squash, drop, push or commit.
- The build is current. `bb` tasks write into tracked DLL folders; `git checkout --` them before reporting.
- `bb reloader-probes` exits 0 (about 2.5 min); `bb test` passes; a full `bb coexist-noise` is eleven Unity launches.
- Use `nostrand/bin/Release/net471/NostrandMain.exe` from this tree, under `mono`, not the `nos` on PATH.
- `integrated` is at `~/git-repos/integrated`. Its package is `file:/Users/parth/gh-repos/magic-2/magic-unity`, this tree's.
  Do not commit there, and do not read its git history. Its unstaged changes are the user's and will be committed: the manifest's `file:` package, `MAGIC_RUNTIME_IN_EDITOR` on Standalone, `CljRuntime.cs`. Leave them; list any change you make there.

## Scope

Step 5 only: the reloader runs in the MAGIC Editor (§2.4).

1. Drop `!MAGIC_RUNTIME_IN_EDITOR` from `magic-unity/Editor/Reload/Magic.Unity.Editor.Reload.asmdef`, so the assembly compiles in both Editor states.
   Its only precompiled reference, `Clojure.dll`, should resolve to whichever runtime the state loads (`magic-unity/CLAUDE.md`, Layout) **[inferred]** for the MAGIC state; measure it.
2. The reloader works there, per §5.2 step 5: a save before any `Var` or `Require` boots the runtime and reloads; a reloaded `defn` and `defmethod` reach a caller in another namespace; play mode with `Reload Domain` on and off.
3. Not step 6 or 7: leave `Editor/Compiler/` and the build trigger alone. Do not edit `magic-unity/README.md`'s guard or tables; list them (§7).

## Read in `integrated` on 2026-11-10

- Unity `2022.3.62f3`; Standalone sets `MAGIC_RUNTIME_IN_EDITOR` (unstaged).
- No `Library/MagicDev`, no roots file: the first `nos dev` of `integrated` is still to run, and its time is wanted (§5.2 step 8). Record it.
- `Assets/Plugins/Magic` holds 230 Clojure DLLs (109 `.clj.dll`, 120 `.cljc.dll`, 1 `.cljr.dll`), gitignored, every meta still carrying the legacy entry.
  Step 4's `Reconcile` should restamp them on the first launch with this package; record what it logs. The plan's §4 says 109; correct it.
- The reloader wiring is consumer code, not the package's:
  - `Assets/AppIntegrated/CljSystem.Reload.cs`, under `#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR`, builds a `ClojureReloader` over `CljSourceRoots.Installed`.
  - `CljSourceRoots.Install` (`Packages/clojure-tool/Runtime/CljSourceRoots.cs`, a submodule) returns nothing under `!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR`, so in the MAGIC state the reloader would watch no root. Its roots come from `clojure-libs/reload.edn`, 29 entries.
  - `Poll` runs only from `CljSystem.FrameTick`, in play mode, after `CljSystem.InitSystem` has booted the runtime and required namespaces.
    So the plan's "save before any `Var` or `Require`" cannot happen through `integrated`'s wiring; check it in smoke.

## Order

Each piece starts with a test that fails on the current code.

- **`bb coexist-noise`**: add a `CoexistenceProbe` field for whether `Magic.Unity.Editor.Reload` is in the domain, expected in both states, and see `bb coexist-noise magic` fail first.
- **`bb reloader-probes`** stays green: Probe 7m is the reloader on MAGIC outside Unity (§5.1, step 1).
- **`magic-unity-smoke`**, MAGIC state, headless through `-executeMethod` with a throwaway probe, as steps 2–4 did.
  Rebuild `Library/MagicDev` with this tree's `nos dev` first.
  - Build a `ClojureReloader` over the roots file's entries before any `Var` or `Require`, edit a smoke source, and `Poll` until it settles: `Boot` runs, then the edit takes.
  - A `defn` called from another namespace, and a `defmethod` (`smoke.polymorphism` has multimethods): after the reload, the caller sees the new body.
  - Play mode, if a headless run can enter it; if not, say so and leave it to the by-hand list.
  - `git checkout --` every smoke source you edit; delete the probe and its output.
- **`integrated`**, after you change its wiring (see decisions): run `nos dev` and one headless launch that compiles with no `error CS`. The rest is a by-hand checklist for the user in the GUI, from §5.2 step 5.

## Pitfalls from steps 3 and 4

- A headless probe that needs a domain reload or a polling loop runs without `-quit`, keeps its phase in `SessionState`, ticks from `EditorApplication.update`, and ends with `EditorApplication.Exit`.
- Whether `FileSystemWatcher` fires in batch mode is **[unverified]**; the reloader's watcher is a background thread with a 200 ms settle.
- `EditorPrefs` is per machine, shared with every project: restore any key a probe sets.
- Since the rebase the Editor boot runs in `Magic.Unity.Clojure`'s static constructor (§2.3): with no roots file, the first use throws `TypeInitializationException`, and so does every later one until a domain reload.
  In `integrated`, `CljRuntime.Init()` (`ResourceIndex` and the edit-mode tests) calls `Clj.Boot()`, so run its first `nos dev` before its first MAGIC-state launch.
- Unity reports `Assets/` references project-relative, other paths absolute.
- Every coexist run rewrites `unity-examples/magic-unity-coexist/ProjectSettings/ProjectSettings.asset` with default icons; `git checkout --` it.
- One Unity at a time; `bb coexist-noise` wants none open.
- Nothing headless reaches the GUI; say so rather than infer it. Do not guess at a result you could not observe.

## Decisions to bring back, not make

- Where `integrated`'s reloader gets its watch roots in the MAGIC state: `Library/MagicDevRoots.txt` (project-local roots only, §2.2), `reload.edn`, or `CljSourceRoots` changed to cover both states; and whether the package should expose the roots file's entries rather than each consumer reading it.
- The consumer guard once the asmdef has no constraint: `#if UNITY_EDITOR` alone, and what the README tells a consumer still on the ClojureCLR state.

Propose each with evidence; stop and ask before building on a choice.

## Rules and report

- Run tests with `bb test`, `bb reloader-probes` and `bb coexist-noise`, not the REPL. `bb check-drift` must still pass.
- Record outcomes in the plan, in its own style: move step 5 to §5.1 with what was observed, where and when, and replace the tags it settles. List §7's doc changes, do not make them.
- Report back:
  - per check, pass or fail with the evidence;
  - the proposed decisions and why;
  - the by-hand checklist for `integrated`;
  - what changed in the plan, and in `integrated`;
  - anything the plan got wrong.
