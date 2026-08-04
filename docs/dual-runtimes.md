# Dual runtimes in one Unity package

Planning / implementation doc. Status: **implemented.** Every work item below is
done; the per-issue notes record what shipped and where it differed from the
plan. Spike run 2026-07-31 against Unity 2022.3.62f3, implementation validated
the same day by `bb coexist-noise` in both states.

## Decision

Ship both Clojure runtimes from this repo and let a scripting define symbol
decide which one the **Editor** loads. Player builds always get MAGIC.

The selector is a plugin **define constraint** on each shipped DLL, not code. A
define constraint is static: a `.meta` cannot say "exclude MAGIC *if* a stock
runtime happens to exist". So whichever state applies when the symbol is unset
must be self-sufficient for every consumer — that is the load-bearing decision
here, and it drives everything else.

**Polarity is forced: default stock, opt in to MAGIC.** Symbol
`MAGIC_RUNTIME_IN_EDITOR`.

| | Editor, symbol unset (default) | Editor, symbol set | Player build |
|---|---|---|---|
| stock ClojureCLR | loaded | excluded | excluded |
| MAGIC fork | excluded | loaded | loaded (always) |

MAGIC DLLs (`Clojure.dll`, `Magic.Runtime.dll`, all 37 `*.clj.dll`):

```yaml
defineConstraints:
- '!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR'
```

Stock ClojureCLR DLLs:

```yaml
defineConstraints:
- UNITY_EDITOR
- '!MAGIC_RUNTIME_IN_EDITOR'
```

The appealing inverse — default MAGIC with `STOCK_CLOJURE_IN_EDITOR` as the
opt-in, which would need no stock runtime in the default state and would change
nothing for current consumers — **cannot be expressed**. It requires
`'!UNITY_EDITOR || !STOCK_CLOJURE_IN_EDITOR'`, and Unity ANDs negated terms
inside an OR group (§Mechanism), so that entry is unsatisfied in the Editor
whether or not the symbol is set. Setting the symbol also drops the DLLs from
**player** builds — measured, `player-clj-refs` 44 → 7 — because PlayerSettings
define symbols reach player compilation too.

That generalises to a rule for any future constraint here: **the selector symbol
must appear as a plain term in the MAGIC constraint, never negated.** Polarity A
is immune by construction — setting its symbol can only ever satisfy the
constraint, in the Editor and in the player alike.

The consequence is that the symbol-unset default depends on a stock runtime
being present, which is what forces the package to vendor one and
makes this a breaking change for current default-package consumers.

This retires the `StockClojureCoexistence` guard: it exists only to solve, at
runtime, the problem the constraints now solve by construction.

## Why this replaces what we have

Today the Editor/player split is enforced two ways at once:

- **`StockClojureCoexistence`** — an Editor guard that scans `Assets` for a
  strong-named `Clojure.dll` and, finding one, flips every fork `*.clj.dll`
  plugin to editor-loading-off, tracked by a `userData` marker and a state file
  in `Library`.
- **Plugin platform settings** — `Any: enabled 1` on MAGIC DLLs, against
  `Any: enabled 0` + `Editor: enabled 1` on an Editor-only stock set.

The premise behind the guard is going away: consumer repos will no longer ship
their own foreign `Clojure.dll`. Once every DLL comes from this repo, plugin
metadata can express the whole policy statically.

Note `PackageExportPath.cs` is *not* part of this enforcement — it exists so the
Editor code can find the package's own DLLs by package path, because Unity
dedups managed plugins by file name. It stays. So does `PlayerCljAssemblies.cs`,
which becomes more load-bearing, not less: in the default (stock Editor) state
the AppDomain holds no MAGIC assemblies at all, and player discovery must keep
going through `CompilationPipeline`.

## Mechanism, as verified

Define-constraint evaluation, read from
`UnityEditor.Scripting.ScriptCompilation.DefineConstraintsHelper` in
2022.3.62f3:

- `IsDefineConstraintsCompatible_Enumerable` fails if **any** list entry is
  incompatible — entries are **AND**-ed.
- `GetDefineConstraintCompatibility` splits each entry on a `(\|\|)` regex, trims,
  and partitions terms into `!`-prefixed and plain.
