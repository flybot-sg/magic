# In-process nostrand probe pack

`bb nostrand-probes` (or `bash run.sh`). Requires `csc` (Roslyn) and `mono`; no
Unity and no JVM. The runtime under test is the DLL set this package ships, so
run `bb build` first — the DLLs are tracked, and their absence is a failure, not
a skip.

What it covers is `magic-unity/Editor/NostrandHost/NostrandHost.cs`, compiled
from source here. The Unity-facing half (`Editor/NostrandHost/Unity/`) is not:
it references `UnityEditor`. `Nostrand.dll` is linked with `-r:` as shipped.
The fixture under `fixture/` is local and deps-free on purpose — a `deps.edn`
would put the network on the path of a regression test.

| Probe | Asserts |
|---|---|
| 1-2 | Cold boot of the shipped set evaluates `(+ 1 2)`; `nostrand/core` and `nostrand/tasks` load from the `nostrand.*.clj.dll` in `Editor/Compiler` (one missing, or one compiled against the CLI exe rather than `Nostrand.dll`, fails here), and `nostrand.repl` stays out of the domain until a repl task asks for it |
| 3 | `Run` dispatches against a consumer load path, requiring the fixture namespaces from source, with both shipped directories on the assembly path |
| 4 | `Nostrand.FindFunction` in all three shapes: qualified `ns/var`, a bare name from `nostrand.tasks`, a bare name from `clojure.core` |
| 5 | `Eval` of an `in-ns` form works and the same eval outside the task bindings throws, which is what the frame is for; the redefinition reaches an already-loaded call site (the fixture is loaded without `production-flags`' direct linking, or this would hold only by accident); the frame pops; a second `Prewarm` is a no-op rather than a second boot |
| 6 | **`*load-paths*` is the same length after every call** — the regression for [#182](https://github.com/flybot-sg/magic/issues/182), where `nostrand.core/update-load-path` concatenated and every call left the previous call's roots in front of the runtime's. The host has no guard of its own here; it relies on `set-load-path` deriving the var from the roots and a fixed base |
| 7 | **Two compiles in one process with a dependency edited in between** — the regression for the `*loaded-libs*` rebinding. Without it `load-lib` skips the already-required dependency, so it is never re-read, its DLL is never written, and the build reports success anyway. Also asserts the task's stdout reached the logger, which needs `*out*` bound and not just `Console.Out` redirected |
| 8 | The emitted DLLs are loaded in a **second** mono process and return the edited body. The compiling domain holds the new definitions in memory either way, so this is the only place the distinction is visible |

Timing is printed, never asserted.

Probes 6 and 7 are the two worth re-running by hand after any change to the
per-call setup. Measured negative controls:

- `update-load-path` concatenating, as before #182 → Probe 6 reads
  `counts=10,12,14` instead of `3,3,3`.
- no `*loaded-libs*` rebinding → Probe 7 fails at "first compile wrote probe.b's
  DLL", because Probe 3 already put `probe.b` in the set.

A logger that writes to `Console.Out` recurses into the host's own writer;
`LineLogWriter` guards against it, and the probe's logger holds the real stdout
captured before the first call.
