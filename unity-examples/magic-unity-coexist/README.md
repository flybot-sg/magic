# magic-unity-coexist

A minimal Unity project that reproduces the **stock-ClojureCLR coexistence
noise** in-repo, so the magic repository can validate fixes to that bug class
without the private consumer project where it was first observed.

## What it reproduces

On every editor open or domain reload, a consumer that keeps stock ClojureCLR
in the editor (for REPL and hot-reload) while shipping the MAGIC fork runtime in
player builds prints one ERROR line per package Export `*.clj.dll`:

```
Assembly 'Packages/sg.flybot.magic.unity/Runtime/Infrastructure/Export/clojure.core_clr.clj.dll' will not be loaded due to errors:
Assembly is incompatible with the editor
```

plus one benign `Duplicate assembly 'Clojure.dll'` dedup line. At MAGIC v0.6.0
the baseline is **46** narration lines. They are benign: Unity is narrating the
intended editor exclusion from issue #25, not a real failure. This project
exists to make that narration measurable and to let a silencing patch prove it
went to zero without regressing the exclusion.

## Why the noise happens (and why this setup is the only way to see it)

Two ingredients are both required:

1. **A foreign stock `Clojure.dll` under `Assets/`.** `StockClojureCoexistence`
   scans the filesystem for a strong-named `Clojure.dll`; finding one flips
   every fork `*.clj.dll` plugin to editor-loading-off (issue #25: stock
   `RT` probe-loads `clojure.core.clj` at init and a fork DLL answering that
   probe throws a `TypeLoadException` storm). This project vendors the exact
   stock layout under `Assets/Plugins/clojure-clr/` (Clojure 1.11.0 net462 +
   DLR 1.3.2 + spec packages), marked Editor-only, identical to the consumer.

2. **An immutable (PackageCache) install of the package.** The narration is a
   *mismatch*: Unity reads the shipped `.meta` (editor-compatible) to decide the
   editor candidate set, then the baked import artifact excludes the DLL, so it
   narrates. On a mutable `file:` install the flip is written back to the
   on-disk `.meta`, the mismatch disappears, and the narration never appears.
   That is exactly why the standard `magic-unity-smoke` project (a `file:`
   install) never shows this, and why `bb coexist-noise` installs the package
   from a repacked tarball, which Unity resolves into a read-only PackageCache.

The standalone `magic-unity-smoke` project deliberately has no stock ClojureCLR,
so it has no coexistence and never narrates. This project is its coexistence
counterpart.

## Running it

```
bb coexist-noise            # tests the dual variant: expect 0 narration lines
bb coexist-noise magic-only # tests the default variant: expect 46 lines (the problem)
```

From the repo root. The task:

1. regenerates `magic-unity-dual` from `magic-unity` (`bb gen-unity-dual`), then
   packs the chosen variant into `magic-unity.tgz` (a UPM tarball, the immutable
   install);
2. writes `Packages/manifest.json` with the dependency key that matches the
   packed variant's `package.json` name (mismatched names make UPM refuse to
   resolve, which would look like a false "0 narration" pass);
3. forces a fresh PackageCache resolve (deletes the lock and the cached
   package);
4. launches Unity 2022.3.62f3 headless twice: a cold import, then a domain
   reload that runs `CoexistenceProbe`;
5. reports the narration-line count, the dedup-line count, and the probe state,
   and asserts the expected outcome for the variant.

The tarball, `Packages/packages-lock.json`, and `Logs/` are build artifacts and
are gitignored. The vendored `Assets/Plugins/clojure-clr/` tree is committed: it
is the fixture. The committed `manifest.json` pins the dual variant (the
default test target).

Quit any GUI Unity holding this project first; batchmode exits 134 otherwise.

### GUI

Open `magic-unity-coexist` in Unity Hub (2022.3.62f3) after running
`bb coexist-noise magic-only` once (so the magic-only tarball is resolved and the
problem is present). Watch `~/Library/Logs/Unity/Editor.log` on a domain reload
(for example, by re-saving any script) for the 46 lines.

## What each variant must prove

- **Dual variant** (`bb coexist-noise`): **0** narration lines, and the probe
  still reports `core-clj-loadable=false` with `preloaded-clj=0` (the runtime
  `clj.dll`s stay out of the editor domain, so a stock `RT` init cannot resolve
  them: issue #25 stays fixed). The `Duplicate assembly 'Clojure.dll'` dedup
  line remains (benign, and confirms the package actually resolved). A run that
  reports 0 narration but no probe line is INCONCLUSIVE, not a pass: the package
  likely failed to resolve.
- **Magic-only variant** (`bb coexist-noise magic-only`): **46** narration lines,
  reproducing exactly what a coexistence consumer of the default package sees.
  This is the problem the dual variant solves.

Player builds are unaffected by either variant: the Export DLLs are discovered
for IL2CPP through player compilation references, independent of editor
visibility. Confirm in `magic-unity-smoke` (`nos dotnet/build` then Build & Run
IL2CPP) that the player path is unchanged.

The `CoexistenceProbe` marker line in the editor log carries the per-run state:

```
[CoexistenceProbe] preloaded-clj=0 core-clj-loadable=false core-clj-load=FileNotFoundException clojure-versions=[1.11.0.0] export-clj-editor-off=46
```