- **`||` is not a plain OR.** An entry is satisfied iff **every negated term is
  absent**, *or* **any plain term is present**. Negated terms therefore behave as
  AND, and an entry made only of negated terms can never be satisfied while any
  one of them is defined. Measured by calling the evaluator directly:

  | defines | `!UNITY_EDITOR \|\| MAGIC_RUNTIME_IN_EDITOR` | `!UNITY_EDITOR \|\| !STOCK_CLOJURE_IN_EDITOR` | `[UNITY_EDITOR, STOCK_CLOJURE_IN_EDITOR]` |
  |---|---|---|---|
  | `{UNITY_EDITOR}` | Incompatible | Incompatible | Incompatible |
  | `{UNITY_EDITOR, STOCK…}` | Incompatible | Incompatible | **Compatible** |
  | `{STOCK…}` | **Compatible** | Incompatible | Incompatible |
  | `{}` (synthetic only) | Incompatible | Compatible | Incompatible |

  Every cell is *Compatible* or *Incompatible*, never *Invalid* — the all-negated
  form parses, it just does not mean what it reads like. The empty-defines row is
  a special path in the evaluator and never occurs in practice; a real player
  define set always carries `UNITY_2022_3`, `UNITY_STANDALONE`, etc.
- `&&` and parentheses are not supported. A doubled or trailing `||` is reported
  as *Invalid* (distinct from *Incompatible*), and the plugin Inspector shows
  which.
- `IsDefineConstraintsCompatible` is annotated `[RequiredByNativeCode]`: the
  native compatibility check calls this exact managed method, so driving it by
  reflection tests the real decision function, not a parallel implementation.
- The define set includes PlayerSettings **Scripting Define Symbols**, confirmed
  empirically (below). Those symbols are per build-target group, and the Editor
  compiles with the **active** group's symbols.

Duplicate file names, read from `PrecompiledAssemblyProvider`: the map is keyed
on `Path.GetFileName` via `TryGetValue`, first-wins. It is not an error. Unity
logs the outcome:

```
Duplicate assembly 'Clojure.dll' with different versions detected,
  using    'Assets/Plugins/clojure-clr/clojure.1.11.0/net462/Clojure.dll' 1.11.0.0 PublicKeyToken=cf3caecd327a2fa9
  ignoring 'Packages/sg.flybot.magic.unity.dual/Runtime/Infrastructure/Export/Clojure.dll' 1.0.0.0 PublicKeyToken=null
```

`Clojure.dll` is the **only** file-name collision: stock ClojureCLR ships no
`*.clj.dll` (its stdlib is `Clojure.dll` + `Clojure.Source.dll`, and side libs
are `clojure.spec.alpha.dll`, not `clojure.spec.alpha.clj.dll`).

## Spike evidence

All rows headless via `bb coexist-noise` in `unity-examples/magic-unity-coexist`:
immutable PackageCache install, stock ClojureCLR 1.11.0 vendored in `Assets`,
plus one unconstrained MAGIC-compiled consumer DLL (`smoke.interop.clj.dll`).
"guard" = `StockClojureCoexistence`; "stock constrained" = the two-entry AND
block above on the vendored `Clojure.dll`; the MAGIC package always carried the
OR constraint on its 37 `*.clj.dll`, with its `Clojure.dll` left unconstrained.

| # | guard | stock | symbol | narration | `preloaded-clj` | `core-clj-loadable` | `clojure-versions` | dedup | editor/player clj refs |
|---|---|---|---|---|---|---|---|---|---|
| 1 | active | unconstrained | off | 0 | 0 | false | `[1.11.0.0]` | 2 | – |
| 2 | active | unconstrained | on | 37 | 0 | false | `[1.11.0.0]` | 3 | 37 / 38 |
| 3 | active | constrained | on | 37 | 0 | false | `[1.0.0.0]` | 0 | 37 / 38 |
| 4 | inert | constrained | on | 0 | 38 | **true** | `[1.0.0.0]` | 0 | 38 / 38 |
| 5 | inert | constrained | off | 0 | 1 | false | `[1.11.0.0]` | 3 | 1 / 38 |

What each row establishes:

1. The OR form behaves exactly like the plain `!UNITY_EDITOR` constraint —
   today's shipped dual behaviour is preserved.
2. **A user-defined symbol gates plugin constraints.** Narration 0 → 37 is
   attributable to nothing else. Player references never moved (38).
