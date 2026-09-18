# Provenance — how this evidence pack was built

This pack was developed in the now-retired `toy-repo` mirror and migrated here. The toy's
git history is the only record of how it grew; this file distills the probe-relevant commit
messages so that record survives the repo's deletion. `README.md` describes what each probe
asserts *today*; this is the *why and when*.

## Guiding principle (what the history shows)

- **Probes 1–2 are evidence.** They reproduce the pre-v2 races — outside Unity, repeatably —
  and were the case made to justify the fix upstream. They keep passing after the fix because
  they measure the *environment* (FSW still delivers duplicate events; concurrent eval still
  tears); the fix is that the reloader stops doing both.
- **Probes 3–7 are regression guards.** Each one promotes something the v2 code review had
  verified only *by reading* into an executable assertion, added as the corresponding fix
  landed. A red Probe 3–7 means the fix or one of its contracts regressed.

## Timeline

- **2026-07-16** — Probes 1–2 born as the evidence pack. `WatchProbe.cs` (2 `Changed` events
  per vim-style save, arriving on two different ThreadPool threads; the save-style matrix) and
  `race_torn_reload.clj` (100% of concurrent reloads observed torn). `run.sh` asserts both
  reproduce; exit 0 = proven. Framed as evidence for the reloader problems and the upstream pitch.
