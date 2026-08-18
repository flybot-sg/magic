# Development

`bb.edn` is the task runner. Every task names the edit it belongs to, and this page is what each one does. `bb tasks` prints the short version.

## Start from the edit

Find the file you touched, run the task on its row, then `bb test`.

| You changed | Run | Runs |
|---|---|---|
| any C# in `clojure-runtime/`, `magic-runtime/` or `nostrand/` | `bb build-runtime` | one `dotnet build` |
| a callsite `.mustache` template | `bb dev-callsites` | `regen-callsites` → `build-runtime` |
| `magic-compiler/src/stdlib/**/*.clj`, outside the `clojure.core` family | `bb refresh-stdlib` | one compile pass over the stdlib |
| `magic-compiler/src/magic/**/*.clj`, `mage/src/`, or the `clojure.core` family | `bb dev-compiler` | `bootstrap` → `bootstrap` |
| a fresh clone | `bb build` | clean, then all of the above |

A compiler change needs two bootstrap passes, hence the convenience task `bb dev-compiler`. For more information check [the bootstrap](./bootstrap.md), which also covers why the `clojure.core` family belongs to the bootstrap instead of `refresh-stdlib`, and which changes need more than two passes.

## Building

**`bb build-runtime`** builds `nostrand/NostrandMain.csproj` in Release. `clojure-runtime` and `magic-runtime` come along through `ProjectReference`, so it covers the C# the host and the compiled DLLs run on.

**`bb bootstrap`** is one pass of the compiler's own rebuild: compile with the compiler currently in `references/`, deploy over it, re-record `dll-sources.edn`. Extra arguments reach `nos`, which is how a spell is enabled for a pass:

```bash
bb bootstrap :spells '[magic.spells.sparse-case/sparse-case]'
```

**`bb refresh-stdlib`** recompiles every committed stdlib namespace outside the `clojure.core` family into `nostrand/references/`, the built host, and the Unity package's `magic/`.

**`bb build`** is the fresh-clone path and the one CI runs. It cleans first, so the bootstrap is always redone from scratch. **`bb clean`** removes the `bin/` directories and `magic-compiler/bootstrap/`.

`bb build`, `bb bootstrap` and `bb refresh-stdlib` stamp the committed DLL mtimes before compiling, which is what decides whether a namespace loads from its DLL or recompiles from source. That is why a raw `dotnet build` is not a substitute ([deterministic compilation](./deterministic-compilation.md)).

## Regenerating what is committed

- **`bb regen-callsites`** writes the 97 `.g.cs` under `magic-runtime/Magic.Runtime/Generated/` from five `.mustache` templates. A template edit is only real once this has run.
- **`bb sync-upm-version`** copies the version from `version.edn` into `magic-unity/package.json`.
- **`bb write-metas`** creates a Unity `.meta` (with its runtime-selection define constraint) for every `magic-unity` DLL that lacks one; existing metas are never rewritten. The constraint blocks are verified by `bb check-drift`; what they say and why is beside `runtime-sets` in `bb/magic/unity.clj`.
- **`bb check-drift`** runs all three plus `refresh-stdlib` and the constraint verification, then fails if any checked path differs from HEAD. Run it after a fresh `bb build`, as CI does. What it byte-diffs and what it only restores are in [deterministic compilation](./deterministic-compilation.md).

## Testing

```bash
bb test                                    # the whole suite
bb test magic.test.fn magic.test.reify     # just these namespaces
```

`nos test` turns `*direct-linking*` and `*strongly-typed-invokes*` off so `with-redefs` can still rebind calls ([what they change](./nos-cli.md#compiler-flags)). Only `magic.test.fn` binds direct linking back on, for named-fn self-reference, and nothing covers strongly-typed invokes, so build a real consumer project after a compiler change. IL2CPP-only failures need [magic-unity-smoke](../unity-examples/magic-unity-smoke), and editor coexistence needs `bb coexist-noise`.

## Inspecting a form

**`bb pipeline`** walks a form through the compiler and stops before running it: form → macroexpand → AST → symbolic IL.

```bash
bb pipeline '(let [x 1] (+ x 1))'
bb pipeline '(let [x 1] x)' :sections '#{:ast :il}'           # stdout only, no files
bb pipeline '(.Length "hi")' :sections '#{:il-edn}' :out /tmp # just the flat IL
```

`TYPES` is the section to read when diagnosing intrinsic rewrites, static versus dynamic call-site selection, or numeric promotion. EDN dumps land in `magic-compiler/target/`, `pipeline-il.edn` being the one you usually want. An argument that is not readable EDN passes through as a symbol, so write `(deref a)` rather than `@a`.

**`bb prepl-server`** and **`bb prepl-eval`** answer the other question: pipeline shows how a form compiles, a prepl shows what it does. The server is MAGIC Clojure (`clojure.core.server/io-prepl`) on a warm runtime, the client is plain babashka, so each eval is fast.

```bash
bb prepl-server                   # blocks; 127.0.0.1:5555, override: bb prepl-server 5560

# from another shell
bb prepl-eval '(+ 1 2)'           #=> {:tag :ret, :val "3", :ns "user", :ms 1.3, ...}
bb prepl-eval '(def answer 42)'   # global defs persist across calls
bb prepl-eval '(/ 1 0)'           #=> {:tag :ret, :exception true, :val "{... :cause \"Divide by zero\"}"}
```

Replies are tagged `:ret` (with `:ms` timing), `:out` and `:err`, and `:tap`. The form goes straight to the socket without `nos` argument parsing, so `@a` works here. Mono only, since a prepl evaluates at run time.

**`bb repl`** is the Nostrand CLI REPL in `magic-compiler/`. For iterating on compiler `.clj` files, `(require '... :reload)` is seconds where two bootstrap passes are minutes.

## The Editor-runtime regression

```bash
bb coexist-noise              # both Editor states, the upgrade path, the toggle
bb coexist-noise clojure-clr  # just the default state, plus those checks
bb coexist-noise magic        # just the opted-in state, plus those checks
```

Drives [`magic-unity-coexist`](../unity-examples/magic-unity-coexist) headless on Unity `2022.3.62f3`; what each check asserts and how to read a run is in [its README](../unity-examples/magic-unity-coexist/README.md).

## MSBuild targets underneath

`Magic.csproj` holds the targets the `bb` tasks call. Reach for one directly only for the narrow job it names, remembering that a raw `dotnet build` skips the mtime stamping.

| Target | What it does |
|---|---|
| `dotnet build -t:Nostrand` | build the task runner only |
| `dotnet build -t:Magic` | bootstrap the compiler into `magic-compiler/bootstrap/` (needs mono), or nothing if that directory exists |
| `dotnet build -t:Bootstrap` | copy those DLLs into `nostrand/references/`, rebuild the host |
| `dotnet build -t:MagicUnity` | deploy the runtime and stdlib into the Unity package |
| `dotnet build -t:Clean` | remove build artifacts |

With no target, MSBuild runs the project's `DefaultTargets`, `All`: `Clean`, then `Magic`, `Bootstrap` and `MagicUnity`, with `Nostrand` pulled in as a dependency of `Magic`.

## Release

```bash
bb outdated         # GitHub Actions via antq, NuGet via dotnet list package
bb verify-dist      # every shipped artifact is present and `nos version` runs
bb release-tarball  # build, stage bin/Release/net471, tar, extract it again and run nos version
bb tag              # verify-dist, then tag from version.edn and push
```

`bb tag` pushes and the release workflow takes it from there. Nothing else here touches the remote.