3. Constraining the stock side makes `Clojure.dll` resolution deterministic:
   the fork wins and the `Duplicate assembly` line disappears.
4. **The target Editor state works.** MAGIC boots in the Editor:
   `clojure.core.clj` resolves (`Version=0.0.0.0`), 38 clj assemblies in the
   domain, fork wins, zero console noise.
5. **The default Editor state works.** Stock wins, MAGIC's 37 stay out, and the
   stock `RT` probe for `clojure.core.clj` fails with `FileNotFoundException` —
   issue #25 stays fixed by construction, with no guard.

A sixth run rejected the inverse polarity. With
`'!UNITY_EDITOR || !STOCK_CLOJURE_IN_EDITOR'` stamped on the 37 package metas in
`magic-unity-smoke`:

| symbol | `editor-clj-refs` | `player-clj-refs` |
|---|---|---|
| unset | 6 (project DLLs only) | 44 |
| set | 6 | **7** |

MAGIC never becomes Editor-visible in either state, and setting the symbol
removes the 37 package DLLs from the **player** as well. See §Decision.

Two further results from `unity-examples/magic-unity-smoke` (no stock
ClojureCLR present):

- Constraining the fork `Clojure.dll` with nothing to replace it turns the
  project red: `Magic.Unity.cs(3,7) CS0246: 'clojure' could not be found`, plus
  `Var` at lines 13 and 89. The asmdef compiles on all platforms and hard-binds
  `clojure.lang`, so **some** `Clojure.dll` must be Editor-visible in every state.
- `editor-clj-refs` moved 6 → 44 with the symbol while `player-clj-refs` held at
  44, measured through `CompilationPipeline.GetAssemblies`.

## Target design

**One package, both runtimes.** `sg.flybot.magic.unity` is the only install a
consumer needs, in either state:

```
magic-unity/Runtime/Infrastructure/Export/   fork Clojure.dll, Magic.Runtime.dll,
                                             37 *.clj.dll
magic-unity/Runtime/Infrastructure/Stock/    stock ClojureCLR + the DLR
```

A consumer migrating off their own nuget-restore layout has to **flatten** it:
`RT.DoInit` `Assembly.LoadFile`s `Clojure.Source.dll`, `clojure.spec.alpha.dll`
and `clojure.core.specs.alpha.dll` as siblings of `Clojure.dll`, unconditionally
and with no fallback, so `clojure.1.11.0/net462/` + `…spec.alpha.0.3.218/…`
cannot be lifted across as-is.

A two-package split (`clojure-clr-unity`, `sg.flybot.clojure.clr.unity`) would be
lighter for MAGIC-only consumers and is the natural shape at default-MAGIC
polarity — but that polarity is inexpressible (§Decision). At the forced polarity
the default install needs a stock runtime, UPM `dependencies` resolve only from a
registry so a git-URL package cannot pull one in, and a consumer installing
`magic-unity` alone hits CS0246 on first import. Shipping both sets from one
package is what keeps the default state self-sufficient.

Worth revisiting only if the polarity ever becomes expressible, or if these
packages move to a registry where UPM `dependencies` work. It would also make the
symbol a contract between two separately versioned packages, and would leave a
consumer's own vendored copy still needing the stock constraint stamped onto it
(issue 5).

**Every DLL of both sets carries its constraint block**, `Clojure.dll` included.
Leaving either `Clojure.dll` unconstrained reintroduces the dedup race (row 5).

**Deleted:** `bb gen-unity-dual`, its `check-drift` dependency, and
`StockClojureCoexistence.cs` in full (guard, bootstrap,
`CljPluginPreprocessor`, `userData` markers, `Library/MagicUnityCoexistenceState.txt`).

**Kept:** `PackageExportPath.cs`, `PlayerCljAssemblies.cs`, `IL2CPPWorkarounds`,
`LinkXmlGenerator`, and the reflection-bound bootstrap in `Magic.Unity.cs` —
which stops being an edge case and becomes the everyday path, since the default
Editor state compiles `Magic.Unity` against stock `Clojure.dll`.

### What `Magic.Unity` binds against, and what that means