- **2026-07-17** — Probe 3 (`ReloaderQueueTest.cs`) added *alongside the v2 rewrite* (reload eval
  moved off the FSW thread onto the host's main-thread `Poll()` tick, via the new Clojure-free
  `DebouncedFileWatcher`). It drives the **real** `DebouncedFileWatcher`: 5 vim saves → 5 reloads,
  rename-over-target (`.tmp`) delivered exactly once. In-editor verification was still pending at
  this point (later done 2026-07-20; see `clojure-hot-reload.md`'s in-editor pass).
- **2026-07-20** — Probes 4–5 (`DisposeTest.cs`, `ReloaderFinalizerCheck.cs`) assert the
  GC/Dispose review fixes: no finalizer declared, `Dispose()` deterministically releases the
  `FileSystemWatcher`, idempotent `Dispose()`, `AddRoot()` after `Dispose()` throws
  `ObjectDisposedException`, and a concurrent `AddRoot`/`Dispose` race tolerating only
  `ObjectDisposedException` with no armed watcher left behind. Probe 5 needs `Clojure.dll`, so
  `run.sh` **skips** (does not fail) it when the runtime hasn't been fetched.
- **2026-07-20** — Probe 6 (`WatcherContractTest.cs`) plus Probe 5's `[L]` lifecycle checks and
  Probe 3's `[C]` gate assertion: segment-aware root dedupe incl. the `foo/bar` vs `foo/barbaz`
  sibling case, `MarkRetry`/`MarkResolved` budget semantics incl. the fresh-event reset
  (starvation fix), the one-time no-Poll warning (positive + negative), and a no-lost-update
  stress (60-write storm → 1 delivery, final version intact). First run fully green
  (`PACK_EXIT=0`, all `D1–D3`/`R1–R4`/`W1–W2`/`S1`/`GATE` pass). **`W1–W2` no longer exist**:
  the no-Poll warning was later dropped from `DebouncedFileWatcher`, and its checks went with
  it — today's Probe 6 has no `W` labels. **`R1–R4` no longer exist either**: the retry budget
  left the watcher on 2026-09-01 (below), first replaced by `[Q1]–[Q5]` and then by nothing
  at all when `Requeue` itself went — the reset they had guarded came back the same day as
  Probe 7's `[E7]`.
- **2026-08-28** — Probe 7 (`ReloaderEvalTest.cs`), the first probe written *here* rather than
  migrated: `ClojureReloader`'s eval path against the real ClojureCLR runtime. The only probe that
  **boots** the runtime (`Clj.Boot` → `RT.Init`) and evals real forms, so it needs the whole
  `clojure-clr` directory (Microsoft.Dynamic/Scripting alongside `Clojure.dll`) and is **skipped**,
  not failed, when absent. Promotes the last unprobed claim — the reloader's whole reason for
  existing — into assertions: a save evals the file and a *re*-save redefines the var (not just
  first-load-wins), an eval failure retires that path's retry bookkeeping (the budget is for read
  races, not source that will never compile), a throwing `onChanged` cannot escape `Poll()` (it
  would kill integrated's `async void RunWorkLoop`, which also drives event dispatch), and `.cljc`
  reader conditionals pick `:cljr`. Mutation-checked: reverting each fix in a throwaway copy turns
  exactly one assertion red and leaves the rest green.

## A note on commit hashes

Hashes cited in this file, in the probe comments and in `run.sh` (`4aae25b`/`32f1260`, `470ea42`,
`b3026df`, `e3fc488`, …) are the retired toy clone's `toy-local` hashes. **None of them resolve to
anything reachable any more**, and neither does the `c7a6bd8..1080138` range this note used to give
as their replacement: the work was migrated here and then rebased, rewriting every hash a second
time. The old objects linger as unreachable commits until they are GC'd — which is why
`git show e3fc488` still works while `git branch --contains e3fc488` prints nothing.

**So identify these commits by subject, not by hash.** Subjects survive a rebase; hashes do not.
The v2 set was developed on `Packages/clojure-tool@refactor/clj-reloader-poll` in Basecity's
`integrated`, each commit carrying the `reloader:` prefix; on 2026-09-04 the two files and this
pack moved here without history (see the last entry below), so that branch is where the
pre-move history lives. The item (#1–#11) numbering these entries use came from an
enhancement backlog that no longer exists anywhere — read the numbers as historical labels, not
as a index you can look up.

- **2026-09-03** — Probe 5 `[L3]` re-pointed at the contract the code actually has. `Init` stopped
  throwing `InvalidOperationException` with no source root installed (see the 2026-08-31 entry
  below) and now logs `No clj source root installed. Reload disabled.` and returns — the
  log-and-return posture the README documents, chosen because a missing root is an environment
  problem and `integrated` calls `Init` from a boot step that does not survive an exception. The
  probe had gone red against a live tree for that reason alone. Asserting the log by itself would
  pass for a reloader that logged the line and then armed a rootless watcher, so `[L3]` now also
  reflects `_watcher` and calls `Poll()`. Two things were stale in the same file: the hermeticity
  clear named `CLJ_SOURCE_ROOTS_OWNED`, a variable `CljSourceRoots` no longer has (it keeps
  `CLJ_SOURCE_ROOTS_INSTALLED` / `_APPENDED`), so it had been a no-op that happened to pass only
  because nothing in the process installs roots. The mutation note in `README.md` still holds:
  dropping `Init`'s roots-empty guard fails only `[L3]`, now on `armed a watcher = True`.

- **2026-08-31** — Probes 5 and 7 extended, and two probes un-broken. New assertions:
  `Init()` after `Dispose()` throws `ObjectDisposedException` (Probe 5 `[L2]`, the documented
  contract, previously unasserted); `Init()` with no source root installed throws
  `InvalidOperationException` (Probe 5 `[L3]`, a *new* contract — `Init` stopped installing
  roots and now only watches what `CljSourceRoots.Installed` reports, so it became reachable
  with nothing to watch); a *read* failure re-queues via `MarkRetry` and gives up at
  the cap (Probe 7 `[E5]` — the last untested branch of `Poll()`, forced deterministically by
  deleting a marked file so `FileNotFoundException` takes the `IOException` path); and re-`Init`
  disposing the previous watcher (Probe 7 `[E6]` — a leaked watcher delivers nothing since it is
  never drained, so what leaks is an armed OS-level watch per domain reload). All three
  mutation-checked. Separately, the reflection helpers in Probes 4 and 7 were still reading
  `_watchers`, `_retries` and `_pending` — renamed to `_watchersByRoot`, `_retryCounts` and
  `_lastMarkElapsedMs` in the v2 refactor — so Probe 4 crashed and Probe 7's `[E2]` died on a
  `NullReferenceException`. Field names are now one constant per probe, and a missing field
  reports itself by name. The same signature change
  (`Init(remapFilePath, logger, onChanged)` → `Init(logger, onChanged)`) broke Probes 5 and 7
  outright; Probe 7 now installs the roots itself in setup, which is what the reloader used
  to do for it.
- **2026-09-01** — The watcher was cut back to a debouncer, and the probes with it. Adopting a
  single-consumer constraint let the retry budget move into `ClojureReloader` as a time window
  (`RetryWindowMs`), which deleted the `NotPending` sentinel, the four-state `_entries` value,
  `MaxRetries`, `MarkRetry` and `MarkResolved` — 271 lines to 215, and a three-call drain
  protocol down to `TakeSettled`/`Requeue`. Probe 6's `[R1]–[R5b]` were retry-budget tests with
  nothing left to test; `[Q1]–[Q5]` replace them with the `Requeue` contract, including `[Q3]`,
  which pins that `Requeue` runs the same accept gate as an FS event (it delegates to `Mark`) —
  previously the one ungated write into the table. Probe 7's reflection helpers follow the
  budget out of the watcher: `EntryKind.Retrying` now counts `ClojureReloader._firstFailure`,
  `EntryKind.Pending` the watcher's `_pending`, and the `NotPending` sentinel reader is gone.
- **2026-09-01 (second pass)** — The time-window budget that landed hours earlier was wrong,
  and the change that landed it had deleted the test that would have said so. `RetryWindowMs`
  measured wall time from the first failed read, but a retry could only be attempted once the
  watcher handed the path back — and every write re-stamps the settle window. So a burst of
  saves held the path back while the budget drained, the path was condemned after ~2 real
  attempts instead of ~5, and the give-up branch dropped the save outright (no requeue) while
  logging `unreadable for 1000ms` about a file that was merely busy. The pre-refactor design
  had both halves right — a *count* (`MaxRetries`) and `Mark`'s `// a fresh event resets the
  retry budget from the previous event` — and `[R1]–[R5b]`, deleted that morning as "retry-budget
  tests with nothing left to test", were what covered the reset.
  Fixed by finishing the direction the refactor started rather than by re-adding the flag the
  (since-deleted) enhancement backlog had sketched: retries no longer go through the watcher at all.
  `ClojureReloader` owns a `_retries` table (attempts + a due time, `RetryBackoffMs`), so every
  path arriving from `TakeSettled` is a genuine event by construction and the reset needs no
  provenance flag to detect — `Requeue` and its `[Q1]–[Q5]` are gone, and `DebouncedFileWatcher`
  is a pure debouncer with one write door. Probe 7 gains `[E7]` (the reset, and that a write
  burst spends no budget) and `[E8]` (a retry whose read succeeds retires its budget instead of
  re-evaluating the file on every Poll); `[E5]` now asserts an exact attempt count, which the
  time window had made unassertable. Probe 6's `[S2]` marks through the private `Mark` by
  reflection, since the public write door it used is gone. All four mutation-checked, plus a
  cap retune to prove `[E5]`/`[E7]` read `MaxReadAttempts` rather than assuming it (green at
  5, 3 and 2, with the mutations re-checked at 2). **`MaxReadAttempts` was then set to 2** —
  one retry, ~150ms of cover for a save-in-progress, which is the shape of the race it is for;
  a longer budget only helps a file locked by something else, and that file gets its next save.

  One finding came out of re-running the mutation checks rather than assuming they carried over.
  `[S1]` had been documented since 2026-07-20 as the statistical guard for `TakeSettled()`'s
  conditional remove; measured against a mutant that makes the claim an unconditional
  `TryRemove`, it passes **6 runs out of 6**. macOS FSW latency keeps real events far outside the
  microsecond claim window, so `[S1]` only ever tested coalescing and liveness. `[S2]` now covers
  the real property by widening the window deliberately — 4000 table entries to stretch the
  enumerate→sort→remove phase, then one `Requeue` fired from a second thread against an in-flight
  drain — and catches that mutant in ~9% of rounds, run 150 times. It is table-only (no roots
  armed), so it tests the claim protocol rather than the filesystem. This also retired the
  "concurrent callers get disjoint sets" item from the README's not-probe-able list: with a
  single consumer there is no longer such a contract to verify.
- **2026-09-01 (third pass)** — Two branches added after the second pass had no coverage, both
  found by mutation rather than by reading: removing them left the whole pack green. `Init` now
  clears `_retries` (a stale entry is already past its due time, so the first `Poll` after a
  domain reload phantom-reloads a file nobody saved, possibly under different roots), asserted
  by Probe 7's `[E6b]` on that consequence rather than on the table. And `Attempt()` bails when
  `_disposed`, since `onChanged` runs synchronously inside the drain and is the one way a
  single-threaded host can reach `Dispose` mid-`Poll` — it returns into a loop still holding the
  rest of `TakeSettled()`'s list, with `_logger` already nulled, so those extra reloads are
  silent too. `[E10]` stages it with two files settling into one drain, either of which disposes
  when evaled, so the assertion is "exactly one var bound" and does not depend on FSW's
  delivery order. Both mutation-checked; `[E9]`'s supersede check re-checked alongside them.

- **2026-09-04** — `Init` became the constructor. `ClojureReloader(logger, onChanged)` replaces
  `void Init(logger, onChanged)`; the two-phase shape was a leftover from 2023, when `Clj.Boot`
  owned a static reloader and handed it a config path after construction. Config loading moved
  to `CljSourceRoots.Install` long ago, so what `Init` still did was assign two callbacks and
  arm a watcher — a constructor's job, and the shape `DebouncedFileWatcher` already had. The
  host's only call site (`Assets/AppIntegrated/CljSystem.cs`) constructed and `Init`ed on
  adjacent lines, so nothing in `integrated` used re-`Init`; a domain reload does not either,
  since it wipes the instance (which is why `CljSourceRoots` keeps its bookkeeping in env vars).

  Four assertions were retired with the contracts they covered, not because they failed:
  `[L2]` (`Init` after `Dispose` throws `ObjectDisposedException`), `[E6]` (re-`Init` disposes
  the previous watcher), `[E6b]` (re-`Init` clears the retry queue) and `[E6c]` (a re-`Init`
  finding no root disarms). Re-arming is now dispose-and-construct, so none of them describe a
  reachable state. `_watcher`, `_logger` and `_onChanged` became `readonly`, which cost two
  things: `Poll` needs its own `_disposed` check (a disposed watcher still holds its marks, and
  `_watcher` no longer goes null to stop the drain), and `[E10]` reads the `_disposed` field
  rather than inferring teardown from a nulled `_watcher`. Probe 5's `[L]` and `[L3]` merged
  onto one inert instance, and its env-var clearing had to move **first** — with construction
  arming the roots, a leftover exported value would arm a watcher before the check could run.
  Whole pack re-run green.

- **2026-09-04 (second pass)** — Review of the constructor change found an unguarded window:
  between `new DebouncedFileWatcher(...)` and the end of the arming loop, a logger that
  throws (forbidden by its own docstring, so a contract-violation path) escapes the
  constructor. Assignment order is irrelevant to this — a throwing constructor returns no
  reference, so nothing outside can dispose what was armed either way — which is why the fix
  is a `try`/`catch` that disposes the watcher and rethrows, not an earlier field write.
  `[L4]` pins it. The watcher is unreachable by construction, so the assertion is on open
  file descriptors: an armed `FileSystemWatcher` holds kqueue fds, measured at 12 per armed
  root here, and only `Dispose` returns them. The probe calibrates first (arming must move
  the count, or the check is blind) and installs its root by setting
  `CLJ_SOURCE_ROOTS_INSTALLED` directly, since `CljSourceRoots.Install` reads edn through
  `Clj.Read` and Probe 5 deliberately boots no runtime. Mutation-checked: removing the
  `try`/`catch` fails only `[L4]`, and the stranded fds survive a forced collect plus
  finalizers — the measurement behind "an armed watch leaks rather than being collected".

- **2026-09-08 (settle-window seam)** — `DebouncedFileWatcher`'s settle window went back to
  being injectable, after the `SettleMs` const left the pack uncompilable at its six call
  sites. Rather than reinstate the optional parameter, the const became `DefaultSettleMs`
  behind a two-argument constructor (the only one the Editor calls) and a three-argument one
  documented as a test seam. The pack builds and runs unedited again, 71 s end to end.
  Mutation-testing `[S2]` while its window was in flux turned up a false-green that predates
  this: with `RemoveIf`'s compare-and-remove replaced by a plain `TryRemove` it stays green
  at 150 and at 1200 rounds, because `TakeSettled` claims inline now and the window it was
  written against — enumerate, sort, then remove — is gone. Re-run against the feature
  commit's own copy of the file it is also green, and no committed version here has ever
  sorted, so this dates to the move from `integrated/` rather than to anything on this
  branch. README.md, "[S2] is a false-green".

- **2026-09-07 (#173 review)** — The `try`/`catch` added in the second pass above was
  removed: it defended a documented-impossible path (`logger` must not throw), and the class
  enforces that contract nowhere else, both `_logger?.Invoke` calls in `Attempt()` being bare.
  `[L4]` asserted the guard, so it is commented out rather than deleted, with the
  `OpenFds`/`Collect` helpers only it used. The leak it measured is real and was re-measured
  both ways first (12 stranded fds, surviving a forced collect plus finalizers); what stays
  unresolved is that the constructor is the one site where a violating logger strands
  something unrecoverably. Re-measuring also corrected two details of the earlier note: the
  fd cost is flat in the size of the watched tree (12 at 3 directories and at 201, linear
  only in stranded watchers), and no thread is stranded per watch. Full reasoning, both
  directions, plus the numbers, in README.md under "Unresolved".

- **2026-09-04 (the move)** — `ClojureReloader.cs` and `DebouncedFileWatcher.cs` moved from
  `integrated/Packages/clojure-tool/Runtime/` (namespace `Hjd.ClojureTool`) into
  `magic-unity/Editor/Reload/` (namespace `Magic.Unity`, its own asmdef `Magic.Unity.Editor.Reload`
  constrained to `!MAGIC_RUNTIME_IN_EDITOR`), and this pack with them, from
  `integrated/Tools/probes/` to `magic-unity/Tests~/reloader/`. Hot reload is a property of the
  ClojureCLR Editor runtime the package ships, so the package is where every consumer can reach
  it; the files were restyled to the package's conventions and otherwise moved as they were.
  Two contract changes, both visible in the probes: the roots are a constructor argument
  (`ClojureReloader(roots, logger, onChanged)`) instead of being read from
  `CljSourceRoots.Installed`, so Probe 5's env-var clearing went and Probe 7 no longer stages a
  `reload.edn`; and the runtime check (`Clj.LoadsFromSource`) became the compile-time
  asmdef define constraint `!MAGIC_RUNTIME_IN_EDITOR` (a file-level `#if` was tried first and
  dropped on 2026-09-07 as redundant), so `run.sh` no longer needs
  `-define:UNITY_EDITOR` and there is no "does not support reload" log line to assert on. The
  `[L3]` string changed with the first (`No clj source root. Reload disabled.`). Probe 2 moved
  from JVM Clojure to the shipped ClojureCLR through `TornRaceHost.cs`, which dropped the pack's
  JVM requirement; its first run was a false positive (`*ns*` at its `clojure.core` root, see the
  README) and now measures ~96% torn reads. The runtime is tracked in this repo, so Probes 5 and 7
  no longer have a SKIP branch: a missing `Clojure.dll` is a failure. Whole pack green after the
  move.
