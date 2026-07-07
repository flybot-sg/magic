# Declaring CLR dependencies

A library often needs different dependencies on the JVM and the CLR. `deps.edn` is EDN, so it cannot use reader conditionals to express that. There are two ways to carry the CLR-specific deps, and `nos` reads both.

## The ClojureCLR way: `deps-clr.edn` (recommended)

A separate file at the project root. `nos` reads it in place of `deps.edn` when it exists, and so does [ClojureCLR](https://github.com/clojure/clojure-clr)'s `cljr`, which reads `deps-clr.edn` first and falls back to `deps.edn` (see [clr.core.cli](https://github.com/clojure/clr.core.cli)). It is self-contained (its own `:paths` and `:deps`), and the JVM `deps.edn` stays untouched. This is ClojureCLR's own convention, so a library that uses it lines up with the wider CLR community's tooling and examples.

```clojure
;; deps-clr.edn
{:paths ["src"]
 :deps  {org.clojure/test.check {:git/url "https://github.com/flybot-sg/clr.test.check"
                                 :git/sha "..."}}
 :aliases {:test {:extra-paths ["test"]}}}
```

## The old way: a `:clr` alias

One `deps.edn` with a `:clr` alias that adds or swaps deps for the CLR via `:extra-deps` and `:override-deps`, activated with `:nos/aliases [:clr]`. `nos` skips Maven coords, so a JVM dep left in `:deps` never resolves, and `:override-deps` points at the CLR fork wherever the lib appears, transitive deps included. Still supported: libraries already ported this way build unchanged. It suits CLR-native projects that are not published for the JVM.

```clojure
;; deps.edn
{:paths ["src"]
 :deps  {org.clojure/test.check {:mvn/version "1.1.1"}}
 :aliases
 {:clr  {:override-deps
         {org.clojure/test.check {:git/url "https://github.com/flybot-sg/clr.test.check"
                                  :git/sha "..."}}}
  :test {:extra-paths ["test"]}}}
```

The `:clr` alias predates adopting the `deps-clr.edn` convention, so some libraries still use it.

## How `nos` chooses

`nos` uses `deps-clr.edn` if it exists in the project root, else `deps.edn`. The redirect is project-root only; transitive git and local deps still read their own `deps.edn`. The `nos`-specific keys `:nos/aliases` and `:nos/submodule-paths` are read from whichever file `nos` uses, so if you add a `deps-clr.edn`, move those keys into it.

## See also

[Porting a Clojure library to MAGIC](./porting-libraries-to-magic.md) for the surrounding workflow.