`Magic.Unity.cs` touches only `clojure.lang.RT`, `Var` and `Symbol`
(`RT.var(string,string)`, `Var.deref()`, `Var.invoke(object)`,
`Symbol.intern(string)`) — identical signatures in stock and fork, since the fork
descends from stock. Everything fork-only is reflection-bound already. So it
compiles against either runtime, which is what makes the default state possible.

Unity compiles the assembly **twice**, once per context, against whichever
runtime that context sees. The player compilation excludes stock, so the shipped
assembly always binds the fork; "compiled against stock" never reaches a device.

The consequence to document for consumers: in the default Editor state
`Boot`/`Require`/`GetVar` drive **stock**, not MAGIC. `BootMagicRuntime` finds no
`RuntimeBootstrapFlag`, returns silently by design, and stock self-initialises on
the first `RT.var`. Identical call sites therefore mean different things — stock
loads namespaces from `.clj` source through the DLR, MAGIC loads AOT `InitType`
— so Editor `Require` needs sources on stock's load path and can diverge from
player behaviour. That is the intended workflow for a project keeping stock for
REPL work, but it is implicit and reads like "MAGIC works in the Editor".

**Added:** an AssetPostprocessor that stamps the MAGIC constraint block onto any
`Assets/**/*.clj.dll`; an Editor menu toggle that writes the symbol to every
build-target group; and the EPL-1.0 / Apache-2.0 notices for the third-party
binaries — `magic-unity/LICENSE.md`, `magic-unity/Third Party Notices.md`, and
the licence texts in `magic-unity/Licenses~/` (a `~` folder, so Unity generates
no metas for them and issue 8 does not apply).

## Consequences for consumers

- **Breaking.** Existing `sg.flybot.magic.unity` users get stock-in-Editor where
  they had MAGIC, and must set `MAGIC_RUNTIME_IN_EDITOR` to keep today's
  behaviour. Needs a headline CHANGELOG entry and a one-line migration note.
  Unavoidable: the polarity that would have preserved their behaviour is
  inexpressible (§Decision).
- `sg.flybot.magic.unity.dual` is retired, and its users are the ones this
  costs nothing: stock-in-Editor with MAGIC in players is exactly the new
  default, so they install the default package and set no symbol. They should
  delete the stock `Clojure.dll` they vendored under `Assets`, since the package
  now ships one — unconstrained, their copy wins the dedup and breaks the
  MAGIC-in-Editor state (issue 3).
- **The Editor must run `apiCompatibilityLevel: 3` (`.NET Framework`).** Stock
  ClojureCLR is net462, and the constraint comes from the DLR rather than from
  `Clojure.dll`, which references only mscorlib / System / System.Core:
  `Microsoft.Scripting` references `System.Configuration`, and
  `Microsoft.Dynamic` references `System.Runtime.Remoting` and `System.Xaml` —
  none of which exist in the .NET Standard 2.1 profile. ClojureCLR's compile and
  interop paths always run through the DLR, so this is unavoidable.

  No netstandard escape: the nupkg's `lib/netstandard2.1/Clojure.dll` is 696 KB
  and defines no `clojure.core` types, against 3.59 MB and 1531 for net462,
  because `ILMerge462` splices the AOT stdlib into the net462 assembly only.

  Both example projects run `6` today, which is right for them — they run MAGIC,
  which has no DLR. `magic-unity-coexist` has to move to `3` as part of issue 6,
  since it will take stock from the package. This is also why level `6` never
  produced an error: stock has never initialised far enough to reach the DLR,
  `RT.DoInit`'s `Assembly.LoadFile` of `Clojure.Source.dll` and the spec DLLs
  having only been fixed upstream in `2418b32`.
- The symbol is per build-target group and the Editor uses the active group's —
  hence the menu toggle rather than a doc note.
- ClojureCLR + DLR weight lands on every consumer, including those who will only
  ever run MAGIC, and the EPL-1.0 / Apache-2.0 notices for those third-party
  binaries ship in the one package. This is the cost of a self-sufficient
  default; only the rejected split avoids it.

## Work items

Issues follow the repo convention: the title states the problem, the body has
`## Problem` and optional `## Suggestion`. All are `comp:magic-unity` unless
noted.

### 1. Choosing between the MAGIC and stock Clojure runtimes requires reinstalling a different package

