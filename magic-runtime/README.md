# Magic.Runtime

The C# runtime that MAGIC-compiled assemblies call into for dynamic dispatch.

Two projects:

- `Magic.Runtime/` is the runtime proper. Compiles to `Magic.Runtime.dll`, which compiled `.clj.dll`s reference by name. `Dispatch` resolves zero-arity members, instance methods, static methods, and constructors at call time when MAGIC could not statically bind them. `Binder` and `Emission` back the call-site cache that the compiler emits inline. `Runtime` exposes entry points used at host load time (`InvokeInitType`, `TryLoadInitType`, `FindType`).
- `Magic.Runtime.Callsites/` is a build-time code generator (a standalone .NET program, not loaded at runtime). It reads the `*.mustache` templates in that directory and writes per-arity call-site, cache, and delegate-helper classes into `Magic.Runtime/Generated/*.g.cs` for arities 1 through 20. The compiler emits IL that targets those generated types directly, so editing a template requires regenerating the `.g.cs` files before the runtime can be rebuilt.

## When to rebuild

| You changed | Run |
|---|---|
| `Magic.Runtime/**/*.cs` | `bb dev-runtime` |
| `Magic.Runtime.Callsites/*.mustache` | `bb dev-callsites` (regenerates `.g.cs` first, then rebuilds the runtime) |

See the top-level [README](../README.md) for the full development workflow.

`bb check-drift` fails CI if `.g.cs` files are stale relative to the mustache sources, so regenerated output must be committed alongside template edits.

## Loading

Compiled `.clj.dll`s reference `Magic.Runtime.dll` by name. New runtime bodies are picked up at load time, no compiler rebootstrap needed. This is why `bb dev-runtime` is the fast iteration loop (seconds) and `bb dev-compiler` is the slow one (minutes).

## Legal

Copyright (c) 2017-2023 Ramsey Nasser and contributors. Licensed under the Apache License, Version 2.0.
