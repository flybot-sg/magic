# magic-unity-smoke

Runtime regression suite for MAGIC's IL2CPP output. Catches bugs that only reproduce under AOT codegen, generic sharing, or managed stripping, things the editor's Mono runtime never touches.

## Requirements

- Unity `2022.3.62f3` (the version this project was authored against and is verified on; see `ProjectSettings/ProjectVersion.txt`). Other Unity versions are untested.
- `nos` (Nostrand) on your PATH. The monorepo root README has the setup.

## Run

```bash
cd magic-unity-smoke
nos dotnet/build
```

That reads `project.edn` + `dotnet.clj`, wipes `Assets/Plugins/Magic/`, and recompiles `smoke.runner` plus its transitive deps into that directory using the production compiler flags (`*direct-linking*`, `*strongly-typed-invokes*`).

Then in Unity:

1. Open this folder in Unity Hub. `SmokeBootstrap` runs on editor load and forces `ScriptingBackend.Standalone = IL2CPP` and `ManagedStrippingLevel.Standalone = Disabled`, so no manual Build Settings tweaking is needed.
2. Open `Assets/Smoke.unity` (the scene has one GameObject with `SmokeTestRunner.cs` attached).
3. Use **MAGIC → Smoke → Build & Run IL2CPP** to build and launch the player. The built player shows a green PASS / red FAIL panel and writes the same report to `Player.log`.

To re-run after a Clojure edit: rerun `nos dotnet/build`, then the menu item again.

To run the same suites under Mono (no Unity round-trip): `nos dotnet/run-tests` from this directory. Catches regressions that surface independent of IL2CPP and exits non-zero on any failure.

## Suites

One namespace per edge-case family. Each exports `(suite)` returning a vector of `{:name :pass? :detail}` maps. `smoke.runner` aggregates them.

| Namespace | Checks | Notes |
|-----------|-------:|-------|
| `smoke.value-types`  | 9  | Zero-arity instance members on `Int64` / `Double` / `String`. Regression set for the constrained.callvirt fix. |
| `smoke.letfn-cases`  | 3  | Mutually-recursive `letfn` and a closure case. Regression set for letfn closed-over field init. |
| `smoke.polymorphism` | 8  | Protocols on `defrecord`/`deftype`, reify-against-protocol, reify and proxy against `System.Object`, multimethods. |
| `smoke.control-flow` | 10 | `loop`/`recur`, `try`/`catch`/`finally` (incl. nested try-finally with side-effecting finally + deref-in-catch), `lazy-seq`, basic numerics. |

30 checks total. All green under Mono and Standalone Mac IL2CPP.

## Adding a new edge case

1. Add the minimal repro to the matching `smoke/*.clj` (or a new namespace, then `:require` it from `smoke.runner`).
2. Express it as `(check "name" #(...) expected-value)`. The harness wraps each thunk in try/catch and pretty-prints failures.
3. `nos dotnet/build`, then **MAGIC → Smoke → Build & Run IL2CPP**. Confirm green.
4. Commit the smoke case alongside the fix.

Rules:

- **Keep cases tiny.** One failing line points at one root cause.
- **No Unity-specific types.** The suites use BCL types only so the same code runs under `nos dotnet/run-tests` for Mono comparison. Unity-specific cases (e.g. `Vector3` interop) belong in a separate namespace.
