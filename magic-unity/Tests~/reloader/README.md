# Reloader evidence pack

Measured proof that the problems the hot reloader in [`../../Editor/Reload/`](../../Editor/Reload)
exists for — background-thread eval and the lack of debounce — are real (Probes 1–2),
**plus** regression tests that assert the fix and its contracts (Probes 3–7) — all
produced outside Unity so they are repeatable. Run everything from the repo root:

```bash
bb reloader-probes   # exit 0 = problems reproduced AND fixes verified
```

Requirements: `csc` (Roslyn), `mono`. No Unity, no JVM: the probes that need a Clojure
runtime use the ClojureCLR the package ships in `Runtime/clojure-clr/`. FileSystemWatcher
behaviour is platform-specific — everything below was measured on macOS with Mono's
default backend — so the pack is run by hand, not in CI.

### The settle window

`DebouncedFileWatcher` has two constructors. The Editor only ever uses the two-argument
one, which fixes the window at the `DefaultSettleMs` const (200 ms). The pack uses the
three-argument one, whose `settleMs` is a **test seam**: a probe that runs many rounds
would otherwise spend all its time asleep. Six call sites take it —
`DisposeTest.cs:50,78,97` and `ReloaderQueueTest.cs:40` pass `150`,
`WatcherContractTest.cs:186,234` pass `50`, and each is paired with waits tuned just past
its own window. Change a window and the waits beside it move too; at 200 ms throughout,
`LostUpdateOnClaim` alone goes from ~20 s of sleeps to ~65 s.

Nothing in the pack needs editing to build. If a call site ever fails to compile against
the two-argument form, the seam has been removed rather than the probe gone stale.

