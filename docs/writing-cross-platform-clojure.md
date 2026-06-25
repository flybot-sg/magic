# Writing cross-platform Clojure (JVM + CLR)

MAGIC runs the same Clojure source the JVM does. A library that compiles for both a backend (JVM) and a Unity client (CLR via MAGIC) is written once, in `.cljc`, with the platform-specific parts gated behind reader conditionals.

This guide is about the *source patterns*: reader conditionals, type hints, host interop, and what differs between the two runtimes. For the *build* side (`deps.edn`, `dotnet.clj`, CI) see [Porting a Clojure library to MAGIC](./porting-libraries-to-magic.md). For why MAGIC exists at all see [Why MAGIC](./why-magic.md).

## File types and reader conditionals

| Extension | Loads on |
|-----------|----------|
| `.clj`  | JVM. Pure Clojure `.clj` (no host interop) also compiles under MAGIC. |
| `.cljr` | CLR only. |
| `.cljc` | Both. Needed once the file has `#?(:clj ... :cljr ...)` branches. |

MAGIC code runs on exactly two platforms, so a conditional needs only `:clj` and `:cljr`; there is no third platform to fall back to, and `:default` is unnecessary.

```clojure
(ns my.lib.core
  #?(:cljr (:import [System.Text.RegularExpressions Regex])))

(defn round [n]
  #?(:clj  (Math/round (double n))
     :cljr (long (Math/Round (double n)))))
```

Reader conditionals are needed in three places: `require`/`import` (different class paths), type hints (CLR performance), and interop calls (different host methods). Everything else is plain portable Clojure. The `#?@` splice form injects a sequence into the surrounding form rather than a single value.

## Portable across ClojureCLR and MAGIC

