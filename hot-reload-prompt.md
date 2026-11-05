# Prompt: MAGIC hot reload, step 3 (dev set and roots file)

You are continuing MAGIC hot reload in the Unity Editor. Steps 0–2 are done; your job is step 3.

## Read first

`master-plan-for-hot-reload.md`, all of it, then again §2.2 and §5.2 step 3.
Claims are tagged **[inferred]** or **[unverified]** unless read in code or measured.
Its line references predate #186: `core.clj` lines are off by about 45 (the mtime rule is now at `core.clj:5962`, not 6005–6007).
Re-find every line you cite, and fix the plan's stale references and its "Line references hold" header as you go.

## Where to work

- `/Users/parth/gh-repos/magic-2`, branch `feat/magic-editor-hot-reload`: #186's commits (PR #207, unmerged), then `wip` commits.
  Do not rebase, squash, drop, push or commit.
- The build is current. `bb` tasks write into tracked DLL folders; `git checkout --` them before reporting.
- `bb reloader-probes` exits 0 (about 2.5 min); `bb test` passes.

## Scope

Step 3 only:

1. A `nos` entry point that writes `Library/MagicDev` and `Library/MagicDevRoots.txt` together (§2.2).
   It wraps `nostrand.tasks/compile-project`; the roots are the source roots `establish-project` resolves.
2. The Editor action that runs it and ends with `EditorUtility.RequestScriptReload`.
3. Whether deleting dev DLLs a domain has loaded is harmless.

Not steps 4–5: leave `CljPluginConstraints` and the reloader's asmdef alone.

## Order

Each piece starts with a test that fails on the current code.

- **nos side:** tests in `magic-compiler/test/magic/test/nostrand.clj`, run with `bb test magic.test.nostrand`:
  a second run after editing a namespace rewrites its DLL;
  the roots file lists exactly the resolved source roots, one absolute path per line.
- **Editor side:** in `unity-examples/magic-unity-smoke`, Unity `2022.3.62f3`, headless through `-executeMethod` with a throwaway probe, as step 2 did (§5.1).
  Empty `Assets/Plugins/Magic` before the checks, regenerate it with `nos build` after, and delete the probe and its output.
  Do not guess at a result you could not observe.

## Decisions to bring back, not make

- Task or `magic.edn` key (§2.2 allows either), and its name.
- How the Editor action finds and launches nos: which `NostrandMain.exe`, through Unity's bundled Mono (§2.1) or the system one, and where that path is configured.

Propose each with evidence; stop and ask before building on a choice.

## Rules and report

- Run tests with `bb test` and `bb reloader-probes`, not the REPL.
- Record outcomes in the plan, in its own style: move step 3 to §5.1 with what was observed, where and when, and replace the tags it settles.
- Report back:
  - per check, pass or fail with the evidence;
  - the proposed decisions and why;
  - what changed in the plan;
  - anything the plan got wrong.
