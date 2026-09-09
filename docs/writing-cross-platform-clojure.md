# Writing cross-platform Clojure

One `.cljc` library can serve the JVM, JS and the CLR.

They can therefore compile with both ClojureCLR and MAGIC. However, **the two CLR runtimes are not at the same Clojure version.**

[ClojureCLR](https://github.com/clojure/clojure-clr) follows mainline Clojure releases closely, so it compiles more or less any codebase. MAGIC is at 1.10.

So a green `cljr` run might not be green on `nos`. A `parse-long` or an `update-vals` in shared code compiles under ClojureCLR and dies under MAGIC at compile time, on the first `nos build` or `nos test`:

```
Unable to resolve symbol: parse-long (compiling ::)
```

That out of the way, porting to the CLR is mainly a matter of putting reader conditionals around host API differences. The conditionals themselves are covered by the [official guide](https://clojure.org/guides/reader_conditionals). For the build side see [declaring CLR dependencies](./clr-dependency-files.md) and [the `nos` CLI](./nos-cli.md).

## Which extension loads where

| Extension | Loads on |
|---|---|
| `.clj` | JVM. Pure Clojure with no host interop (also compiles under MAGIC/ClojureCLR for legacy reasons) |
| `.cljr` | CLR only. Cannot contain reader conditionals. |
| `.cljc` | Both. Needed as soon as the file has `#?(:clj ... :cljr ...)` reader conditionals. |

When one namespace resolves to more than one of them, both `cljr` and `nos` take the first of `.cljr`, `.cljc`, `.clj`, so a `.cljc` shadows the JVM `.clj` beside it.

That `.clj` at the end is why most CLR-only code in the wild is in the wrong file. `clr.data.json`, `clr.tools.reader` and most other `clr.*` ports are plain `.clj`, and `clr.tools.deps` mixes all three extensions in one tree. It is for legacy reasons: ClojureCLR is from 2009, `.cljc` arrived with Clojure 1.7 in 2015, and `.cljr` only landed in January 2023. Write new code the way the extensions read: `.cljr` when the namespace only ever runs on the CLR, `.cljc` when one file has to serve both platforms. But don't be surprised if you see CLR-only code in `.clj` in the wild.

## Coming from a library that already targets ClojureScript

Quite a few `.cljc` libraries already serves the JVM and ClojureScript. And adding the CLR support is just adding one more `:cljr` reader conditional in theory. In practice we can make use of the `:default` reader conditional for host that behaves the same.

The CLR and the JVM share the `clojure.lang.*` names, so their reader cond can be collapsed to `:default` branch while the `:cljs` handle the JS interop.

That is why [`fun-map`](https://github.com/robertluo/fun-map) reads the way it does. Its branches are almost all `:cljs` against `:default`, and MAGIC takes the `:default` side without the file naming `:cljr` anywhere:

```clojure
#?(:cljs
   (defprotocol IFunMap (-raw-seq [m]))
   :default
   (definterface IFunMap (rawSeq [])))

(deftype CloseableValue [value close-fn]
  #?(:cljs IDeref :default clojure.lang.IDeref)
  #?(:cljs (-deref [_] value)
     :default (deref [_] value))
  Haltable
  (halt! [_] (close-fn))
  #?@(:clj  [java.io.Closeable   (close   [this] (halt! this))]
      :cljr [System.IDisposable  (Dispose [this] (halt! this))]))
```

The last branch needs `#?@`, the splicing form, because `Closeable` and `IDisposable` share neither name nor method: one conditional has to contribute an interface and its implementation, and only `#?@` splices a sequence into the surrounding form.

Two things about `:default` are worth knowing before you rely on it.

**The first matching branch wins, so `:default` goes last.** Written the other way round it swallows the platform you meant to special-case, silently:

```clojure
;; read on the CLR
#?(:cljr :from-cljr :default :from-default)   ; => :from-cljr
#?(:default :from-default :cljr :from-cljr)   ; => :from-default, the :cljr branch is dead
```

**`:default` also means something else inside `catch`.** ClojureScript spells its catch-all `(catch :default e)`, so a portable catch ends up saying the word twice, once as a feature and once as the thing being caught:

```clojure
(catch #?(:cljs :default :default Exception) e ...)
```

Imports are where a real `:cljr` branch tends to survive, because each branch imports what its own code hints with. `fun-map` splits its import list for that reason: its JVM transient type hints the abstract base `ATransientMap`, while the other branch hints the `ITransientMap` interface, so the CLR side never names `ATransientMap` and does not import it.

```clojure
(:import #?(:clj  [clojure.lang IMapEntry IPersistentMap ITransientMap ATransientMap]
            :cljr [clojure.lang IMapEntry IPersistentMap ITransientMap]))
```

Targeting only the JVM and the CLR, `:clj` and `:cljr` are the two branches you need and `:default` earns nothing.

## Type hints are a CLR concern

Hints let MAGIC emit a direct unboxed call instead of reflecting. They are also what `*strongly-typed-invokes*` builds on: the hints on a `defn` are what give it a typed `invokeTyped` surface at all ([compiler flags](./nos-cli.md#compiler-flags)). Keep them in the `:cljr` branch and leave the `:clj` side dynamic.

```clojure
(defn scale
  #?(:clj  [factor n]
     :cljr [^double factor ^long n])
  (* factor n))

(defrecord Point #?(:clj  [x y]
                    :cljr [^long x ^long y]))
```

For a reference type, `require` the namespace that defines it and then import the class. Both clauses are CLR-only here, since the hint is, and both go in `:cljr` branches:

```clojure
(ns my.lib.geometry
  #?(:cljr (:require [my.lib.shapes]))
  #?(:cljr (:import [my.lib.shapes Point])))

(defn translate
  #?(:clj  [p dx dy]
     :cljr [^Point p dx dy])
  (-> p (update :x + dx) (update :y + dy)))
```

The `require` is not optional and its position is not either. `:import` resolves a name against the assemblies already loaded in the process, never against the disk, so the namespace defining `Point` has to be loaded first or the compile fails with `Could not find type my.lib.shapes.Point during import`. Clauses run in written order, which is what makes `:require` first sufficient. It is the same rule a precompiled C# assembly runs into, in its harder form, where nothing loads the assembly for you at all ([C# assemblies](./native-assemblies.md)).

A hint that cannot resolve is a hard error rather than a warning, so a typo or a missing import fails at compile time. Two hints to avoid: a collection's element type, which has no hint syntax at all, and a map's concrete class, which flips between `PersistentArrayMap` and `PersistentHashMap` with size. Hint the interface `clojure.lang.IPersistentMap` instead.

## Host APIs that differ

Examples of interops you might need:

| Need | `:clj` | `:cljr` |
|---|---|---|
| Class name of a value | `(.getName (class x))` | `(.FullName (class x))` |
| Namespace name | `(.getName *ns*)` | `(.Name *ns*)`, not `.FullName` |
| Writer hint | `^java.io.Writer` | `^System.IO.TextWriter` |
| Writer method | `(.write w s)` | `(.Write w s)` |
| Regex hint | `^java.util.regex.Pattern` | `^System.Text.RegularExpressions.Regex` |
| Round a double | `(Math/round ...)` | `(Math/Round ...)` |
| Epoch milliseconds | `(System/currentTimeMillis)` | `(.ToUnixTimeMilliseconds (DateTimeOffset/UtcNow))` |

`Environment/TickCount` is not the CLR answer for the last row, however close it looks. It counts milliseconds since the machine booted, in an `Int32` that wraps roughly every 25 days, so it is a stopwatch and not a clock. Reach for `System.Diagnostics.Stopwatch` when you want elapsed time.

`print-method` is the case you might meet often too:

```clojure
#?(:clj  (defmethod print-method Square [^Square s ^java.io.Writer w]
           (.write w (str "#Square " (:side s))))
   :cljr (defmethod print-method Square [^Square s ^System.IO.TextWriter w]
           (.Write w (str "#Square " (:side s)))))
```

`(catch Exception e ...)` and `(thrown? Exception ...)` need no conditional even though they look like they should: bare `Exception` resolves at compile time on both runtimes.

A `hash`-derived identity carries across runtimes: MAGIC aligned String and map `hasheq` with the JVM, so `(hash "hello")` is 1715862179 on both. Only the formatting call differs, `Integer/toHexString` on the JVM against `(.ToString n "x")` on the CLR, and the lowercase `x` is not optional. `"X"` gives you `FF` where the JVM gives `ff`.

## The stdlib stops at Clojure 1.10

MAGIC ships the Clojure 1.10 surface, right up to its last additions: `ex-message`, `ex-cause`, `tap>`, `read+string`, the 1.10-shape `Throwable->map`, and `prepl` / `io-prepl` / `remote-prepl`.

It stops there. Everything from 1.11 onward is absent: `update-vals`, `parse-long`, `random-uuid` and the rest of the newer core fns, the `clojure.math` namespace, and the 1.11 keyword-arguments calling convention. This is a boundary, not a short list of gaps.

A missing core fn can be shimmed under `:cljr`:

```clojure
#?(:cljr (defn update-vals [m f]
           (persistent!
            (reduce-kv (fn [acc k v] (assoc! acc k (f v))) (transient {}) m))))
```

A change baked into the calling convention cannot be. Read "added in 1.11" in the Clojure changelog as "not in MAGIC", and stay on 1.10 idioms in shared code.

## Linting

clj-kondo models JVM Clojure and has no `:cljr` feature. Asking for one does not degrade, it fails:

```
$ clj-kondo --lint x.cljc          # with {:cljc {:features #{:clj :cljr}}}
x.cljc:0:0: error: Can't parse x.cljc, No matching clause: :cljr
```

Which leaves the two extensions in opposite positions, and each wants a different fix.

**`.cljc` is analysed under `:clj` and `:cljs`.** A `:cljr` branch matches neither, so it is skipped and reports nothing. The noise comes from the `:cljs` pass instead: unconditional code that ClojureScript cannot resolve, most often `(catch Exception e ...)`, and any host type the `:clj` branch imported. If the library has no ClojureScript target, drop that pass:

```clojure
;; .clj-kondo/config.edn
{:cljc {:features #{:clj}}}
```

If it does target ClojureScript, keep both features and put the offending form behind a conditional.

**`.cljr` is analysed as ordinary Clojure**, which is better (real typos get caught) and noisier. Two CLR-isms have no JVM equivalent for clj-kondo to resolve: a static class used without an import reads as a namespace, and the CLR-only `clojure.core` vars are simply unknown.

```
load_dll.cljr:9:22: warning: Unresolved namespace Environment. Are you missing a require?
load_dll.cljr:15:6:  error:   Unresolved symbol: assembly-load-from
```

Silence those per namespace rather than globally, so a genuine typo elsewhere still fails:

```clojure
{:config-in-ns
 {my-lib.load-dll
  {:linters {:unresolved-namespace {:exclude [Environment]}
             :unresolved-symbol    {:exclude [assembly-load-from]}}}}}
```

One asymmetry to know: `clj-kondo --lint src` over a directory skips `.cljr` entirely. Only naming the file lints it, which is what an editor does, so a `.cljr` file only ever gets linted while it is open in front of you.
