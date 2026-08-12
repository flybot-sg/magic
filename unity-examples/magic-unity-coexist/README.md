# magic-unity-coexist

A minimal Unity project that regression-tests **which Clojure runtime the Editor
loads**, in both of the states the `magic-unity` package supports. It is the
in-repo stand-in for the private consumer project where the coexistence bug
class ([#25](https://github.com/flybot-sg/magic/issues/25),
[#30](https://github.com/flybot-sg/magic/issues/30)) was first observed. The
driver — and the reasoning behind every check — is
[`bb/magic/coexist.clj`](../../bb/magic/coexist.clj).

## What it checks

The package ships both runtimes and `MAGIC_RUNTIME_IN_EDITOR` selects the
Editor's; player builds always get MAGIC. So there are two valid steady states,
and they assert opposite things:

| state | symbol | Editor loads | `preloaded-clj` | `core-clj-loadable` | `clojure-versions` |
|---|---|---|---|---|---|
| `clojure-clr` (default) | unset | ClojureCLR 1.11.0 | 0 | false | `[1.11.0.0]` |
| `magic` | set | the MAGIC fork | 38 | true | `[1.0.0.0]` |

Both must be silent — **0** `Assembly is incompatible with the editor` lines,
**0** `Duplicate assembly 'Clojure.dll'` lines — and `player-clj-refs` must not
move between them. `core-clj-loadable=false` in the default state is #25 held by
construction: the fork `*.clj.dll` are not in the editor domain, so ClojureCLR's
namespace resolution can never reach them.

After the states, three more checks:

- **The upgrade path** (three launches): import under the package packed
  *without* `CljPluginConstraints.cs`, upgrade to the real package with the
  consumer metas left in place, and assert `Reconcile` constrained every one of
  them — in one log line — and that a fresh launch drops them from the Editor.
- **The toggle** (one launch): `EditorRuntimeProbe` seeds a deliberately
  unsorted define list, sets and clears the symbol through
  `Magic.Unity.EditorRuntime`, and must show append-then-exact-restore with no
  build-target group's defines changed.
- **The logs**: every log the run wrote is scanned for error-shaped lines,
  catching what no other check predicted.

## Why this project and not `magic-unity-smoke`

Two ingredients smoke lacks:

1. **An immutable (PackageCache) install.** `bb coexist-noise` installs from a
   repacked tarball; `magic-unity-smoke` is a mutable `file:` install, on which
   this bug class cannot appear.
2. **A consumer-compiled Clojure DLL outside the package.**
   `Assets/Plugins/Consumer/smoke.interop.cljr.dll` stands in for a consumer's
   own compiled namespace. Its `.meta` is package *output* — the constrainer
   writes it on import — so the metas are gitignored and deleted before each
   import; committing them would turn the constraining into setup.

The ClojureCLR runtime comes from the package, exactly as a consumer's would,
which is why the project runs API Compatibility Level `.NET Framework`.

## Running it

```
bb coexist-noise              # both states, then the upgrade path and the toggle
bb coexist-noise clojure-clr  # just the default state, plus those
bb coexist-noise magic        # just the opted-in state, plus those
```

A full run is eight Unity launches — two per state, three for the upgrade path,
one for the toggle — on Unity 2022.3.62f3 with no GUI editor holding the
project (batchmode exits 134 otherwise). It leaves the fixture in the
`clojure-clr` state; to open it in the GUI with the MAGIC Editor runtime, run
`bb coexist-noise magic` first.

The tarball, `Packages/packages-lock.json`, `Logs/` and the consumer `.meta`s
are build artifacts and are gitignored.

The `[CoexistenceProbe]` marker line in the editor log carries the per-run
state:

```
[CoexistenceProbe] symbol=unset preloaded-clj=0 core-clj-loadable=false core-clj-load=FileNotFoundException clojure-versions=[1.11.0.0] editor-clj-refs=0 player-clj-refs=38
```

Player builds are unaffected by the selection; the end-to-end confirmation is
an IL2CPP Build & Run in `magic-unity-smoke` in both symbol states
([dual runtimes](../../docs/dual-runtimes.md), Remaining validation).