**Problem.** The Editor/player runtime split is fixed at install time by which
of two packages a consumer picks. `magic-unity-dual` is byte-identical to
`magic-unity` apart from `defineConstraints: ['!UNITY_EDITOR']` on 37 metas and
the package name, yet it must be generated, committed, drift-checked and
released in lockstep. A consumer who wants stock ClojureCLR for REPL work today
and MAGIC in Play mode tomorrow has to swap packages.

**Suggestion.** Ship one package that carries both constraint blocks and select
the Editor runtime with a scripting define symbol (§Decision). Validated in the
spike above (rows 4 and 5).

**Done.** All 39 `Export/*.dll.meta` carry
`'!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR'`, against the two-entry block the 7
`Stock/` metas carry. `magic-unity/Editor/EditorRuntime.cs` is the switch: a
`MAGIC/Editor Runtime/` menu writing the symbol to every build-target group,
with `UseMagic()` / `UseStock()` public for CI. The blocks are
authored metadata, so `magic.unity/check-constraints!` (wired into `bb
check-drift` and `bb write-metas`) fails if any DLL in either set loses its
block — a DLL added to `Export/` later arrives with an unconstrained meta from
Unity, and nothing else would catch it. To run it on its own:
`bb -e "(require '[magic.unity :as u]) (u/check-constraints!)"`.

### 3. Which `Clojure.dll` the Editor loads is decided by path precedence, not configuration

**Problem.** Unity dedups managed plugins by file name
(`PrecompiledAssemblyProvider.FilenameToPrecompiledAssembly`, first-wins on
`TryGetValue`) and both runtimes ship a `Clojure.dll` — fork 1.0.0.0 unsigned,
stock 1.11.0.0 strong-named. With both Editor-eligible, Unity picks one and logs
`Duplicate assembly 'Clojure.dll' with different versions detected`. Observed in
spike rows 2 and 5: the `Assets` copy won over the package copy. In row 2 the
MAGIC stdlib was Editor-eligible while stock won `Clojure.dll`, i.e. 37
assemblies bound to 1.0.0.0 against a loaded 1.11.0.0.

**Suggestion.** Constrain **both** `Clojure.dll`s so exactly one is ever
eligible. Row 3 shows the duplicate line disappearing and
`clojure-versions=[1.0.0.0]` once the stock side is constrained.

**Done** with issue 1, and now asserted: `bb coexist-noise` fails either state on
a single `Duplicate assembly 'Clojure.dll'` line, and both states report exactly
one version (`[1.11.0.0]` stock, `[1.0.0.0]` MAGIC).

### 4. The coexistence guard overrides define constraints from filesystem state

**Problem.** `StockClojureCoexistence.ForeignClojurePresent()` scans
`Application.dataPath` for a strong-named `Clojure.dll` on disk and, finding
one, flips every `*.clj.dll` plugin to editor-loading-off. It does not consult
Unity's compatibility state, so it fires even when the stock DLL is already
excluded by a define constraint. In spike row 3 — stock constrained out, symbol
set, fork winning `Clojure.dll` — the guard still produced 37 narration lines and
`preloaded-clj=0`, i.e. it prevented the runtime the consumer asked for from
loading. Making it inert is the only reason row 4 works.

**Suggestion.** Delete `StockClojureCoexistence.cs` entirely: the guard, the
`[InitializeOnLoad]` bootstrap, `CljPluginPreprocessor`, the `userData` markers
and `Library/MagicUnityCoexistenceState.txt`. Constraints subsume all of it.

**Done.** File deleted, and the `Reconcile()` call dropped from
`MagicPreprocessor`'s build hook, which now goes straight to the IL2CPP rewrite.
`PlayerCljAssemblies`'s comment was the only other reference; its reasoning is
unchanged, just re-anchored on the constraint rather than the guard — discovery
must still go through `CompilationPipeline`, because in the default state the
editor domain holds no MAGIC assemblies at all.

### 5. MAGIC-compiled consumer DLLs load silently into a stock-ClojureCLR Editor

**Problem.** A consumer's own `.clj.dll` in `Assets/Plugins/` carries no define
constraint, so it stays Editor-eligible when MAGIC's runtime is excluded, and it
binds unsigned `Clojure` 1.0.0.0 which is not loaded. In spike row 5 the DLL was
admitted to the Editor domain (`preloaded-clj=1`) with **no log line at all** —
no narration, no warning. The mismatch surfaces only when something touches a
type from it. Today this is masked by the coexistence guard, which flips those
plugins off and writes `userData: Magic.Unity.StockClojureCoexistence:any-platform`
into their metas; issue 4 removes that cover.

