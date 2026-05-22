# Changelog

### v0.1.0 - 2026-05-22

First release of Flybot's MAGIC monorepo. Consolidates Ramsey Nasser's six MAGIC repos (`magic`, `mage`, `Clojure.Runtime`, `Magic.Runtime`, `nostrand`, `Magic.Unity`) into one tree with unified release tooling, plus `magic-unity-smoke`, a new IL2CPP regression project. Author and date history of all six upstream repos is preserved.

#### Release & distribution (new)
- One-line `nos` install via `install/nos.sh` (curl + tar, requires `mono` runtime, no .NET SDK)
- `nos` CLI ships as a GitHub Releases tarball
- `magic-unity` UPM package consumed via git URL `?path=magic-unity#<tag>`; package renamed to `sg.flybot.magic.unity`
- Single `version.edn` source of truth: `Directory.Build.props` derives `<Version>` for every csproj, `nos version` reports unified component versions
- `bb verify-dist` pre-tag gate; `bb tag` reads `version.edn`, creates the annotated tag, and pushes it (the release workflow takes over from there)

#### Compiler fixes (vs. nasser/magic upstream)
- `reify` against `System.Object` now compiles under IL2CPP
- `letfn` sets closed-over fields after allocating instances - Fix [#218](https://github.com/nasser/magic/issues/218)
- Instance methods on value types use `constrained.callvirt` - Fix [#225](https://github.com/nasser/magic/issues/225)
- `magic.api/eval` returns the last value from a top-level `do` - part of [#237](https://github.com/nasser/magic/issues/237)
- Vector literals preserve metadata - 2 failures in [#237](https://github.com/nasser/magic/issues/237)
- Fast call-site lambdas convert args before invoking - 3 errors in [#237](https://github.com/nasser/magic/issues/237)

#### Stdlib fixes
- `clojure.datafy/datafy-ns` uses `Namespace.Name` instead of `.FullName` - Fix [nasser/magic#236](https://github.com/nasser/magic/issues/236)
- String and map `hasheq` match JVM Clojure - Fix [nasser/magic#239](https://github.com/nasser/magic/issues/239)
- `clojure.pprint` `emit-nl` drops the nullable `^String` hint that broke compilation

#### Unity / IL2CPP
- New `magic-unity-smoke/` IL2CPP regression project on Unity `2022.3.62f3`
- Preserve EH boundary instructions during the unreachable-IL sweep (prevents method-body corruption)
- Unity 2022 compatibility: AssemblyDefinition leak fix, `link.xml` merge

#### Tooling
- `bb` task runner with paired dev loops: `bb dev-compiler`, `bb dev-runtime`, `bb dev-callsites` (build + test + revert byproducts in one shot)
- `bb refresh-stdlib` with `magic-compiler/stdlib-manifest.edn` for SHA-tracked drift detection of stdlib `.clj.dll`s
- `bb pipeline '<form>'` walks a Clojure form through reader → AST → symbolic IL, dumping EDN per stage
- Bootstrap parameterized with `:spells [<sym> ...]` (any spell; symbols resolved at call time). `bb build-magic-portable` is a preset that applies `sparse-case` for hashing-semantics changes so `case` jump tables are not baked with stale hash values
- `MAGIC_DEBUG_LOAD` env var traces `load-one` decisions for namespace-resolution debugging
- Callsite `.g.cs` codegen output written in-tree under `magic-runtime/Magic.Runtime/Generated/`

#### CI
- `.github/workflows/test.yml` runs `bb test` on PRs and develop/main pushes
- `.github/workflows/release.yml` builds and publishes the `nos` tarball to GitHub Releases on every `v*` tag push
- `bb check-drift` gate fails if generated callsite `.g.cs` or stdlib `.clj.dll`s are out of sync with source (PR check, also runnable locally)

#### Repo
- All six upstream repos consolidated via `git-filter-repo` with full author/date history
- ClojureCLR fork embedded as `clojure-runtime/`; David Miller and ClojureCLR contributors appear in the GitHub contributors view
- Build-time NuGet packages and GitHub Actions bumped to current versions
