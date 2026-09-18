# Deploy `Clojure.dll` and `Magic.Runtime.dll` from their own projects

Not started. `Nostrand.dll` already works this way; this note is what is left to make the other two committed C# DLLs match it.

## Problem

The repo's rule for committed binaries is that the task producing one deploys it to every committed location:
`refresh/stdlib` writes the stdlib set to `references/`, `bin/` and `Runtime/magic`,
`refresh/nostrand` writes nostrand's set to `references/`, `bin/` and `Editor/Compiler`,
and `Nostrand.csproj` copies its own `net471` Release output into `Editor/Compiler` on every build.

`Clojure.dll` and `Magic.Runtime.dll` are the exception.
`bb build-runtime` rebuilds both and deploys neither.
The only copy into `magic-unity/Runtime/magic` is the `MagicUnity` target in the root `Magic.csproj`, which is the bootstrap's orchestrator and runs under `bb build` alone.
So a C# runtime fix reaches the package only through a full bootstrap, or the by-hand `dotnet build -t:MagicUnity` that `docs/bootstrap.md` prescribes.

## Why it was left this way

Both csprojs stamp a `git describe` `SourceRevisionId` into `InformationalVersion` (`SetSourceRevisionId` in `clojure-runtime/Clojure.csproj` and `magic-runtime/Magic.Runtime/Magic.Runtime.csproj`).
Their bytes therefore change on every commit, and no rebuild reproduces the committed ones.
`bb check-drift` restores the two from HEAD instead of byte-diffing them (`drift/check!` in `bb/magic/drift.clj`), and maintainers refresh them deliberately.
A post-build copy today would leave the two files modified in `git status` after every `bb build-runtime`.

## Steps

1. **Make the two DLLs byte-stable**, the way `nostrand-lib/Nostrand.csproj` already is:
   drop the `SetSourceRevisionId` target from both csprojs and set
   `EnableSourceControlManagerQueries=false`, `IncludeSourceRevisionInInformationalVersion=false` and `DebugType=none` for Release.
   Confirm with two Release builds from different checkout paths that the bytes match.
   The stamp stays on `NostrandMain.csproj`: `nos version` reads git describe from the exe through `Nostrand.VersionSource`.
2. **Decide what `nos version` prints for the runtimes.**
   `nostrand.tasks/version` prints `Nostrand/ClojureRuntimeVersion` and `Nostrand/MagicRuntimeVersion`, which read `InformationalVersion` from the two DLLs.
   After step 1 those report `version.edn`'s number alone, no commit hash.
   The CHANGELOG entry for #75 describes the current tagged format (`0.11.0+v0.11.0-0-g<hash>`), so that entry's promise ends here; say so in the CHANGELOG.
3. **Add a `DeployToMagicUnity` post-build target to each csproj**, copying `$(TargetPath)` into `../magic-unity/Runtime/magic`, conditioned on `net471` and Release, mirroring the one in `Nostrand.csproj`.
   Check which target framework the package actually ships from: `Magic.csproj` uses `ClojureRuntimeDllNet471` and `MagicRuntimeDllNet471`.
4. **Trim `Magic.csproj`'s `MagicUnity` target** to the two bootstrap sets.
   Remove the `<Copy>` lines for `ClojureRuntimeDllNet471` and `MagicRuntimeDllNet471` and the properties (the `netstandard2.0` one looks unused already).
5. **Let the drift check byte-diff all three C# DLLs.**
   Remove the `git checkout` of the two DLLs from `drift/check!`.
6. **Docs.** `docs/deterministic-compilation.md` ("A DLL can go stale" and the paragraph on the two restored DLLs),
   `docs/bootstrap.md` (the "never commit `magic/` straight after a player build" rule and its `check-drift` first, then `-t:MagicUnity` ordering, which no longer applies),
   `docs/dll-provenance.md` (the `Clojure.dll` and `Magic.Runtime.dll` rows),
   `docs/development.md` (`bb build-runtime`),
   `CONTRIBUTING.md` (the sentence on the two DLLs that cannot be byte-verified),
   root `CLAUDE.md` (the auto-revert paragraph and the `bb dev-runtime` row).
7. **Verify** with `bb clean && bb build && bb check-drift && bb test`, then `bb build-runtime` on a clean tree: `git status` must stay clean.

## Consequence to accept

Every Release build of the two runtime projects writes into a tracked package directory.
That is already true of `Nostrand.dll` and of `bb build`, and once the bytes are stable an unchanged source leaves an unchanged file, so the write is visible only when it should be.
