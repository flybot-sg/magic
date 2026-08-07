# Magic.Runtime

The C# runtime that MAGIC-compiled assemblies call into for dynamic dispatch.

It is a separate assembly from [clojure-runtime](../clojure-runtime) by design: keeping new functionality out of the ClojureCLR fork makes ingesting upstream updates easier, so anything new MAGIC needs lands here instead.

Two projects:

- `Magic.Runtime/` is the runtime proper. Compiles to `Magic.Runtime.dll`, which compiled `.clj.dll`s reference by name. `Dispatch` resolves zero-arity members, instance methods, static methods, and constructors at call time when MAGIC could not statically bind them. `Binder` and `Emission` back the call-site cache that the compiler emits inline. `Runtime` exposes the entry points compiled code and the host call into: `InvokeInitType`, `TryLoadInitType`, `FindType`, and `FindTypeOrThrow`, which a deferred `:import` emits so an unresolvable type fails at load with a hint naming the DLLs on the load path that match its namespace.
- `Magic.Runtime.Callsites/` is a build-time code generator (a standalone .NET program, not loaded at runtime). It reads the `*.mustache` templates in that directory and writes per-arity call-site, cache, and delegate-helper classes into `Magic.Runtime/Generated/*.g.cs` for arities 1 through 20. The compiler emits IL that targets those generated types directly, so editing a template requires regenerating the `.g.cs` files before the runtime can be rebuilt.

## When to rebuild

| You changed | Run |
|---|---|
| `Magic.Runtime/**/*.cs` | `bb build-runtime` |
| `Magic.Runtime.Callsites/*.mustache` | `bb dev-callsites` (regenerates `.g.cs` first, then rebuilds the runtime) |

See [the development guide](../docs/development.md) for the full workflow.

`bb check-drift` fails CI if `.g.cs` files are stale relative to the mustache sources, so regenerated output must be committed alongside template edits.

## Loading

Compiled `.clj.dll`s reference `Magic.Runtime.dll` by name. New runtime bodies are picked up at load time, no compiler rebootstrap needed. This is why `bb build-runtime` is the fast iteration loop (seconds) and `bb dev-compiler` is the slow one (minutes).

## Legal

Copyright © 2017-2023 Ramsey Nasser and contributors.
Copyright © 2026 Flybot Pte. Ltd.

Licensed under the Apache License, Version 2.0.