`:cljr` is the standard CLR reader conditional, not a MAGIC dialect: ClojureCLR (David Miller's .NET port) reads it too. So the same `.cljc` source compiles unchanged on both ClojureCLR and MAGIC, with no MAGIC-specific hacks, as long as it stays within the Clojure 1.10 stdlib (the surface MAGIC ships; see below). For example, [clojure/clr.test.check](https://github.com/clojure/clr.test.check) builds under MAGIC with zero changes from upstream.

## Type hints for CLR performance

Type hints let MAGIC emit direct, unboxed calls instead of reflective dispatch. They are a CLR performance concern, so put them in the `:cljr` branch and keep the `:clj` side dynamic and test-friendly.

Value types on function arguments:

```clojure
(defn scale
  #?(:clj  [factor n]
     :cljr [^double factor ^long n])
  (* factor n))
```

Record fields (the whole field vector is the conditional):

```clojure
(defrecord Point #?(:clj  [x y]
                    :cljr [^long x ^long y]))
```

Reference types, for example a record defined in another namespace used as a parameter: import the class, then hint with it.

```clojure
(ns my.lib.geometry
  #?(:cljr (:import [my.lib.shapes Point])))

(defn translate
  #?(:clj  [p dx dy]
     :cljr [^Point p dx dy])
  (-> p (update :x + dx) (update :y + dy)))
```

A type hint that cannot resolve is a hard error, so a typo or missing import fails fast.

Limitations:

- Collections cannot be hinted with an element type (no "vector of `Point`").
- Maps are not worth hinting; the concrete class varies (`PersistentArrayMap`, `PersistentHashMap`, ...).

## Records, types, and protocols

`defrecord`, `deftype`, `defprotocol`, `reify`, `proxy`, and multimethods work exactly as on the JVM, with no reader conditional. Record `=` is structural, so use `defrecord` when you want value equality; a `deftype` has reference identity. Mutable `deftype` fields (`^:unsynchronized-mutable` + `set!`) work too.

## Host APIs that genuinely differ

These reflect real JVM-versus-CLR API differences and stay in the source forever (unlike the workaround above). Keep the reader conditional.

| Need | `:clj` | `:cljr` |
|------|--------|---------|
| Class name of a value | `(.getName (class x))` | `(.FullName (class x))` |
| Namespace name | `(.getName *ns*)` | `Namespace.Name` (not `.FullName`) |
| Writer type hint | `^java.io.Writer` | `^System.IO.TextWriter` |
| Writer method | `(.write w s)` | `(.Write w s)` |
| Regex type hint | `^java.util.regex.Pattern` | `^System.Text.RegularExpressions.Regex` |
| Round a double | `(Math/round ...)` | `(Math/Round ...)` |
| Wall-clock millis | `(System/currentTimeMillis)` | `(Environment/TickCount)` |

`print-method` is the everyday case:

```clojure
#?(:clj  (defmethod print-method Square [^Square s ^java.io.Writer w]
           (.write w (str "#Square " (:side s))))
   :cljr (defmethod print-method Square [^Square s ^System.IO.TextWriter w]
           (.Write w (str "#Square " (:side s)))))
```

Portable without a conditional:

- `(catch Exception e ...)` and `(thrown? Exception ...)`: bare `Exception` resolves at compile time on both runtimes.
- Prefer a stdlib fn over a hand-copied `:cljr` variant. MAGIC's stdlib already uses the CLR-correct host calls (for example `clojure.datafy/datafy`), so call it rather than copying it to swap `.getName` for `.FullName`.

A `hash`-derived identity needs care. A value computed from `hash` (a hex id, say) is self-consistent within one runtime, and MAGIC aligned String and map `hasheq` with the JVM so the values match across runtimes too. But if you persist such an id on one runtime and compare it on the other, format the hex the same way: `Integer/toHexString` on the JVM, `(.ToString n "x")` with a lowercase `x` on the CLR.

## Standard library: MAGIC is at Clojure 1.10

MAGIC's stdlib is at Clojure 1.10 parity. The full marked-1.10 surface is on the CLR, including `ex-message` / `ex-cause`, `tap>`, `read+string`, the 1.10-shape `Throwable->map`, and `prepl` / `io-prepl` / `remote-prepl`. Most of `clojure.core`, `clojure.string`, `clojure.set`, `clojure.spec.alpha`, protocols, records, and multimethods work without conditionals.

MAGIC targets Clojure 1.10. Mainline Clojure is now at 1.12, so everything added in 1.11 and 1.12 is unavailable: new `clojure.core` fns (`update-vals`, `parse-long`, `random-uuid`, ...), the `clojure.math` namespace, and a new keyword-arguments calling convention, among others. This is a real boundary, not a couple of functions.

An individual missing core fn can be shimmed under `:cljr`:

```clojure
#?(:cljr (defn update-vals [m f]
           (persistent!
            (reduce-kv (fn [acc k v] (assoc! acc k (f v))) (transient {}) m))))
```

Changes baked into the calling convention or the compiler (the 1.11 trailing-map keyword arguments, for one) cannot be shimmed. Keep cross-platform code on 1.10 idioms, and treat "added in 1.11" in the Clojure changelog as "not in MAGIC".

## Linting

clj-kondo runs on the JVM, so it cannot resolve two kinds of symbol in a `.cljc` file and flags them as lint noise, not real errors:

1. Symbols inside a `:cljr` branch (`System.*` types, CLR-only methods).
2. Bare host symbols used unconditionally, notably `Exception` in `(catch Exception e)` / `(thrown? Exception ...)`.

Default clj-kondo to the `:clj` feature set so CLR-only branches grey out instead of erroring:

```clojure
;; .clj-kondo/config.edn
{:cljc {:features #{:clj}}}
```

## See also

- [Porting a Clojure library to MAGIC](./porting-libraries-to-magic.md): the build side (`deps.edn`, `dotnet.clj`, CI, RCT on the CLR).
- [Unity integration](./unity-integration.md): compiling `.clj.dll` and loading them in Unity.
- [Why MAGIC](./why-magic.md): why the CLR needs a dedicated static compiler.
- [`magic-compiler/src/magic/flags.clj`](../magic-compiler/src/magic/flags.clj): the compiler flags. Production builds set `*direct-linking*` and `*strongly-typed-invokes*`.
