# Changelog

## v0.11.0 - 2026-07-24

Mostly compiler fixes: never-returning branches emitted invalid IL, protocol-hinted parameters threw `InvalidCastException`, a named fn was not `identical?` to its own self-reference, several numeric literals and integer operations emitted wrong bytes or overflowed instead of promoting, and the last known sources of nondeterministic DLL bytes are gone.

### Compiler
- `if`/`cond` forms whose branches never return (throw or recur on both sides) no longer emit a dead branch past the end of the method, which the CLR rejected with a `VerificationException`. Constant-test ifs, like `cond`'s `(if :else expr nil)` expansion, are analyzed correctly too - [#54](https://github.com/flybot-sg/magic/issues/54).
- A parameter hinted with a protocol name stays `Object` and dispatches through the protocol fn, as stock Clojure does. The hint used to narrow to the protocol's generated interface, so passing an `extend` / `extend-protocol` type threw `InvalidCastException` at the invoke boundary. A qualified tag, which is what `deftype` / `reify` specs expand to, still narrows - [#51](https://github.com/flybot-sg/magic/issues/51).
- A named fn's self-reference is the fn value itself, so `identical?` / `=` between them returns true, matching JVM and ClojureCLR. Reader source-location metadata is stripped from fn literals like upstream, so `(meta (fn [] 1))` is `nil` - [#52](https://github.com/flybot-sg/magic/issues/52), [#53](https://github.com/flybot-sg/magic/issues/53).
- When a type fails to resolve, the error hints at load-path DLLs matching the type's namespace prefix that are not loaded yet - [#70](https://github.com/flybot-sg/magic/issues/70).
- Reporting a wrong-arity instance-method call no longer throws an `ArityException` from the error reporter itself, masking the diagnostic - [#79](https://github.com/flybot-sg/magic/issues/79).
- `#inst`, `#uuid`, and any other literal without a dedicated emitter no longer throw `load-constant not implemented` when embedded in compiled source; they are written as a `print-dup` string and read back with `RT.readString` at load - [#83](https://github.com/flybot-sg/magic/issues/83).
- Unsigned integer constants keep their exact bits: a `UInt32` above `Int32/MaxValue` no longer throws `Value was either too large or too small` during emission, and a `UInt64` constant no longer emits corrupted IL through mage's untyped `Emit` dispatch - [#85](https://github.com/flybot-sg/magic/issues/85), [#88](https://github.com/flybot-sg/magic/issues/88).
- Widening an unsigned value to `long` zero-extends instead of sign-extending, so `(long UInt32/MaxValue)` is `4294967295`, not `-1`; the conversion opcode is chosen from the source type's signedness - [#87](https://github.com/flybot-sg/magic/issues/87).
- Casting an `Object` to a narrow numeric type (`Char`, `SByte`, `Int16`, `UInt16`, `UInt32`, `UInt64`) goes through the matching `RT` cast, so `(char x)` or `(int x)` on a boxed value no longer throws `InvalidCastException` - [#91](https://github.com/flybot-sg/magic/issues/91).
- Integer arithmetic promotes narrow operands to `long` like Clojure's numeric tower, so `(inc Int32/MaxValue)` is `2147483648` and `(inc UInt32/MaxValue)` is `4294967296` instead of overflowing or wrapping to a smaller type - [#92](https://github.com/flybot-sg/magic/issues/92), [#93](https://github.com/flybot-sg/magic/issues/93).

### Deterministic compilation
- Reusable-type selection and loop binding-type inference iterate their candidate sets in a sorted order instead of hash order, removing the last known ways unchanged sources could compile to different bytes across processes - [#60](https://github.com/flybot-sg/magic/issues/60), [#68](https://github.com/flybot-sg/magic/issues/68).
- The type-name gensym counter resets per file-writing compile unit, so editing an unrelated nostrand namespace no longer renumbers every committed DLL - [#64](https://github.com/flybot-sg/magic/issues/64).

### Runtime
- Every eval on Mono registered a dead extra dynamic assembly; the duplicate `DefineDynamicAssembly` call, leftover upstream debug code, is gone - [#77](https://github.com/flybot-sg/magic/issues/77).

### Nostrand
- New `nos where` prints the directory of the runtime the running host actually loaded - [#72](https://github.com/flybot-sg/magic/issues/72).
- `CLOJURE_LOAD_PATH` entries are absolute, so a loader scanning it finds native assemblies from any working directory, as on stock ClojureCLR - [#62](https://github.com/flybot-sg/magic/issues/62).
- A `deps.edn` without `:aliases` no longer throws a `NullReferenceException` when aliases are requested, undeclared aliases warn on stderr like tools.deps does, and a missing or malformed deps file is reported by name - [#55](https://github.com/flybot-sg/magic/issues/55).
- `nos test` can skip individual `deftest` vars via `:exclude-vars` in `magic.edn`'s `:test` config, not just whole namespaces, so a lib with a few platform-specific tests still runs the rest - [#96](https://github.com/flybot-sg/magic/issues/96).

### Unity
- Nine orphaned `clojure.tools.analyzer*` DLLs, excluded from the deploy target since the monorepo's first commit and last compiled in 2022, are dropped from `Export/` so they stop shipping in every player build - [#66](https://github.com/flybot-sg/magic/issues/66).

### Tooling
- The `SourceRevisionId` stamped into `Clojure.dll` and `Magic.Runtime.dll` includes release tags, so from this release on a build at a tag checkout stamps `0.11.0+v0.11.0-0-g<hash>` instead of a bare commit hash. Develop builds, where no release tag exists, keep the bare hash - [#75](https://github.com/flybot-sg/magic/issues/75).
- `bb refresh-stdlib` no longer flags the bootstrap-owned DLLs as missing source on every run - [#81](https://github.com/flybot-sg/magic/issues/81).

### Docs
- New [`docs/native-assemblies.md`](docs/native-assemblies.md): loading a precompiled C# assembly on the CLR via a loader namespace that scans `CLOJURE_LOAD_PATH`, and how to rebuild the committed native DLLs byte-stably - [#62](https://github.com/flybot-sg/magic/issues/62), [#74](https://github.com/flybot-sg/magic/issues/74).

## v0.10.0 - 2026-07-14

Compilation is now deterministic, so rebuilding unchanged sources reproduces the committed DLLs byte-for-byte, and CI catches a stale binary with a plain byte diff. The `bb` tasks got simpler from it too.

### Deterministic compilation
- Emission is deterministic and machine-independent: members emit in a stable content-derived order, sorts ignore the locale, `:file` metadata is load-relative, the `gensym` counter and the `type-lookup` cache reset per compile unit, and the saved assembly gets a zeroed PE timestamp and a content-derived MVID. The same tree compiles to identical bytes on macOS and Linux - [#43](https://github.com/flybot-sg/magic/issues/43), [#48](https://github.com/flybot-sg/magic/issues/48).
- `bb check-drift` byte-diffs the committed DLLs against a rebuild, and CI runs it on every PR. A single `dll-sources.edn` replaces the two source-SHA manifests, and a stale binary now fails CI no matter what made it stale - [#43](https://github.com/flybot-sg/magic/issues/43).
- `(load ...)` sub-file DLLs compile as explicit units after their parent, so `core_clr` and the `pprint` sub-files finally regenerate; they had been frozen at their 2020/2022 upstream bytes - [#45](https://github.com/flybot-sg/magic/issues/45).
- `AssemblyVersion` is pinned to `1.0.0.0`, and the release version lives in `FileVersion` / `InformationalVersion` (what `nos version` reports). Every emitted `.clj.dll` bakes `Clojure.dll`'s identity into its AssemblyRef, so deriving it from `version.edn` would have rewritten the whole committed DLL set on each release.

### Stdlib
- In `clojure.spec.alpha`, regex ops (`s/cat`, `s/*`, `s/alt`, ...) no longer throw `No matching clause`. The committed spec DLL predated the qualified-keyword `hasheq` change, so its `case` jump tables were keyed on the old hashes; the 17 stdlib DLLs that `dotnet build` skips are regenerated, and a regression suite now runs against the committed DLL - [#40](https://github.com/flybot-sg/magic/issues/40).
- `&` applies its predicates on empty input, so `(s/keys* :req-un [...])` rejects an empty sequence like upstream does - [#41](https://github.com/flybot-sg/magic/issues/41).
- `*ns-load-mappings*` is defined in `clojure.core-clr`. The runtime dropped its C#-side intern in 2020, which left the source uncompilable and `add-ns-load-mapping` missing from the committed DLL - [#45](https://github.com/flybot-sg/magic/issues/45).

### Tooling
- `bb bootstrap` replaces `build-magic` / `build-bootstrap` / `build-magic-portable`: one task that re-bootstraps, deploys, and re-records `dll-sources.edn`, forwarding extra args to `nos`.
- `bb test` takes namespace args to run a subset.

### Docs
- New [`docs/deterministic-compilation.md`](docs/deterministic-compilation.md) explains the guarantees and the drift workflow - [#43](https://github.com/flybot-sg/magic/issues/43).

## v0.9.0 - 2026-07-08

Aligns MAGIC's tooling with the ClojureCLR ecosystem: `nos` reads `deps-clr.edn` like `cljr` does, and a ported library builds and tests from a small `magic.edn` instead of a hand-written `dotnet.clj`. Plus a compiler fix for hinted by-ref locals.

### Nostrand
- `nos` reads `deps-clr.edn` in place of `deps.edn` when present, matching `cljr`, so a repo can carry CLR-specific deps without touching the JVM `deps.edn`. Project-root only - [#35](https://github.com/flybot-sg/magic/issues/35).
- Built-in `nos build` / `nos test` tasks read an optional `magic.edn` (`:build` / `:test` option maps), so a ported library no longer needs a hand-written `dotnet.clj`. It states only what differs from the defaults and is validated against a spec; existing `dotnet.clj` projects keep working - [#36](https://github.com/flybot-sg/magic/issues/36).

### Compiler
- `by-ref` on a type-hinted local now compiles instead of erroring in analysis: `analyze-byref` looks through the `:tagged` hint node and carries the tag onto the local - [#34](https://github.com/flybot-sg/magic/issues/34).

### Docs
- New `docs/clr-dependency-files.md`: choosing between `deps-clr.edn` and a `:clr` alias for CLR deps.
- The porting guide, README, and Unity guide cover the `magic.edn` and `nos build` / `nos test` workflow.

## v0.8.0 - 2026-06-24

Compiler and runtime correctness fixes, plus CI coverage for the committed bootstrap binaries.

### Compiler
- Inherited interface properties resolve on an interface type hint: property resolution now walks `.GetInterfaces` like method resolution already did, so e.g. `(.Count ^System.Collections.IDictionary d)` compiles instead of failing to resolve - [#32](https://github.com/flybot-sg/magic/issues/32).
- `proxy-super` resolves its base type from the enclosing proxy when `this` is shadowed by a type-hinted local, instead of crashing the analyzer - [#9](https://github.com/flybot-sg/magic/issues/9).

### Runtime
- `Compiler.InvokeInitType` initializes each assembly's init type at most once. A stray re-init of an already-loaded namespace no longer re-runs its top-level forms (which reset `*load-paths*` and cascaded into sub-namespace reloads). Reloading an already-initialized, DLL-backed namespace via `:reload` is now a no-op; source reload and freshly recompiled DLLs are unaffected - [#3](https://github.com/flybot-sg/magic/issues/3).

### Magic.Unity
- `BootMagicRuntime` warns when a fork `Clojure.dll` is loaded but an expected bootstrap member is missing (the package scripts and `Clojure.dll` are mismatched MAGIC versions), instead of silently skipping the runtime bootstrap. It stays silent under stock ClojureCLR, so coexistence editors and hot-reload setups see nothing.
- Dropped the obsolete NuGet packaging from the package.

### Tooling
- `bb check-drift` now guards the bootstrap and compiler `.clj.dll` set that `refresh-stdlib` skips (`clojure.core`, the `core_*` helpers, `magic.*`, `mage`) via `magic-compiler/bootstrap-manifest.edn` source SHA256s. Editing a compiler or core source without rerunning `bb build-magic` + `bb build-bootstrap` now fails CI instead of shipping a stale committed binary.

### Docs
- Project documentation restructured and CLR porting/interop guides added.

## v0.7.0 - 2026-06-09

A second Unity package variant for projects that keep stock ClojureCLR as the editor runtime.

### Magic.Unity
- New `sg.flybot.magic.unity.dual` variant: the runtime `*.clj.dll` carry a `!UNITY_EDITOR` define constraint, so Unity excludes them from the editor and a coexistence project no longer logs the `Assembly is incompatible with the editor` lines on every domain reload. The default `sg.flybot.magic.unity` is unchanged and still runs MAGIC in editor Play mode - [#30](https://github.com/flybot-sg/magic/issues/30).
- New `docs/unity-integration.md` covers the consumer workflow and how to choose a variant.

### Tooling
- `bb gen-unity-dual` generates the dual variant from `magic-unity` (drift-checked by `bb check-drift`); `bb coexist-noise` reproduces the console noise in-repo via `unity-examples/magic-unity-coexist`. Example Unity projects moved under `unity-examples/`.

## v0.6.0 - 2026-06-07

Stock-ClojureCLR coexistence for Unity consumers that keep ClojureCLR as the editor runtime, plus IL2CPP workaround-selection fixes.

### Compiler
- `set!` on a hinted mutable deftype field emits a `castclass`, fixing unverifiable IL that IL2CPP's transpiler rejects - [#27](https://github.com/flybot-sg/magic/issues/27).

### Magic.Unity
- Coexistence: while a strong-named `Clojure.dll` is under `Assets`, fork `.clj.dll` plugins are excluded from the editor (and restored when it leaves), keeping stock RT's `clojure.core.clj` probe away from fork assemblies - [#25](https://github.com/flybot-sg/magic/issues/25). Editor scripts compile against the stock assembly in that state - [#24](https://github.com/flybot-sg/magic/issues/24).
- IL2CPP workaround signatures come from player compilation references instead of an editor AppDomain scan, keeping editor-only assemblies (e.g. `Mono.WebBrowser` on Windows) out of the signature pool - [#23](https://github.com/flybot-sg/magic/issues/23).
- The workaround resolver searches project-local player reference directories - [#26](https://github.com/flybot-sg/magic/issues/26).
- `csc.rsp` `-r:` references count as player references and are logged for build-log verification.
- README documents the benign coexistence console lines (`Assembly is incompatible with the editor`).

## v0.5.0 - 2026-06-04

Consumer quality-of-life fixes from the 0.4.0 rollout.

### Nostrand deps
- The git-dep cache root honours the `GITLIBS` env var (cloning under `$GITLIBS/nostrand/`), so CI can keep the cache inside the checkout and one variable relocates both the JVM and CLR caches; `~/.nostrand/gitlibs` stays the default when unset - [#17](https://github.com/flybot-sg/magic/issues/17).

### Tooling
- `run-clojure-tests` takes `:re`, a regex scoping the run to the namespaces it fully matches, so a consumer can skip suites loaded by its dependencies - [#19](https://github.com/flybot-sg/magic/issues/19).

### Stdlib
- Plain 1-arg `slurp` no longer prints the `(slurp f enc) is deprecated` warning on every call; `normalize-slurp-opts` aligned with ClojureCLR's CLJCLR-127 revert - [#18](https://github.com/flybot-sg/magic/issues/18).

### Docs
- Porting guide: CI caching section with the canonical `GITLIBS` block, `:clean? true` in the drop-in `dotnet.clj`, and `:re` usage with the `re-matches` full-match caveat.

## v0.4.0 - 2026-06-04

Native `deps.edn` resolution for nostrand, replacing `project.edn`, plus shared `dotnet.clj` build/test helpers - [#15](https://github.com/flybot-sg/magic/issues/15).

### Nostrand deps
- Nostrand resolves `deps.edn` natively at boot (alias merge, transitive git and local coords into `~/.nostrand/gitlibs`, `:override-deps`), so projects no longer need a `project.edn`. Private repos now authenticate through your git/SSH config rather than coordinate-level credentials. The `project.edn`-era providers (github, gitlab, maven, ipfs, nuget acquire) are removed; `mage`, `magic-compiler`, and `magic-unity-smoke` move onto `deps.edn`.
- `:nos/submodule-paths` derives a project's `:paths` from `.gitmodules`, so a submodule-vendored project no longer hand-maintains the list.

### Tooling
- `nostrand.tasks` provides shared `dotnet.clj` helpers (`production-flags`, `compile-project`, `run-clojure-tests`), so consumer projects stop restating the flag binding block and namespace lists.

### Docs
- New [`docs/porting-libraries-to-magic.md`](docs/porting-libraries-to-magic.md): porting an existing Clojure library to MAGIC, including the RCT-on-CLR workflow.

## v0.3.0 - 2026-06-01

Completes the Clojure 1.10 stdlib surface and unifies the compiler config behind `magic.flags`.

### Clojure 1.10 stdlib (the marked-1.10 port is now complete)
- `ex-message` / `ex-cause` added to `clojure.core` - Fix [nasser/magic#238](https://github.com/nasser/magic/issues/238)
- `symbol` arity-1 now converts a Var (qualified) or Keyword
- `tap>` / `add-tap` / `remove-tap` (the tap system) ported to `clojure.core`
- `read+string` ported to `clojure.core`, backed by string capture in `LineNumberingTextReader`
- `PrintWriter-on` ported to `clojure.core` - Closes [#8](https://github.com/flybot-sg/magic/issues/8)
- `Throwable->map` brought to the 1.10 shape: conditional `:message`/`:cause` and the `:phase` key; `StackTraceElement->vec` and the StackFrame print-method derive the class from the frame's declaring type - part of [#10](https://github.com/flybot-sg/magic/issues/10)
- `ex-triage` / `ex-str` ported to `clojure.main`; `repl-caught` rewired to the 1.10 `Throwable->map` -> `ex-triage` -> `ex-str` path - part of [#10](https://github.com/flybot-sg/magic/issues/10)
- `prepl` / `io-prepl` / `remote-prepl` ported to `clojure.core.server` - Closes [#10](https://github.com/flybot-sg/magic/issues/10)
- `renumbering-read` ported to `clojure.main`; `repl-read` rewired to it - Closes [#12](https://github.com/flybot-sg/magic/issues/12)
- `defprotocol :extend-via-metadata` dispatch implemented (direct defs -> fully-qualified-symbol metadata -> extend table) - Closes [#13](https://github.com/flybot-sg/magic/issues/13)

### Compiler
- `magic.flags` is now the single config surface: every compilation knob is a dynamic var there, and spells (`*lift-vars*`, `*lift-keywords*`, `*sparse-case*`) are flags too. Removed `magic.core/*spells*`, `bind-spells!`, the load-time global mutation, and the dead `magic.spells.protocols` spell; `active-spells` derives the spell fns from the flags - Fix [nasser/magic#233](https://github.com/nasser/magic/issues/233)
- `throw` of a `let`/`loop`-local introduced inside a `catch` now compiles (the thrown expression was recompiled with a stale captured compilers map) - Closes [#7](https://github.com/flybot-sg/magic/issues/7)

### Runtime
- `LineNumberingTextReader` captures read text for `read+string`
- `LispReader` `MetaReader` preserves an explicit `:line`/`:column`/`:source-span` instead of clobbering it with positional values (uses positional only as a default), matching JVM 1.10 and ClojureCLR - part of [#12](https://github.com/flybot-sg/magic/issues/12)
- `MethodImplCache` carries the protocol fn symbol needed for `:extend-via-metadata` dispatch - part of [#13](https://github.com/flybot-sg/magic/issues/13)

### Tooling
- `bb prepl-server` / `bb prepl-eval`: live runtime eval against a warm nostrand-hosted MAGIC runtime (a socket io-prepl), the runtime complement to `bb pipeline` - Closes [#11](https://github.com/flybot-sg/magic/issues/11)

### Deps & docs
- `deps.edn` switched to monorepo paths and `flybot-sg/clr.test.check`
- Component READMEs refreshed; hardcoded version pins dropped
- `magic-unity/package.json` metadata fixed and version synced from `version.edn`

## v0.2.0 - 2026-05-23

Bug-fix release. Three compiler and stdlib fixes.

### Compiler fixes
- Analyzer throws a clear "Unable to resolve type hint" error instead of silently dropping unresolvable hints and bottoming out at "no constructor with args [nil]" - Closes [#5](https://github.com/flybot-sg/magic/issues/5)

### Stdlib fixes
- Seed `*loaded-libs*` with `clojure.core` so `(:require [clojure.core :as core])` no longer re-loads core and cascades through every sub-namespace - Closes [#2](https://github.com/flybot-sg/magic/issues/2)
- `defn`/`defmacro` with a `:pre`/`:post` map no longer crash at def-eval (`sigs` skips the prepost conj; runtime asserts still fire). Same block fix: only strip `&form` `&env` when at least 2 params remain - Closes [#4](https://github.com/flybot-sg/magic/issues/4)

### Docs
- `CONTRIBUTING.md` adds conventions for issue filing, PR style, component labels, and commit messages

## v0.1.0 - 2026-05-22

First release of Flybot's MAGIC monorepo. Consolidates Ramsey Nasser's six MAGIC repos (`magic`, `mage`, `Clojure.Runtime`, `Magic.Runtime`, `nostrand`, `Magic.Unity`) into one tree with unified release tooling, plus `magic-unity-smoke`, a new IL2CPP regression project. Author and date history of all six upstream repos is preserved.

### Release & distribution (new)
- One-line `nos` install via `install/nos.sh` (curl + tar, requires `mono` runtime, no .NET SDK)
- `nos` CLI ships as a GitHub Releases tarball
- `magic-unity` UPM package consumed via git URL `?path=magic-unity#<tag>`; package renamed to `sg.flybot.magic.unity`
- Single `version.edn` source of truth: `Directory.Build.props` derives `<Version>` for every csproj, `nos version` reports unified component versions
- `bb verify-dist` pre-tag gate; `bb tag` reads `version.edn`, creates the annotated tag, and pushes it (the release workflow takes over from there)

### Compiler fixes (vs. nasser/magic upstream)
- `reify` against `System.Object` now compiles under IL2CPP
- `letfn` sets closed-over fields after allocating instances - Fix [nasser/magic#218](https://github.com/nasser/magic/issues/218)
- Instance methods on value types use `constrained.callvirt` - Fix [nasser/magic#225](https://github.com/nasser/magic/issues/225)
- `magic.api/eval` returns the last value from a top-level `do` - part of [nasser/magic#237](https://github.com/nasser/magic/issues/237)
- Vector literals preserve metadata - 2 failures in [nasser/magic#237](https://github.com/nasser/magic/issues/237)
- Fast call-site lambdas convert args before invoking - 3 errors in [nasser/magic#237](https://github.com/nasser/magic/issues/237)

### Stdlib fixes
- `clojure.datafy/datafy-ns` uses `Namespace.Name` instead of `.FullName` - Fix [nasser/magic#236](https://github.com/nasser/magic/issues/236)
- String and map `hasheq` match JVM Clojure - Fix [nasser/magic#239](https://github.com/nasser/magic/issues/239)
- `clojure.pprint` `emit-nl` drops the nullable `^String` hint that broke compilation

### Unity / IL2CPP
- New `magic-unity-smoke/` IL2CPP regression project on Unity `2022.3.62f3`
- Preserve EH boundary instructions during the unreachable-IL sweep (prevents method-body corruption)
- Unity 2022 compatibility: AssemblyDefinition leak fix, `link.xml` merge

### Tooling
- `bb` task runner with paired dev loops: `bb dev-compiler`, `bb dev-runtime`, `bb dev-callsites` (build + test + revert byproducts in one shot)
- `bb refresh-stdlib` with `magic-compiler/stdlib-manifest.edn` for SHA-tracked drift detection of stdlib `.clj.dll`s
- `bb pipeline '<form>'` walks a Clojure form through reader → AST → symbolic IL, dumping EDN per stage
- Bootstrap parameterized with `:spells [<sym> ...]` (any spell; symbols resolved at call time). `bb build-magic-portable` is a preset that applies `sparse-case` for hashing-semantics changes so `case` jump tables are not baked with stale hash values
- `MAGIC_DEBUG_LOAD` env var traces `load-one` decisions for namespace-resolution debugging
- Callsite `.g.cs` codegen output written in-tree under `magic-runtime/Magic.Runtime/Generated/`

### CI
- `.github/workflows/test.yml` runs `bb test` on PRs and develop/main pushes
- `.github/workflows/release.yml` builds and publishes the `nos` tarball to GitHub Releases on every `v*` tag push
- `bb check-drift` gate fails if generated callsite `.g.cs` or stdlib `.clj.dll`s are out of sync with source (PR check, also runnable locally)

### Repo
- All six upstream repos consolidated via `git-filter-repo` with full author/date history
- ClojureCLR fork embedded as `clojure-runtime/`; David Miller and ClojureCLR contributors appear in the GitHub contributors view
- Build-time NuGet packages and GitHub Actions bumped to current versions
