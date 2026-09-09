# magic-unity-smoke

Runtime regression suite for MAGIC's IL2CPP output. Catches bugs that only reproduce under AOT codegen, generic sharing, or managed stripping, things the editor's Mono runtime never touches.

## Requirements

- Unity `2022.3.62f3` (the version this project was authored against and is verified on; see `ProjectSettings/ProjectVersion.txt`). Other Unity versions are untested.
- `nos` (Nostrand) on your PATH. The monorepo root README has the setup.

## Run

```bash
cd unity-examples/magic-unity-smoke
nos build
```

That reads `deps.edn` + `magic.edn`, wipes `Assets/Plugins/Magic/`, and recompiles `smoke.runner` plus its transitive deps into that directory using the production compiler flags (`*direct-linking*`, `*strongly-typed-invokes*`). The C# assembly that `csharp-lib` ships lands in `Assets/Plugins/CSharp/`; the wipe covers `Assets/Plugins/Magic/` only.

Then in Unity:

1. Open this folder in Unity Hub. `SmokeBootstrap` runs on editor load and forces `ScriptingBackend.Standalone = IL2CPP` and `ManagedStrippingLevel.Standalone = Disabled`, so no manual Build Settings tweaking is needed.
2. Open `Assets/Smoke.unity` (the scene has one GameObject with `SmokeTestRunner.cs` attached).
3. Use **MAGIC → Smoke → Build & Run IL2CPP** to build and launch the player. The built player shows a green PASS / red FAIL panel and writes the same report to `Player.log`.

To re-run after a Clojure edit: rerun `nos build`, then the menu item again.

To run the same suites under Mono (no Unity round-trip): `nos dotnet/run-tests` from this directory. Catches regressions that surface independent of IL2CPP and exits non-zero on any failure.

## Suites

One namespace per edge-case family. Each exports `(suite)` returning a vector of `{:name :pass? :detail}` maps. `smoke.runner` aggregates them.

| Namespace | Checks | Notes |
|-----------|-------:|-------|
| `smoke.value-types`  | 9  | Zero-arity instance members on `Int64` / `Double` / `String`. Regression set for the constrained.callvirt fix. |
| `smoke.letfn-cases`  | 3  | Mutually-recursive `letfn` and a closure case. Regression set for letfn closed-over field init. |
| `smoke.polymorphism` | 20 | Protocols on `defrecord`/`deftype`, the auto-generated record collection surface (equiv/count/conj/assoc, incl. a zero-field record), reify-against-protocol, reify and proxy against `System.Object`, `proxy-super` (incl. the shadowed type-hinted `this` idiom), protocol-hinted parameters, multimethods. |
| `smoke.control-flow` | 18 | `loop`/`recur`, `try`/`catch`/`finally` (incl. nested try-finally with side-effecting finally + deref-in-catch), `lazy-seq`, branches that never return, `#inst` / `#uuid` constants, named fn self-reference, narrow numeric casts and integer promotion. |
| `smoke.read-print`   | 12 | Floating point through the reader and the printer: out-of-range literals reading as infinity, and doubles and floats surviving `pr-str` then `read-string`. IL2CPP supplies its own `Double.ToString` and exception handling, so Mono does not cover this. |
| `smoke.stdlib-1-10`  | 11 | Clojure 1.10 stdlib surface: `symbol`, `read+string`, `PrintWriter-on`, `tap>`, `Throwable->map`, ex-triage, extend-via-metadata. |
| `smoke.interop`      | 2  | `by-ref` on a type-hinted local. Written as `.cljr`, so the source-extension handling is exercised too. |
| `smoke.intrinsics`   | 5  | Intrinsic lowering, and the fallback when an intrinsic declines. |
| `smoke.compare`      | 10 | `compare` and `sort` ordering by UTF-16 code unit rather than OS collation. |
| `smoke.csharp`       | 3  | Calls into a C# assembly a library ships (see below). |

93 checks total. All green under Mono and Standalone Mac IL2CPP.

## The C# assembly example

`csharp-lib/` is a library in the shape [docs/native-assemblies.md](../../docs/native-assemblies.md) describes, consumed here as a `:local/root` dep:

```
csharp-lib/
  deps-clr.edn                   {:paths ["src" "src_classes"]}
  Greeter.cs                     the source
  src/smoke_csharp/load_dll.cljr the loader, scanning CLOJURE_LOAD_PATH
  src_classes/smoke_csharp.dll   the assembly, committed
```

Rebuild the assembly with the command it was built with, from `csharp-lib/`:

```bash
csc -nologo -deterministic -optimize+ -target:library \
    -out:src_classes/smoke_csharp.dll Greeter.cs
```

Use that command as written. The flags and the compiler version are both part of the output bytes ([why](../../docs/deterministic-compilation.md#committing-an-assembly-you-compiled-yourself)); these come from Roslyn 3.9. The repo commits the result in three places, here plus `Assets/Plugins/CSharp/` and `magic-unity-coexist/Assets/Plugins/Consumer/`, so rebuild it and update all three; `bb check-drift` fails if they diverge.

`smoke.csharp` requires the loader and imports `[smoke_csharp Greeter]`, so `nos build` resolves the types through the loader at compile time, then copies `smoke_csharp.dll` into `:csharp-out`. Unity imports it as a plain managed plugin: no define constraint applies, so both Editor runtimes load it and a player build carries it.

`Assets/Plugins/CSharp/` is committed here, `.meta` files included, so a clone has the assembly before anyone runs `nos build`. That part is a choice ([the two plugin folders](../../docs/unity-integration.md#the-two-plugin-folders)); the folder split is not, because `:clean?` deletes `Assets/Plugins/Magic/` on every build and a plugin living there would be reimported under a new GUID each time.

## Adding a new edge case

1. Add the minimal repro to the matching `smoke/*.clj` (or a new namespace, then `:require` it from `smoke.runner`).
2. Express it as `(check "name" #(...) expected-value)`. The harness wraps each thunk in try/catch and pretty-prints failures.
3. `nos build`, then **MAGIC → Smoke → Build & Run IL2CPP**. Confirm green.
4. Commit the smoke case alongside the fix.

Rules:

- **Keep cases tiny.** One failing line points at one root cause.
- **No Unity-specific types.** The suites use BCL types only so the same code runs under `nos dotnet/run-tests` for Mono comparison. Unity-specific cases (e.g. `Vector3` interop) belong in a separate namespace.
