# Prompt: MAGIC hot reload, step 2 (Editor boot step)

You are continuing implementation work on MAGIC hot reload in the Unity Editor.
Steps 0 and 1 are done; your job is step 2, not the design.

## Read first

`/Users/parth/gh-repos/magic-2/master-plan-for-hot-reload.md`, all of it, then again §2.2, §2.3 and §5.2 step 2.
Every claim there is tagged **[inferred]** or **[unverified]** unless it was read in code or measured.
Step 2 settles the stdlib double-load tag in §2.3 item 2, and fixes the roots file's format.

Also read `magic-unity/Tests~/reloader/README.md`, "The settle window" and "Probe 7m".
They describe the harness you extend and how Probe 7m stages a MAGIC runtime.

## Where to work

- Work in `/Users/parth/gh-repos/magic-2`.
  It is on local `develop` with four `wip` commits ahead of `origin/develop`; the plan's simplification is staged on top.
  Leave all of that alone: do not rebase, squash, drop or push.
- The build is current (`bb build-runtime` ran after the last C# change).
  `bb` tasks write into tracked DLL folders; `git checkout --` them before committing anything.
- `bb reloader-probes` exits 1 today: Probes 3, 4 and 6 do not compile (README, "The settle window").
  Judge your checks by their own probe's output. Do not fix 3, 4 and 6 unless the user asks.

## Implementation order

Each piece starts with a test that fails on the current code.

1. **Harness tests first** (step 2's "In the harness" list), in `magic-unity/Tests~/reloader/`, run through `bb reloader-probes`.
   The stub `Magic.Unity.Clojure` is compiled into the harness; `ClojureReloader` finds it by scanning the loaded assemblies (§2.3).
   The `RT.UseMagicCompiler()` check needs a staged runtime without `Editor/Compiler/`, in its own `mono` process.
2. **`RT.UseMagicCompiler()`** in `clojure-runtime` (§2.3 item 1), and `nostrand-lib/Runtime.cs`'s `Boot` calling it instead of binding the five vars itself.
   Rebuild with `bb build-runtime`. Probe 7m must stay green, since it boots through `Nostrand.Runtime.Boot`.
3. **The reloader's reflective boot call** in `magic-unity/Editor/Reload/ClojureReloader.cs` (§2.3, "Trigger").
   Its asmdef constraint stays; dropping it is step 5.
4. **The Editor branch of `Magic.Unity.Clojure.Boot`** in `magic-unity/Runtime/Magic.Unity.cs` (§2.3): bind the compiler, replace `*load-paths*`, switch the lookup order last, and split `_booted`.
   Fork-only members by reflection, as `BootMagicRuntime` does.

## Then the in-Unity checks

Step 2's "Inside Unity" list, in `unity-examples/magic-unity-smoke`, Unity `2022.3.62f3` (`/Applications/Unity/Hub/Editor/2022.3.62f3`).

- **Unity loads the package's `Runtime/magic/Clojure.dll`, which `bb build-runtime` does not update.**
  To get `RT.UseMagicCompiler()` there: `bb check-drift` first (it restores that DLL from HEAD), then `dotnet build Magic.csproj -t:MagicUnity` (root `CLAUDE.md`, "Paired bootstrap refresh").
  The other order ships the old DLL. Do not commit the refreshed DLLs without asking.
- **Smoke's state:**
  - The `wip` commit `187f7f85` sets `MAGIC_RUNTIME_IN_EDITOR` in `ProjectSettings.asset`, so the project already opens in the MAGIC state.
  - `Assets/Plugins/Magic` again holds the 14 production-built consumer DLLs. In the MAGIC state Unity preloads them, and under InitType their init types shadow the dev set.
    Delete the `smoke*.dll` files and their `.meta`s before the checks, as step 0 did. The folder is gitignored, and `nos build` in the smoke directory regenerates it.
  - `Library/MagicDev` still holds step 0's `test-flags` build of `smoke.csharp`, `smoke.check` and `smoke-csharp.load-dll`.
    To rebuild it, run the repo's `nostrand/bin/Release/net471/NostrandMain.exe` from the smoke directory:
    `-e "(require '[nostrand.tasks :as t]) (t/compile-project :namespaces '[smoke.csharp] :flags t/test-flags :out \"Library/MagicDev\" :csharp-out \"Assets/Plugins/CSharp\" :clean? true)"`.
- **The roots file:** the plan fixes its format (one absolute path per line) but not its location.
  Pick one under `Library/`, write it by hand listing smoke's `Assets/Clojure`, and record the location in §2.2.
- **Decisions:** the plan proposes FileSystem-first and a first-run failure that names the cause.
  Test the proposals. If the evidence argues for the alternative, stop and report rather than choosing it yourself.
- Step 0 ran headless and was quickest: write the define into `ProjectSettings.asset`, then run `Unity -batchmode -quit -nographics -projectPath <smoke> -logFile <log> -executeMethod <Class.Method>` with a throwaway Editor script that writes its results to a file under `Library/`.
  A GUI menu item on the same script lets the user repeat it by hand.
  `[InitializeOnLoad]` code that calls `PackageManager.PackageInfo.FindForAssetPath` can run before the package database is ready on a domain reload; do not rely on it there.
  Do not guess at a result you could not observe.
- Afterwards, delete the throwaway probe and its output, and regenerate smoke's `Assets/Plugins/Magic` with `nos build`.

## Rules

- Run tests with the project's commands (`bb test`, `bb reloader-probes`), not the REPL.
- C# runtime edits need `bb build-runtime`; run `bb test` after `RT.UseMagicCompiler()` lands, since `nos` boots through it.
- Do not commit without asking.
- Record each outcome in the plan, in its own style:
  - move step 2 to §5.1 with what was observed, where and when;
  - replace the tags it settles;
  - fix the roots file's format and location in §2.2;
  - state the two decisions.
- Report back:
  - per check, pass or fail with the evidence;
  - the decisions and why;
  - what changed in the plan;
  - anything the plan got wrong.