**Suggestion.** An AssetPostprocessor that stamps the MAGIC constraint block
(§Decision) onto every `Assets/**/*.clj.dll` on import — the same hook as
`CljPluginPreprocessor`, with a simpler rule, writing to mutable `Assets` metas
where writes are legal. Have `nos dotnet/build` emit the constraint in generated
metas too.

**Done** as `magic-unity/Editor/CljPluginConstraints.cs`, appending the block
rather than replacing `DefineConstraints`, so a constraint the consumer added
themselves still holds (entries are AND-ed).

The `nos dotnet/build` half of the suggestion was **dropped as inapplicable**:
nostrand writes no `.meta` files at all — Unity generates them on import, which
is exactly the hook above. Nothing to change there.

Regression: the fixture now carries `Assets/Plugins/Consumer/smoke.interop.clj.dll`
with its `.meta` gitignored and deleted before each run, so every run re-derives
the stamp. It moves the default state from the spike's `preloaded-clj=1`,
`editor-clj-refs=1` (row 5, the silent admission this issue is about) to `0` and
`0`.

### 6. `bb coexist-noise` asserts one fixed outcome and cannot express a two-runtime matrix

`comp:bb`, `comp:unity-examples`

**Problem.** `magic.unity/verdict` hardcodes "the dual variant must be silent
**and** report `core-clj-loadable=false`". With the symbol-driven design there
are two valid states with opposite expectations, so the task mislabels them: the
working MAGIC-in-Editor state (row 4) comes back `:inconclusive` because
`core-clj-loadable=true`, and rows 2–3 come back `:fail`. The variant axis
(`dual` / `magic-only`) no longer exists.

**Suggestion.** Re-key the task on Editor-runtime state rather than package
variant, and assert per state: **stock Editor** → 0 narration,
`clojure-versions=[1.11.0.0]`, `core-clj-loadable=false`; **MAGIC Editor** → 0
narration, `clojure-versions=[1.0.0.0]`, `core-clj-loadable=true`,
`preloaded-clj` = the shipped stdlib count. Which of the two is the
symbol-unset default follows §Decision. Keep the
probe reporting `symbol=`, `editor-clj-refs=`, `player-clj-refs=` so a null
result is never ambiguous. Move the project to `apiCompatibilityLevel: 3` — it
currently runs `6`, which cannot resolve the DLR references stock needs
(§Consequences).

**Done.** `bb coexist-noise` now runs both states (or one: `bb coexist-noise
stock` / `magic`), comparing every probe field against that state's expectations
instead of matching one hardcoded sentence, and additionally asserts
`player-clj-refs` is identical across states. `apiCompatibilityLevel: 3` set,
manifest repointed at `sg.flybot.magic.unity`, and `bb gen-unity-dual` and its
`check-drift` wiring are gone.

Two implementation notes:

- The symbol is written into the fixture's `ProjectSettings.asset`, not through
  the new menu toggle. The constraints are evaluated during the cold import,
  before any `-executeMethod` could run, so a toggle call would land one Unity
  launch too late.
- `symbol=` is read from `#if MAGIC_RUNTIME_IN_EDITOR` in the probe rather than
  from `PlayerSettings`, so it reports the symbol as the Editor *compilation* saw
  it — the same define set the plugin constraints were evaluated against, which
  is the thing that could silently fail to arrive.

Measured, both states `:pass`:

| state | narration | dedup | `preloaded-clj` | `core-clj-loadable` | `clojure-versions` | editor/player clj refs |
|---|---|---|---|---|---|---|
| `stock` (symbol unset) | 0 | 0 | 0 | false (`FileNotFoundException`) | `[1.11.0.0]` | 0 / 38 |
| `magic` (symbol set) | 0 | 0 | 38 | true (`clojure.core.clj/0.0.0.0`) | `[1.0.0.0]` | 38 / 38 |