**This is the out-of-Unity tier.** It proves the FSW environment and the
`DebouncedFileWatcher`/`ClojureReloader` C# source in isolation, compiled from the
real files (single source of truth). The complementary in-editor tier — the same claims
verified in a running Unity Play session (real source roots, Unity-main-thread eval,
reload latency, UI re-render) — lives with the consumer that wires `Poll()` into its
game loop (`hot-reload-verify/` in Basecity's `integrated`). Neither tier substitutes
for the other.

## What each probe shows (measured 2026-07-16, macOS, default Mono FSW backend)

### `WatchProbe.cs` — FileSystemWatcher behavior under the reloader's exact config

Two watchers on one directory: `[reloader-cfg]` replicates the **pre-v2** reloader
config (`NotifyFilters.LastWrite`, `Filter "*.clj*"`, recursive) — the probe keeps
that old config deliberately, to measure the FSW environment the fix had to overcome.
(The shipped reloader now watches with `LastWrite|FileName` + `Filter "*"` via
`DebouncedFileWatcher`; Probe 3 checks the fixed behavior.) `[all-filters]` is a
control that sees everything the backend can deliver. Findings:

- **Duplicate events (#4)**: a vim/Neovim-style save (rename original away, write
  a new file at the path) delivers exactly **2 `Changed` events**, 10/10 across 5
  saves, the pair within the same millisecond. Each event triggered a full
  snapshot + eval in the pre-v2 design; the v2 reloader coalesces them (Probe 3 [A]).
- **Thread identity (#1a)**: main thread is `tid=1`; every callback arrived on an
  arbitrary ThreadPool thread, and **the two events of a single save arrive on
  two different threads** — two evals of the same file can race each other, not
  just the main thread.
- **Save-style matrix (#7)**: in-place overwrite → `Changed` (fires);
  vim-style rename-away + rewrite-at-path → `Changed` ×2 (fires, inode changes);
  temp-file renamed **over** the target (VS Code-style safe write) → **zero
  events** under the pre-v2 config — silent no-reload. The v2 `Filter "*"` +
  in-handler gate fixes this (Probe 3 [B] confirms delivery on this backend).

### `race_torn_reload.clj` — torn multi-var states (#1b)

A file defines `k1` and `k2` that must always agree, separated by 30 filler
forms (standing in for a real file where related defs are far apart —
integrated's biggest watched `.cljc` is 2,213 lines). A reader thread spins on
`(= k1 k2)` while the main thread re-evals the file 100 times, replicating the
pre-v2 watcher-thread eval racing the Unity main thread's `dispatch!`/`render!`
reads (v2 moves the eval onto the main thread, closing this race by construction).

`TornRaceHost.cs` boots the shipped ClojureCLR and `load-file`s the script with `*ns*`
bound to `user`, as `clojure.main` would — without that binding the script's top-level
`declare` lands in `clojure.core` while its `load-string` bodies `in-ns` into `user`, and
the reader compares two unbound vars, which read as torn on every sample (measured
`torn-observations = reader-samples` exactly, a false positive).

Measured on ClojureCLR 1.11.0 (2026-09-04, three runs):
`reloads=100 reader-samples≈11.6M torn-observations≈11.1M` — **100% of reloads
observed torn; ~96% of all reads during the run saw the inconsistent state**. The
JVM run it replaced (2026-07-16) measured ~90%. The window is the whole eval
duration and scales with file size. The script itself is plain Clojure and still runs
as `clojure -M race_torn_reload.clj` on the JVM.

### `ReloaderQueueTest.cs` — asserts the v2 fix (#1/#4/#7)

Compiled against the **real** `DebouncedFileWatcher.cs` (single source of truth), so
it drives the shipped debounce/drain logic rather than a copy. Two tests, both
poll-until-quiet (no fixed sleeps that assume FSEvents latency), no thread-id
assertions (single-threaded here, they'd only test the harness):

- **[A] coalescing (#1/#4)**: 5 vim-style saves, drained one at a time via `TakeSettled()`
  → each save yields **exactly one** result (`[1,1,1,1,1]`, total 5). The old design
  evaluated every raw event (~2 per save).
- **[B] rename-over delivery (#7)**: one temp-file-renamed-over-target save using a
  **non-`.clj` temp name** (`probe.tmp` → `probe.cljc`) → the target appears
  **exactly once**. This is the acceptance test for the `Filter="*"` + in-handler
  gate decision: Probe 1 measured **zero** events for this save style under the old
  `Filter="*.clj*"` config. Measured green on this Mono/macOS backend.

- **[C] gate held**: any `TakeSettled()` result that is not the target (a leaked
  `probe.cljc~` backup or `.tmp` file) fails the probe — the extension gate is
  asserted, not just exercised.

Probe 3 prints `PROBE3-FIX-VERIFIED` / exit 0 on success, and **fails if the fix
regresses** (e.g. the settle window removed → duplicate results; the wide filter
narrowed → rename-over missed again).

### `DisposeTest.cs` — Probe 4: Dispose/finalizer contract of `DebouncedFileWatcher`

Reflection + behavior, against the real source: **[C1]** no finalizer declared
(guards `470ea42`); **[C2]** `Dispose()` deterministically releases the
`FileSystemWatcher` (a save after Dispose yields zero `TakeSettled()` results); **[C3]**
`Dispose()` is idempotent; **[C4]** `AddRoot()` after Dispose throws
`ObjectDisposedException` (guards `b3026df`); **[C5]** a concurrent AddRoot/Dispose
race throws nothing but `ObjectDisposedException` and leaves no armed watcher
(validates the `_rootsLock`-guarded `_disposed` check).

### `ReloaderFinalizerCheck.cs` — Probe 5: `ClojureReloader` contract

Links `Runtime/clojure-clr/Clojure.dll` but boots nothing; the DLL is only needed to
resolve the `Compiler.load` callsite. **[F]** `ClojureReloader` declares no finalizer
(guards `e3fc488`); **[L3]** constructing with an empty roots list logs
`No clj source root. Reload disabled.` and leaves `_watcher` null, so the following
`Poll()` cannot fire. The roots are the host's argument (in integrated,
`CljSourceRoots.Installed`), so the constructor can be reached with nothing to watch;
log-and-return is the chosen posture, because a missing root is an environment problem
and the host's boot step does not survive an exception. Both observables are asserted: the
log alone would pass for a reloader that logged the line and then armed a rootless
watcher. **[L]** lifecycle null-safety on that same inert instance — `Poll()` with nothing
armed, double `Dispose()`, `Poll()` after `Dispose()` all no-op without throwing.
**[L4]** is commented out — see [Unresolved](#unresolved). Re-arming is not a contract —
the reloader is constructed armed or inert and disposed once — so there is no
`Init`-after-`Dispose` case here (that was **[L2]**, retired 2026-09-04).

### `WatcherContractTest.cs` — Probe 6: behavioral contract of `DebouncedFileWatcher`

Everything here was previously verified only by code review:

- **[D1–D3] root dedupe & segment-aware covering** (guards the #8 fix —
  integrated's `reload.edn` maps six mj/zp namespaces to one directory):
  child-then-parent (shallower root replaces the covered one), parent-then-child
  (covered root skipped), and the sibling-prefix case `foo/bar` vs `foo/barbaz`
  (naive string-prefix covering would swallow the sibling; a save under it would
  silently stop hot-reloading).
- **[Q1–Q5] gone (2026-09-01, second pass)**: `Requeue` was removed, so there is no
  requeue contract to test. Retry queueing lives wholly in `ClojureReloader` now, which
  leaves the watcher one write door — the OS event stream — and makes the property the [Q]
  checks really protected (*no retry cap here*) structural rather than asserted. The
  consumer-side budget is Probe 7's [E5]/[E7]; the accept gate [Q3] guarded has only one
  caller left to guard.
- **[S1] coalescing/liveness STRESS**: 60 rapid in-place writes → the final version is
  always delivered after quiescence (measured: coalesced to **one** delivery). Note it
  does *not* discriminate `TakeSettled()`'s conditional remove — see the mutation results
  below; macOS FSW latency keeps real events well clear of the microsecond claim window.
  That property is [S2]'s job.
- **[S2] no-lost-update on a raced claim**: fills the table with 4000 entries to widen
  the enumerate→sort→remove window, then fires one mark from another thread against
  an in-flight drain, 150 rounds. The re-marked path must always come back. Table-only
  (no roots armed), so it tests the claim protocol rather than FSW.
- **[P1] `IsPending`'s mark-to-hand-out interval**: false before a mark, true from the
  mark until `TakeSettled()` removes it — *including* inside the settle window, which is
  exactly where the reloader's retry-supersede check needs a true — and false again after
  the hand-out. A gate-rejected path never reads pending. Table-only, like [S2]: FSW adds
  nothing to a `ContainsKey` contract. The reloader-side half (the due retry actually
  being dropped) is Probe 7's [E9].
- **[GATE]** cross-cutting: `TakeSettled()` never returned a path outside each test's
  expected targets.

**Mutation-checked** (2026-09-01): in throwaway copies of `DebouncedFileWatcher.cs` —
making `TakeSettled()`'s claim an unconditional `TryRemove` fails only [S2]
(`re-mark lost on 13/150 raced claims`), and [S1] passes it **6 runs out of 6**, which is
why [S2] exists and why [S1] is no longer described as guarding that property. (The second
mutation, `Requeue` bypassing `Mark`'s gate, went out with `Requeue` itself.)

Since [S2] arms no roots, and real FSW latency is what keeps events clear of the claim
window it targets, it now marks through the private `Mark` by reflection — with `Requeue`
gone, the table has no public write door, and giving it one *for the test* would put back
the API this change removed.

### `ReloaderEvalTest.cs` — Probe 7: `ClojureReloader`'s eval path

The regression probe that **boots** the runtime (`RT.Init`) and evals real forms, so
`run.sh` stages the whole `clojure-clr` directory beside it, not just `Clojure.dll`.
Probes 3/4/6 stop at the watcher boundary and Probe 5 covers only lifecycle
null-safety, so until this probe the reloader's entire reason for existing had no test.

- **[E1] a save reloads, and a re-save redefines**: `probe.a/v` takes the file's value,
  then a second save changes it. The second half is the load-bearing one — a reloader
  that only ever first-loads a file passes the first assertion.
- **[E2] eval failure retires the retry budget**: unbalanced parens → `Compiler.load`
  throws → no `_retries` entry — and no straggler in `_pending` — survives for that path.
  The budget exists for *read* races; source that will never compile is terminal for that
  save. Asserted by convergence rather than one sample, so a trailing event from the same
  save cannot re-Mark the path and read as a failure. Weak on its own — a file that reads
  cleanly never opens a `_retries` entry, so there is nothing for the cleanup to remove.
- **[E2b] the cleanup, where it is actually load-bearing**: the file is deleted first, so
  the read fails and *does* open a `_retries` entry, then recreated with uncompilable
  source — the next attempt gets past the read and dies in `Compiler.load`. That entry must
  be gone, or the path carries a stale attempt count into the next save.
- **[E3] a throwing `onChanged` cannot escape `Poll()`**: the failure is logged, the
  reload still stands, and nothing propagates. The host calls `Poll()` from its main
  tick — in integrated `RunWorkLoop`, which also drives event dispatch — so an escaping
  exception would silently kill the game loop.
- **[E4] `.cljc` reader conditionals pick `:cljr`**: pins the argument shape of the
  `Compiler.load` call, since passing the real file name is what enables read-cond.

- **[E5] a *read* failure retries, then gives up at exactly the cap**: [E2] covers the
  *eval* failure, leaving `Poll()`'s
  `catch when (ex is IOException || ex is UnauthorizedAccessException)` — the read branch —
  untested. The probe marks a file, deletes it, then polls: `FileNotFoundException` is an
  `IOException`, so the retry branch is reached deterministically instead of by racing a
  real mid-write read. Asserts the deferral, an **exact** `MaxReadAttempts - 1` deferrals
  before the terminal give-up (attempts are countable now that the budget is not a clock),
  that no bookkeeping survives, and that nothing escaped `Poll()` — narrowing that filter
  turns a transient read error into a killed host tick, the failure [E3] guards on the
  callback side. The cap is read by reflection, not hardcoded, so retuning it cannot
  silently invalidate the count.
- **[E7] a fresh file event resets the read budget** — the starvation fix, back under test.
  Drives the budget to its last attempt, then runs the reported scenario: a burst of saves
  ~80 ms apart, each re-stamping the settle window so the path never settles and *no retry
  can be attempted while the burst runs*. Two properties. The burst must spend **zero**
  budget (a wall-clock window is spent by the writer instead, and ~800 ms of writing
  exhausts a 1 s one); and the hand-out that follows must start over at attempt 1, so a
  save that fails one read is not mistaken for a file nobody can read. Staging matters:
  the burst is allowed to settle *before* the next `Poll`, because `Poll` drains settled
  paths ahead of due retries — let the stale retry go first and it exhausts its own
  generation, the give-up clears the entry, and the reset under test becomes a no-op that
  passes for the wrong reason. This test existed once as [R1]–[R5b] ("fresh-event reset")
  and was deleted with the watcher-side budget on 2026-09-01 — the same change that dropped
  the reset it guarded.
- **[E8] a retry whose read *succeeds* retires the budget**: the ordinary rename-replace
  recovery, where the file becomes readable again with no further FS event. That cannot be
  staged through the watcher — making the file readable is itself an event — so the entry
  is injected directly and the file lives outside the watched root: no FSW, fully
  deterministic. The file is then rewritten with nobody listening; it must **not** re-eval.
  Drop the retirement and the entry keeps a due time in the past and re-evaluates the file
  on every single `Poll`.
- **[E9] a due retry is dropped while its path is pending again**: the newer save's
  settled drain *is* the retry — fresher content, clean budget — and reading now would
  mostly catch the file mid-write, so `Poll()` must consult `IsPending` and drop the
  entry rather than attempt it. Staged like [E8] (injected entry, due immediately), but
  keyed by the watcher's **own** pending key — FSW's FullPath, `/private`-prefixed on
  macOS — since a key mismatch would read as the check failing. One `Poll` inside the
  settle window discriminates three ways: superseded log + entry gone + var unbound is
  the fix; var bound with the mark still pending is the regression (the retry read
  through an in-flight save); var bound with the mark gone is a staging race, reported
  as such rather than as a verdict. Ends by asserting the dropped save still reloads.
- **[E10] `Dispose()` from inside `onChanged` stops the drain**: `onChanged` is called
  synchronously by `Attempt`, so a host disposing there returns into a loop still holding
  the rest of `TakeSettled()`'s list, reloading into a host that has already torn down.
  Two files settle into one drain and *either* one
  disposes when evaled, so exactly one of the two vars may be bound; two means the guard is
  gone. Deliberately order-independent — which file FSW reports first is not a thing to
  assert on. Reentrancy through `onChanged` is the only way a single-threaded consumer can
  reach `Dispose` mid-`Poll`.

**Mutation-checked** (2026-08-28, extended 2026-08-31): reverting each fix in a throwaway copy of
`ClojureReloader.cs` turns exactly one assertion red and leaves the rest green —
dropping the `_retries.Remove` that follows a successful read fails only [E8]
(`re-evaled with no new event = True`) — **not** [E2b], which the hand-out's own reset
covers; dropping that hand-out reset from `Poll()` fails only [E7]
(`the hand-out was given up on immediately`); an off-by-one in the cap check
(`>=` → `>`) fails only [E5] (`2 deferral(s) before give-up, expected 1`);
unguarding `_onChanged` fails only [E3] (`escaped exception = InvalidOperationException`);
narrowing the read `catch` filter fails only [E5] (`escaped Poll = FileNotFoundException`);
dropping the `IsPending` supersede check from `Poll()`'s retry loop (2026-09-01) fails only
[E9], through its regression branch, not the staging-race one (`the due retry read and
evaled the file while a newer save's mark was pending`);
dropping the `_disposed` guard from `Attempt()`
(2026-09-01) fails only [E10] (`the drain kept evaluating after Dispose() returned`);
dropping the constructor's roots-empty guard fails only [L3].
(Four rows left this list on
2026-09-04 with the `Init` → constructor change: the two that mutated `Init`'s teardown, the
one that moved it below its guard clauses, and the one that dropped its `_disposed` guard;
a fifth, the half-armed-watcher `try`/`catch`, left on 2026-09-07 with [L4] — see
[Unresolved](#unresolved).)
Run at `MaxReadAttempts` 5, 3 and 2, every assertion stays green with recomputed
expectations, and all three mutations above still turn exactly one red — the check that
[E5]/[E7] read the cap rather than assuming it. 2 is the floor: at 1 there is no retry left
to test.

## A note on the reflection-based checks

Probes 4, 5, 6 and 7 reach into private members (`_watchersByRoot`, `_pending`, `_retries`,
`MaxReadAttempts`, `_watcher`, and the watcher's `Mark`) to assert bookkeeping the public API
does not expose. Probe 7's [E8] and [E9] also *write* one — the only way to stage a retry
whose read succeeds, or one racing a pending mark — and Probe 6's [S2]/[P1] call `Mark`, the
table's only write door since `Requeue` left.
That couples them to names that are free to change: a rename leaves `GetField` returning
null and the probe dying on a `NullReferenceException` far from the cause — which is
exactly what refactors had already done repeatedly: to Probe 4's `_watchers`, to Probe 7's
`_retries`/`_pending`, then to `_retryCounts`/`_lastMarkElapsedMs` when those merged into
`_entries` (2026-08-31), and again when `_entries` split back into the watcher's `_pending`
and the reloader's `_firstFailure`, then to `_retries` when the retry queue became the
reloader's own (2026-09-01, second pass). The names are single constants per probe,
and a missing field raises a message naming the field instead of NullReferencing — which is
what made each of those renames a one-line fix rather than a debugging session.

## Probes 1–2 vs Probes 3–7: what each guards

Probes 1–2 prove *environment/design* behavior, so they keep passing after the fix
as-is (FSW still delivers duplicate events; concurrent eval still tears — the fix is
that the reloader stops doing both, which Probes 3–7 check). `run.sh` exiting 1 on
Probe 1 would mean the FSW environment itself changed (platform/backend); exiting 1
on Probes 3–7 means the reloader fix or one of its contracts regressed.

**Not probe-able, verified by argument only**: post-`Dispose()` callback suppression
(`FileSystemWatcher.Dispose()` does not wait for handlers already running on a ThreadPool
thread, so the `_disposed` early-return in `Mark`/`OnError` shrinks the window rather than
closing it — any assertion on it would be flaky by construction; `[C2]` covers the
observable contract, that no post-Dispose save is ever *delivered*); and absence of
lock-order deadlocks (`OnError` does take `_watchersLock` since 2026-09-01 — the dead-root
removal and its guard — but no cycle exists: `FSW.Dispose()` does not wait for in-flight
handlers, so a thread holding the lock while disposing a watcher cannot block on a handler
waiting for that same lock; C5 plus the stresses are the practical ceiling).

**Probe-able but not yet pinned**: `OnError`'s dead-root removal, which needs reflective
invocation of `OnError` to be deterministic. (constructing with a null logger →
`ArgumentNullException` was listed here on 2026-09-01 and is gone: `logger` is deliberately
nullable now, so there is no exception to pin. What a null logger costs is documented on the parameter instead.)

Two entries left this list on 2026-09-01. Concurrent-drain disjointness is no longer a
contract to verify — the watcher is single-consumer by construction, and `TakeSettled()`
says so. And the lost-update race, previously "verified by argument only" because the
window is microseconds wide, turned out to be reachable after all once the table is large
enough to stretch the enumerate→sort→remove phase: `[S2]` hit it in ~9% of rounds against
a mutated claim, deterministically enough at 150 rounds. **That is no longer true** — see
[[S2] is a false-green](#s2-is-a-false-green) below.

## Unresolved

**[L4], commented out 2026-09-07 (#173).** It asserted that a logger throwing *while
arming* does not strand the armed watch: the constructor propagates (the logger broke its
documented no-throw contract), and because a throwing constructor hands back no reference,
nothing outside could dispose what it armed, so it had to dispose the watcher itself before
rethrowing. The assertion was on **open file descriptors**, the only observable for a
watcher unreachable by construction: an armed `FileSystemWatcher` holds descriptors that
only `Dispose` returns. It calibrated first (a reloader that arms and is disposed normally
must move the count, else the check is blind) and used a real temp directory as its root.

Review of #173 removed the `try`/`catch` it pinned, so the check now fails by design. It is
commented out in `ReloaderFinalizerCheck.cs`, together with the `OpenFds`/`Collect`/`FdSlack`
helpers only it used; uncomment both blocks to restore it alongside the guard.

What the guard was worth, measured here both ways before it went:

```
guard present:  fds baseline = 4, armed = 16, after the throwing construction = 4   PASS
guard removed:  fds baseline = 4, armed = 16, after the throwing construction = 16  FAIL
                12 fd(s) still open -- the caller got no reference, so nothing can dispose it
```

Two things strand per failed construction: 12 fds, and a `_pending` nobody drains (the
watcher keeps marking paths). Neither is reclaimed. `Collect()` was `Thread.Sleep(300)` +
`GC.Collect()` + `WaitForPendingFinalizers()` + `GC.Collect()` and the fds survived all of
it, because an armed watch is rooted and so never becomes garbage. Whether
`DebouncedFileWatcher` declares a finalizer ([C1]) is beside the point; `FileSystemWatcher`
inherits one from `Component` and it does not run either. In the Editor this is expected to
persist until the next domain reload — expected, not measured: nothing in this pack runs
inside Unity, so domain-unload reclaim is untested.

Sizing it, measured 2026-09-07 (see [Measured](#measured-2026-09-07) below): the cost is
flat in the size of the watched tree, though it scales with the root's path depth, and
`_pending` is bounded rather than unbounded — it
converges on the source tree's accepted paths, since `Mark` keys by path and takes the last
write. An earlier draft of this section also listed a stranded monitor thread per watch;
that does not hold on this backend, which adds no thread per watcher.

The case for removing it anyway: the guard defended a documented-impossible path, and
`ClojureReloader` enforces that same logger contract nowhere else. Both `_logger?.Invoke`
calls in `Attempt()` are bare, so a throwing logger escapes into the host's tick, which
`[E3]` describes as silently killing the game loop. Eight lines and a nesting level in the
constructor bought a clean teardown for the one violation whose consequence is a leak rather
than a visible failure.

The counter-argument, which is why this is parked rather than closed: that site is the only
one where the violation is unrecoverable. At `Poll()` time a throwing logger is loud and the
reloader is still reachable, so the host can dispose it. At construction time it is silent
and no reference to clean up with ever exists.

Resolving it properly means picking one posture for the whole class rather than per call
site, and the measurement below decides which. A throwing logger escaping `Poll()` is not
the milder failure of the two: it propagates into the host's tick, where `[E3]` has it
killing the game loop, against a constructor leak of a dozen-odd descriptors that lasts
until domain reload. So the fix is the wider one — route every `_logger?.Invoke` through a
`Log(string)` that swallows and reports. That closes the severe path, makes the constructor
leak unreachable on the way, and lets [L4] come back green rather than staying parked.
Restoring the guard alone would leave the worse path open.

### [S2] is a false-green

**Found 2026-09-08, unfixed.** `[S2]` (`LostUpdateOnClaim`) no longer detects the defect it
names. Replacing `RemoveIf`'s compare-and-remove with a plain `TryRemove` — the exact
mutation its failure message describes — leaves it green:

```
mutated claim, 150 rounds:   [S2] PASS: re-mark lost on 0/150 raced claims
mutated claim, 1200 rounds:  [S2] PASS: re-mark lost on 0/1200 raced claims
```

More rounds will not recover it. The probe was written when `TakeSettled` snapshotted the
table, sorted it, and only then removed, which left a window of hundreds of microseconds
between the enumerator reading `target`'s timestamp and the claim reaching it — the ~9%
quoted above. `TakeSettled` now claims inline:

```csharp
foreach (var kv in _pending) {
    if (now - kv.Value < _settleMs) continue;
    if (RemoveIf(kv.Key, kv.Value)) result.Add(kv.Key);
}
```

so those two moments are adjacent statements and the racing `Mark` has nanoseconds to land
in. The probe's own comment already claims "the window survived that going away"; it did
not.

It is not a regression from the settle-window seam, and not from the `SettleMs` const before
it. Running the same mutation against `git show <the feature commit>:…/DebouncedFileWatcher.cs`
verbatim — the three-argument constructor with `settleMs = 200`, the probe passing 50, the
tree exactly as it stood before either change — is also green at 0/150. Nor did it break
inside this repo: the file has one commit here, and no committed version of it has ever
sorted. The ~9% was measured against the pre-move `integrated/Packages/clojure-tool/`
version and was never re-validated after the sort went away, so `[S2]` has been a
false-green for the whole life of the file in this repository.

The compare-and-remove is still worth keeping: a real watcher thread can mark at exactly
that moment even though the probe cannot arrange it. What is gone is the evidence, so
`[S2]` currently protects nothing. Recovering it means forcing the interleaving instead of
racing it — a seam inside `TakeSettled` that a probe can block on between the enumerate and
the claim — which is a bigger change to production code than the window seam, and a
judgement call about how much test-only surface `DebouncedFileWatcher` should carry.

### Measured (2026-09-07)

Numbers behind the two paragraphs above, taken outside the pack with `csc` + `mono` on
macOS, so a later reader can weigh the trade without re-deriving them. `FileSystemWatcher`
resolves to `System.IO.CoreFXFileSystemWatcherProxy` here.

**The fd cost is flat in the tree, linear in the root's path depth, linear in stranded
watchers.** One armed recursive watcher, against trees of increasing size:

```
dirs in tree      3     11     51    201
fds held        +12    +12    +12    +12     (reclaimed by GC + finalizers: 0, every size)
```

```
watchers armed    0      1      2      3      4    then all disposed
fds               4     16     28     40     52    ->  4
threads          10     10     10     10     10    -> 10
```

The 12 is not a constant, though: it is the root's own path chain. `lsof -a -p` on an armed
watcher names every descriptor it holds — two `KQUEUE`, then one `DIR` for the watched root
and one for **each of its ancestors**, up to `/System`:

```
3u   KQUEUE          4r  DIR  /private/var/folders/dk/.../T/fdkind_xxx   <- the root
14u  KQUEUE          5r  DIR  /private/var/folders/dk/.../T
                     6r  DIR  /private/var/folders/dk
                     ...      ... every ancestor
```

Nothing per watched subdirectory, which is why the sweep above stays flat. Holding the
subtree at 20 directories and moving the root deeper instead:

```
root depth (segments)   6     9    12    15
fds held              +12   +15   +18   +21
```

One descriptor per extra path segment. The 12 above is `Path.GetTempPath()` sitting six
segments deep; a real source root under `Assets/` sits deeper, so read it as roughly
14-16 per root rather than a flat 12.

Three consequences. A recursive watch does **not** cost per directory, so a large source
tree strands no more than a small one. The total is that per-root figure x failed
constructions, so this matters only if construction repeats, which it does only while the
logger stays broken. And arming adds **no thread**, which is what retires the monitor-thread
claim; `Dispose` returns every fd, so the leak is specific to the unreachable-instance case
[L4] covered and is not a general watcher problem.

For scale: macOS `launchctl limit maxfiles` defaults to a 256 soft limit for GUI-launched
apps, and Unity raises its own well above that. A dozen-odd descriptors is not the argument
against removing the guard; the argument, if there is one, is the unrecoverability in the
paragraph above.

**A throwing logger does escape `Poll()`.** Asserted in the paragraphs above from reading
`Attempt()`; confirmed by running it. A logger that throws on the success line, after a
reload that demonstrably happened:

```
reload actually happened (p/v) = 1
=> Poll() PROPAGATED InvalidOperationException
```

Whoever writes the probe for this should note the trap that made the first attempt of this
measurement pass for the wrong reason: match the success line by its current text. The run
above reported `CONTAINED` until the matched string was corrected to `Reloaded {path}`, and
only the separate `p/v` check showed the reload had happened at all. Assert that the reload
occurred before concluding anything about containment, the way [E2b] does.

## How the pack was built

See [`PROVENANCE.md`](PROVENANCE.md) for the commit-by-commit history (distilled from the
retired toy-repo mirror): why Probes 1–2 are *evidence* and 3–7 are *regression guards*, when
each probe landed, and why these commits are identified by subject rather than hash.
