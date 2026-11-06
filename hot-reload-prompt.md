# Prompt: MAGIC hot reload, step 4 (consumer DLLs player-only)

You are continuing MAGIC hot reload in the Unity Editor. Steps 0–3 are done; your job is step 4.

## Read first

`master-plan-for-hot-reload.md`, all of it, then again §2.1's last bullet, §4's immutable-package bullet and §5.2 step 4.
Claims are tagged **[inferred]** or **[unverified]** unless read in code or measured.
Line references hold as of 2026-11-06; re-find every line you cite anyway, and keep the "Line references hold" header current.
Then `magic-unity/CLAUDE.md`'s "Runtime selection", and `unity-examples/magic-unity-coexist/README.md`.

## Where to work

- `/Users/parth/gh-repos/magic-2`, branch `feat/magic-editor-hot-reload`: #186's commits (PR #207, unmerged), then `wip` commits.
  Do not rebase, squash, drop, push or commit. The plan has uncommitted edits; keep them.
- The build is current. `bb` tasks write into tracked DLL folders; `git checkout --` them before reporting.
- `bb reloader-probes` exits 0 (about 2.5 min); `bb test` passes (a single namespace still takes about 2 min, since `test/run` loads them all).
- The `nos` on PATH is the `magic` checkout's. Use `nostrand/bin/Release/net471/NostrandMain.exe` from this tree, under `mono`.

## Scope

Step 4 only: in both Editor states, a consumer's `.clj.dll` stays out of the Editor and reaches player builds (§2.1).

1. `CljPluginConstraints` (`magic-unity/Editor/CljPluginConstraints.cs`) gates consumer DLLs to players.
   The plan proposes a separate `!UNITY_EDITOR` entry that ANDs with the existing one **[inferred]**; measure it.
2. Consumer DLLs already stamped with today's constraint get the new gate too.
   Today they are skipped: `NeedsConstraint` treats any entry whose terms are all false in the ClojureCLR Editor as done, and `!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR` is such an entry.
3. The package's own DLLs stay as they are: `Runtime/magic/` (the Editor's runtime and stdlib) and `Editor/Compiler/` are `.clj.dll`s too, and in `magic-unity-smoke` they sit in a writable `file:` package.
4. Player discovery still finds the consumer DLLs: `PlayerCljAssemblies` reads `CompilationPipeline.GetAssemblies(PlayerWithoutTestAssemblies)`, which IL2CPP rewriting and `link.xml` depend on.

Immutable packages stay out of reach (§4); keep the existing warning, and say whether its wording still fits.
Not step 5: leave the reloader's asmdef alone. Keep the `bb/magic/unity.clj` constraint blocks and `CljPluginConstraints` in sync, as the comment at `CljPluginConstraints.cs:18` asks.

## Order

Each piece starts with a test that fails on the current code.

- **`bb coexist-noise`** is the in-repo regression for these constraints: a tarball install, consumer DLLs in `Assets/Plugins/Consumer/`, both states, and an upgrade path.
  Its MAGIC-state expectations (`bb/magic/coexist.clj:63-71`) count the consumer DLLs in `:preloaded-clj` and `:editor-clj-refs`; step 4 makes them package-only, with `:player-clj-refs` unchanged.
  Change the expectations first and see `bb coexist-noise magic` fail.
  Add the upgrade case: a consumer DLL whose meta carries today's constraint gains the new gate on import of the new package.
- **`magic-unity-smoke`**, Unity `2022.3.62f3`, headless through `-executeMethod` with a throwaway probe, as steps 2 and 3 did (§5.1).
  Fill `Assets/Plugins/Magic` with this tree's `nos build` first: step 4 needs consumer DLLs to gate.
  In the MAGIC state, check: no consumer assembly is in the domain at load; `Boot` and `Require` of a smoke namespace load it from `Library/MagicDev` (`MAGIC/Build Dev Set` or this tree's `nos dev` writes it); the player references still list every consumer DLL.
  Delete the probe and its output; leave `Assets/Plugins/Magic` as `nos build` wrote it.
  Do not guess at a result you could not observe.

## Pitfalls from step 3

- A headless probe that needs a domain reload runs without `-quit`, keeps its phase in `SessionState`, and ends with `EditorApplication.Exit`.
- `EditorPrefs` is per machine, shared with every project: restore any key a probe sets.
- Nothing headless reaches the GUI; say so rather than infer it.

## Decisions to bring back, not make

- How a stamped consumer DLL is told from the package's own: by path, by `PackageInfo`, or by the constraint it carries; and whether the new gate is a separate entry or replaces the old one.
- What the MAGIC Editor does for a consumer that has not run `nos dev`: step 2's boot failure already names the menu, so possibly nothing; say what a consumer upgrading the package sees.

Propose each with evidence; stop and ask before building on a choice.

## Rules and report

- Run tests with `bb test`, `bb reloader-probes` and `bb coexist-noise`, not the REPL. `bb check-drift` must still pass.
- Record outcomes in the plan, in its own style: move step 4 to §5.1 with what was observed, where and when, and replace the tags it settles.
  §7's runtime-selection table entry is now due; list the doc changes, do not make them.
- Report back:
  - per check, pass or fail with the evidence;
  - the proposed decisions and why;
  - what changed in the plan;
  - anything the plan got wrong.
