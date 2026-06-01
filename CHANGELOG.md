# Changelog

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