One gap this regression does **not** cover: `apiCompatibilityLevel`. The stock
state still passes at level `6`, because the fixture observes that stock's
`Clojure.dll` loaded without ever driving `RT` far enough to reach the DLR —
verified by running it at `6` on purpose. Closing that would mean booting stock
from the probe, with sources on its load path. Until then the API level is
guarded only by `EditorRuntime`'s warning, which was verified the same way (fires
once, in the stock state at level `6`, and not otherwise).

### 7. `magic-unity/CLAUDE.md` reports DLL counts that no longer match the package

**Problem.** `Runtime/Infrastructure/Export/` holds 39 DLLs (37 `*.clj.dll` +
`Clojure.dll` + `Magic.Runtime.dll`). `magic-unity/CLAUDE.md` says "48 DLLs"
(line 13) and "the 46 runtime `*.clj.dll` plugins" (line 45), and the narration
baselines at line 47 and root `CLAUDE.md` line 118 say 46 where `bb.edn` and the
fixture README say 37.

**Suggestion.** Correct to 39 / 37 in `magic-unity/`. Fold into whichever issue
rewrites these docs for the single-package design.

**Done**, folded into that rewrite as suggested. The narration baselines the wrong
counts appeared in are gone with the variant they described — both states are
asserted silent now, so there is no baseline count left to keep in sync.
`Stock/` is documented alongside `Export/`, including the flat-directory
requirement.

### 8. Unity regenerates an untracked `magic-unity/CLAUDE.md.meta`

**Problem.** `magic-unity-smoke` installs the package as a mutable `file:` path,
so Unity imports the whole package directory and generates `.meta` files for
non-asset files such as `CLAUDE.md`. The result is untracked, which can trip
`bb check-drift` depending on when it last ran.

**Suggestion.** Either commit the generated metas or exclude docs from the
package payload (`.npmignore` / `files` in `package.json`).

**Done** by a third route: `CLAUDE.md.meta` is gitignored, next to the existing
`CLAUDE.md` rule. Neither suggested option actually works here — `.npmignore` and
`files` apply to registry tarballs, and these packages install by git URL or
`file:` path, where Unity imports the directory as it finds it and will generate
the meta regardless. Since `CLAUDE.md` is itself gitignored (both tracked copies
predate that rule), treating its meta the same way is the consistent fix.

### 9. `bb verify-dist` passes on a package that ships no stock runtime

`comp:bb`

**Problem.** `magic.release/verify-dist!` (`bb/magic/release.clj`) is the
pre-tag gate: it asserts `Clojure.dll` and `Magic.Runtime.dll` exist under
`magic-unity/Runtime/Infrastructure/Export/` and that at least one `*.clj.dll`
matches. It knows nothing about `Runtime/Infrastructure/Stock/`, so a release
that never vendored the stock runtime — or dropped it in a bad merge — passes.

At the forced polarity that is not a cosmetic omission. The symbol-unset default
state, which is what every consumer gets on install, has no Clojure runtime
without those files, and the failure mode is `Magic.Unity.cs` failing to compile
against a missing `clojure.lang` on first import. `bb sync-clojure-clr` is a
manual maintainer step outside `check-drift`, so nothing else in the release path
notices its absence.

**Suggestion.** Extend the `required` vector with the seven `Stock/` DLLs.
Better, assert against the allowlist `magic.clojure-clr/assets` already defines
(currently `^:private`) so the release gate and the sync task cannot drift apart.

**Done, then superseded.** `verify-dist!` did briefly require `Stock/Clojure.dll`
and `Stock/Clojure.Source.dll`, but the gate has moved and neither half of the
suggestion survives.

The allowlist is gone: `magic.clojure-clr/assets` was deleted, and `sync!` now
takes whatever `*.dll` the release publishes, so there is no set to assert
against.

The premise is gone too. This issue was filed while `Stock/` was untracked, when
"did anyone vendor it?" was a live question. `Stock/` is committed now, so `bb
check-drift` byte-diffs it on every pull request and on pushes to `main` and
`develop` — a missing or altered runtime fails there, earlier and louder than a
pre-tag check reached. Duplicating that in `verify-dist!` bought nothing.

What is left in `verify-dist!` is the `nos version` smoke test alone. `nos` boots
the whole runtime to print its version, so a missing launcher, runtime DLL or
stdlib `.clj.dll` exits non-zero — verified by deleting all 73 `.clj.dll`, which
fails on an unbound `clojure.core/-load` with exit 1. That covers
`nostrand/bin/`, the one path in the release that is untracked build output.

