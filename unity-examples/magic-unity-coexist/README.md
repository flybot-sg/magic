# magic-unity-coexist

A minimal Unity project that regression-tests **which Clojure runtime the Editor
loads**, in both of the states the `magic-unity` package supports. It is the
in-repo stand-in for the private consumer project where the coexistence bug
class ([#25](https://github.com/flybot-sg/magic/issues/25),
[#30](https://github.com/flybot-sg/magic/issues/30)) was first observed.

## What it checks

The package ships both runtimes — MAGIC's fork under
`Runtime/Infrastructure/Export/` and stock ClojureCLR under
`Runtime/Infrastructure/Stock/` — and a define constraint on every DLL lets the
`MAGIC_RUNTIME_IN_EDITOR` scripting define symbol select one for the Editor.
Player builds always get MAGIC. So there are two valid steady states, and they
assert opposite things:

| state | symbol | Editor loads | `preloaded-clj` | `core-clj-loadable` | `clojure-versions` |
|---|---|---|---|---|---|
| `stock` (default) | unset | stock ClojureCLR 1.11.0 | 0 | false | `[1.11.0.0]` |
| `magic` | set | the MAGIC fork | 38 | true | `[1.0.0.0]` |

Both must produce **0** `Assembly is incompatible with the editor` lines and
**0** `Duplicate assembly 'Clojure.dll'` lines, and `player-clj-refs` must not
move between them — that last one is the static half of "player builds are
unaffected by the Editor selection".

`core-clj-loadable=false` in the stock state is the point of #25: the fork
`*.clj.dll` are not in the editor domain, so stock's init-time
`Assembly.Load("clojure.core.clj")` probe cannot resolve one and cannot start a
`TypeLoadException` storm. It stays fixed by construction now — by plugin
metadata Unity evaluates before loading anything — rather than by an Editor
guard reacting to filesystem state.

## Why this project and not `magic-unity-smoke`

Two ingredients are needed to see the failure modes this guards against:

1. **An immutable (PackageCache) install.** The old narration was a *mismatch*:
   Unity read the shipped `.meta` to pick the editor candidate set, then the
   baked import artifact excluded the DLL, so it narrated. On a mutable `file:`
   install the flip was written back to the on-disk `.meta` and the mismatch
   disappeared. `magic-unity-smoke` is a `file:` install and never showed it;
   `bb coexist-noise` installs from a repacked tarball, which Unity resolves
   into a read-only PackageCache.
2. **A consumer-compiled `.clj.dll` outside the package.**
   `Assets/Plugins/Consumer/smoke.interop.clj.dll` stands in for a consumer's
   own compiled namespace. It carries no constraint in git; the package's
   `CljPluginConstraints` postprocessor stamps one on import, which is what
   keeps it out of a stock Editor.

   The DLL is fixture input; its `.meta` is package *output*. Unity generates
   the `.meta` and `CljPluginConstraints` stamps the runtime constraint into it,
   so committing it would both dirty the tree on every run and turn the stamping
   into setup instead of something the run has to re-derive. That is why the
   consumer `.meta`s are gitignored and deleted before each import.

The stock runtime is **not** vendored here any more — it comes from the package,
exactly as a consumer's would. That is also why this project runs API
Compatibility Level `.NET Framework` (`3`): stock ClojureCLR is net462 and its
DLR dependencies reference assemblies the .NET Standard profile does not have.

## Running it

```
bb coexist-noise            # both states
bb coexist-noise stock      # just the default state
bb coexist-noise magic      # just the opted-in state
```

From the repo root. Per state the task:

1. writes `MAGIC_RUNTIME_IN_EDITOR` into `ProjectSettings.asset` (not through
   the package's menu toggle: the constraints are evaluated during the cold
   import, before any `-executeMethod` could run);
2. packs `magic-unity` into `magic-unity.tgz`, a UPM tarball — the immutable
   install;
3. forces a fresh PackageCache resolve and drops the consumer `.meta`s;
4. launches Unity 2022.3.62f3 headless twice: a cold import, then a domain
   reload that runs `CoexistenceProbe`;
5. compares every probe field against that state's expectations and reports
   `:pass`, `:fail`, or `:inconclusive`.

A missing probe line is `:inconclusive`, never a pass: it usually means the
package failed to resolve, which would also give 0 narration lines.

Quit any GUI Unity holding this project first; batchmode exits 134 otherwise.
A full run leaves the fixture in the `stock` state (the default, symbol unset).
To open the fixture in the GUI with the MAGIC Editor runtime, run
`bb coexist-noise magic` first.

The tarball, `Packages/packages-lock.json`, `Logs/` and the consumer `.meta`s
are build artifacts and are gitignored.

The `CoexistenceProbe` marker line in the editor log carries the whole per-run
state:

```
[CoexistenceProbe] symbol=unset preloaded-clj=0 core-clj-loadable=false core-clj-load=FileNotFoundException clojure-versions=[1.11.0.0] editor-clj-refs=0 player-clj-refs=38
```

`symbol=` is read from the compiled define set (`#if MAGIC_RUNTIME_IN_EDITOR`),
not from `PlayerSettings`, so it reports the symbol as the Editor compilation
actually saw it — the same thing the plugin constraints were evaluated against.

Player builds are unaffected by the selection: the Export DLLs are discovered
for IL2CPP through player compilation references, independent of editor
visibility. Confirm end to end in `magic-unity-smoke` (`nos dotnet/build`, then
Build & Run IL2CPP) — the output must be identical in both symbol states.
