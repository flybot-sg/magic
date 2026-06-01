# Clojure.Runtime

A fork of [ClojureCLR](https://github.com/clojure/clojure-clr) used by MAGIC as its data-structure and reader runtime.

## What it provides

- Persistent collections (`PersistentVector`, `PersistentHashMap`, `PersistentList`, ...)
- `Var`, `Symbol`, `Keyword`, `Namespace`
- The Clojure reader (`LispReader`)
- `RT` static helpers used by every compiled namespace

Compiled `.clj.dll`s reference this assembly as `Clojure.dll`. New bodies are picked up at load time, no compiler rebootstrap needed.

## Relationship to upstream ClojureCLR

ClojureCLR is maintained by [David Miller](https://github.com/dmiller) and contributors. This directory is a fork imported into the monorepo with full author/date history, so David Miller and other ClojureCLR contributors appear in the GitHub contributors view.

Key divergence from upstream: **the DLR-based dispatch paths are disabled in this fork.** Upstream ClojureCLR uses Microsoft's Dynamic Language Runtime (`Microsoft.Scripting.*`, `System.Dynamic.IDynamicMetaObjectProvider`) on the call/invocation hot path; the IL2CPP transpiler cannot ship the DLR, which is why upstream ClojureCLR does not target it. In this directory the `IDynamicMetaObjectProvider` implementations on `AFn` and related types are commented out, so the assembly does not pull `Microsoft.Scripting` in at runtime. MAGIC's own call-site infrastructure lives in [`magic-runtime`](../magic-runtime); MAGIC-compiled code dispatches through it rather than through the DLR.

`readme.txt` is the legacy upstream readme, kept for provenance.

## Legal

ClojureCLR is Copyright (c) Rich Hickey, licensed under the [Eclipse Public License 1.0](./epl-v10.html). The MAGIC fork inherits the same license.