## Implementation order

Polarity is settled (§Decision), so this is a straight sequence. **All steps are
done**; the sequence is kept because the release constraint in step 2 is not just
a plan.

1. Issue 9 — extend the release gate to require the vendored stock runtime.
   (Since superseded: the gate is `check-drift` on the committed `Stock/` bytes,
   not `verify-dist!` — see issue 9. The release constraint below still holds.)
2. Issues 1 + 3 — constraint blocks on both sets, the symbol, the menu toggle.

   **Do not release between vendoring the stock runtime and this step.** Separate
   commits are fine; a release in between is not, because the vendored
   `Clojure.dll` is Editor-eligible while the fork's is still unconstrained, which
   is the dedup race of rows 2 and 5 (§Target design) — now inside a single
   package, where which copy wins is decided by import order.
3. Issue 5 — consumer-DLL stamping. Before the guard goes, since the guard is
   what covers those DLLs today.
4. Issue 4 — delete the guard.
5. Issue 6 — rework the regression, then retire `bb gen-unity-dual` and its
   `check-drift` wiring.
6. Issues 7 + 8 — docs and drift hygiene.

## Validation

- ✅ `bb coexist-noise` in both symbol states, per issue 6. Both `:pass`; table
  under issue 6.
- ⏳ `unity-examples/magic-unity-smoke`: `nos dotnet/build`, then an IL2CPP Build &
  Run in both symbol states — output must be identical. **Not yet run: this is a
  by-hand GUI step.** `player-clj-refs` held constant across all five polarity-A
  spike rows and again across both implemented states, which is the static half of
  this check. Note the symbol *does* reach the player define set; polarity A is
  unaffected only because the symbol appears there as a plain term. The sixth
  run showed what happens otherwise (44 → 7), so this check is not a formality.

  The smoke project now sets `MAGIC_RUNTIME_IN_EDITOR` in its committed
  `ProjectSettings.asset` — it runs MAGIC in Play mode, so it is the first
  consumer to take the breaking change.
- ⏳ `bb clean && bb build && bb check-drift && bb test` before the PR. Not yet
  run here (a full rebuild); `magic.unity/check-constraints!` and `bb
  verify-dist` pass standalone.

## Open questions

- ~~Polarity~~ — **resolved.** Default-MAGIC / opt-in-stock is inexpressible in
  Unity's constraint grammar (§Mechanism, §Decision); default-stock /
  opt-in-MAGIC is the only shape that works, and it forces both the vendoring
  and the breaking change. `MAGIC_RUNTIME_IN_EDITOR` still beats
  `USE_AOT_IN_EDITOR` as a name: AOT is a player-backend concern, and this
  symbol selects an Editor runtime.
- Whether the two-package split is worth revisiting if Unity ever supports a
  real OR over negated terms, or if a registry ever makes UPM `dependencies`
  usable here. Both would make the default state self-sufficient without
  shipping ClojureCLR + DLR to every consumer.
- Whether to ship `clojure.test.check`, the only optional member of the set.
  `Clojure.Source.dll`, `clojure.spec.alpha.dll` and
  `clojure.core.specs.alpha.dll` are **not** optional — `RT.DoInit` `LoadFile`s
  all three unconditionally, so stock does not initialise without them, and the
  earlier framing of this question as "or only what a stock Editor REPL actually
  needs" was wrong. test.check is neither a release asset nor a nuspec dependency
  of the runtime, so including it means publishing it from clojure-clr first;
  nothing in this design needs it.
- ~~Whether the menu toggle should also switch `apiCompatibilityLevel`~~ —
  **resolved: warn only.** Asserting `3` unconditionally would silently rewrite a
  project setting the consumer owns as a side effect of a click about something
  else, and a dedicated menu item to write it was no better — the setting stays
  the consumer's, changed from `Project Settings > Player`. Instead
  `EditorRuntime` warns — from `[InitializeOnLoadMethod]`, since the default
  install lands in exactly the state that needs it — and only when the
  configuration is genuinely broken (Editor on stock **and** the level wrong).
  One warning line in a broken configuration is not the per-domain-reload noise
  this design set out to remove.
