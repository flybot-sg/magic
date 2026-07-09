# Declaring CLR dependencies

A library often needs different deps on the JVM and the CLR, and `deps.edn` (plain EDN) has no reader conditionals to say so. `nos` supports two ways to carry the CLR-specific deps. [Which one to pick](#which-to-use) comes down to who else builds the library.

## `deps-clr.edn`: a separate CLR deps file

A self-contained file at the project root (its own `:paths`, `:deps`, `:aliases`). `nos` reads it in place of `deps.edn` when present, and so does [ClojureCLR](https://github.com/clojure/clojure-clr)'s `cljr` (`deps-clr.edn` first, else `deps.edn`; see [clr.core.cli](https://github.com/clojure/clr.core.cli)). The JVM `deps.edn` stays untouched. It is ClojureCLR's own convention, so a library that uses it lines up with the wider CLR community's tooling.

```clojure
;; deps-clr.edn
{:paths ["src"]
 :deps  {org.clojure/test.check {:git/url "https://github.com/flybot-sg/clr.test.check"
                                 :git/sha "..."}}
 :aliases {:test {:extra-paths ["test"]}}}
```

`nos` and `cljr` read one file or the other, never a merge, so it restates every coordinate shared by both runtimes.

## A `:clr` alias in `deps.edn`

One `deps.edn` with a `:clr` alias that adds or swaps CLR deps via `:extra-deps`/`:override-deps`, activated with `:nos/aliases [:clr]` (or per build/test run via `magic.edn`'s `:aliases`). `nos` skips Maven coords (a JVM-only dep in `:deps` never resolves), and `:override-deps` swaps a fork wherever the lib appears, transitive deps included. The alias states only the JVM-to-CLR delta, so shared coordinates are declared once. `cljr` does not read it.

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

## Which to use

The deciding question: does anything other than `nos` build this library?

- **Public CLR library**: use `deps-clr.edn`. Its users build with stock ClojureCLR's `cljr`, which expects that file.
- **Internal, `nos`-only library**: use a `:clr` alias. Nothing else reads the deps, so a separate file only adds cost.

| | `deps-clr.edn` | `:clr` alias |
|---|---|---|
| Read by | `nos` **and** `cljr` | `nos` only |
| Shared deps | repeated in two files | declared once |
| Drift risk | SHAs can fall out of sync | none |
| Best for | public CLR libraries | internal, `nos`-only libraries |

The main cost of `deps-clr.edn` is drift: a git dep used by both runtimes has its SHA in two files, so it is easy to bump one and forget the other. Default to a `:clr` alias while the library is internal, and switch when it goes public. The switch is a small mechanical edit.

## How `nos` chooses

`deps-clr.edn` if present at the project root, else `deps.edn`. Project-root only: transitive git and local deps still read their own `deps.edn`. The `nos`-specific keys `:nos/aliases` and `:nos/submodule-paths` are read from whichever file `nos` uses, so move them into `deps-clr.edn` if you add one.

## See also

[Porting a Clojure library to MAGIC](./porting-libraries-to-magic.md) for the surrounding workflow.
