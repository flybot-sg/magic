# Loading precompiled native assemblies

A library sometimes mixes Clojure source with C# compiled ahead of time to a plain assembly committed to the repo, say a hand-written parser class built with `csc`. On the JVM the equivalent, `.class` files on a `:paths` directory, just works: the classpath is how types get resolved. The CLR has no classpath for types. `:import` only searches assemblies already loaded in the process; it never loads one. So something must call `assembly-load-from` before the `:import` runs, or the `:import` fails with a missing type.

This is not a MAGIC quirk. ClojureCLR behaves the same way, and its `assembly-load` / `assembly-load-from` / `assembly-load-file` are the official mechanism; there is nothing higher-level, on either `cljr` or `nos`. Every CLR Clojure library that ships a precompiled assembly has to load it explicitly. The only question is where that load lives.

## The recommended pattern: a loader namespace

The official way to load an assembly is `assembly-load-from`, called before the `:import` runs. The common shortcut is to hardcode the DLL's path, but that breaks the moment the library is consumed from another directory. Instead, scan `CLOJURE_LOAD_PATH` (the CLR's load path) for the DLL from a small namespace, and require it from the namespace that does the `:import`. Clauses run in written order, so put the `:require` before the `:import`, and the assembly loads first. This is the plain ClojureCLR pattern, and it works unchanged on MAGIC because `nos` puts the project's `:paths` on `CLOJURE_LOAD_PATH` too:

```clojure
(ns my-lib.load-dll
  "The CLR does not load an assembly at :import; load the compiled DLL
   explicitly. Scan CLOJURE_LOAD_PATH, which carries the project :paths
   on both ClojureCLR and nostrand."
  #?(:cljr (:require [clojure.string :as str]))
  #?(:cljr (:import [System.IO Path File])))

#?(:cljr
   (let [roots (some-> (Environment/GetEnvironmentVariable "CLOJURE_LOAD_PATH")
                       (str/split (re-pattern (str Path/PathSeparator))))]
     (when-let [dll (some (fn [root]
                            (let [p (Path/Combine root "my_lib.dll")]
                              (when (File/Exists p) p)))
                          roots)]
       (assembly-load-from dll))))
```

The importing namespace requires it in the same `ns` form:

```clojure
(ns my-lib.core
  #?(:cljr (:require [my-lib.load-dll]))
  #?(:cljr (:import [my_lib MyParser])))
```

Between libraries this is boilerplate: copy the loader namespace as-is and edit the one filename string. The directory holding the DLL (say `src_classes`) must be on the project's `:paths`, so the scan finds it, and the DLL should be named after the namespace prefix of the types inside it (`my_lib.MyParser` lives in `my_lib.dll`); the compiler's unloaded-DLL diagnostics match on that convention. The diagram below shows the order.

```mermaid
flowchart LR
    req["require<br/>my-lib.core"] --> ld["my-lib.load-dll runs<br/>scans the load path"]
    ld --> found{"DLL on<br/>a load path?"}
    found -->|yes| al["assembly-load-from"] --> imp[":import resolves"]
    found -->|no| pre["already loaded<br/>(Unity plugins)"] --> imp
```

Multiple assemblies compose through the same pattern. A library shipping several DLLs loads each one from its single loader (a `doseq` over the filenames), and libraries compose with no consumer coordination: requiring each library runs its own loader. Loads are idempotent, since `require` runs a loader once per process and `assembly-load-from` on an already-loaded path returns the cached assembly.

## Why the load must be in-band

Keeping the load in the require graph is what makes it work everywhere, with no toolchain support. One placement covers every case:

- `nos build` and `nos test`: the loader runs before anything uses the type.
- ClojureCLR's `cljr`, which puts `:paths` on the same `CLOJURE_LOAD_PATH` the loader scans.
- Consumers pulling the library as a git dep: their require of `my-lib.core` runs the loader in their own process.
- Unity players: the scan finds nothing and the loader is a no-op, which is correct, because assemblies under `Assets/Plugins` are already loaded.

This is also why MAGIC does not load the DLL for you. It could, but a library leaning on that would no longer load under `cljr`, and ClojureCLR is not ours to change. The loader namespace is the one form of the load both runtimes execute, so it stays in the library.

## When the loader is missing

Nothing warns ahead of time; the `:import` itself fails when it runs, and MAGIC's error names the DLL it found on the load path:

```
System.InvalidOperationException: Could not find type my_lib.MyParser during import; found /path/to/my-lib/src_classes/my_lib.dll on the load path but no loaded assembly defines this type, load it before :import, see docs/native-assemblies.md in the MAGIC repo
```

Under `nos build` and `nos test` this surfaces while the namespace compiles; for a consumer it surfaces at `require`. The hint is MAGIC's; `cljr` fails at the same point with its own missing-type error.

## Parity libraries

A library that must build on both ClojureCLR and MAGIC should pair the loader with a self-contained `deps-clr.edn` whose `:paths` include the DLL directory. Both `cljr` and `nos` read that one file and put every `:paths` entry on `CLOJURE_LOAD_PATH`, so the loader's scan finds the assembly on either runtime. See [Declaring CLR dependencies](./clr-dependency-files.md).

## Building the assembly against the runtime

When the C# source references Clojure types, the compiler needs the runtime assemblies. `nos where` prints the directory the running host loaded them from, so a build script never guesses install dirs:

```bash
csc -deterministic -target:library -r:$(nos where Clojure.dll) -out:src_classes/my_interop.dll MyInterop.cs
```

The `-deterministic` flag is what keeps the committed DLL byte-stable: without it, `csc` stamps a random module id, so rebuilding unchanged source modifies the committed file every time. Byte-stability also needs a pinned `AssemblyVersion` (a `1.0.*` wildcard derives the version from the build time) and the same compiler version across rebuilds, so expect a toolchain upgrade to change the bytes once. If the build emits debug info (`-debug:portable`), add `-pathmap:"$PWD"=/src` so the embedded source paths do not tie the bytes to one checkout directory.

## See also

- [Declaring CLR dependencies](./clr-dependency-files.md)
- [Porting a Clojure library to MAGIC](./porting-libraries-to-magic.md)
- [Writing cross-platform Clojure](./writing-cross-platform-clojure.md)
