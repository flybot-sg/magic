using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading;
using Magic.Unity;
using clojure.lang;

// Probe 7 — ClojureReloader's eval path, end to end through the real ClojureCLR
// runtime. Probes 3/4/6 stop at the DebouncedFileWatcher boundary and Probe 5 only
// covers lifecycle null-safety, so everything below was previously unverified: the
// reloader's whole reason for existing (a save redefines a var) had no test at all.
//
//   [E1] a save actually reloads: the var takes the file's value, and a second save
//        redefines it (not just first-load-wins).
//   [E2] an eval failure retires the path's retry bookkeeping. The budget exists for
//        read races, not for source that will never compile. Weak on its own: a file that
//        reads cleanly never opens a _retries entry, so there is nothing for the cleanup
//        to remove. [E2b] is the discriminating version.
//   [E2b] the cleanup is load-bearing when a read failure opened the budget first: the
//        file is held open exclusively (read fails -> _retries entry), then rewritten with
//        uncompilable source, so the attempt that follows reads fine and fails in eval. That
//        entry must be gone, or the path carries a stale attempt count into the next save.
//   [E3] a throwing onChanged does not escape Poll(). The host calls Poll() from its
//        main tick -- in integrated, RunWorkLoop, which also drives event dispatch --
//        so an escaping exception would silently kill the game loop.
//   [E4] .cljc reader conditionals pick the :cljr branch, pinning the argument shape
//        of the Compiler.load call (passing the real file name is what enables them).
//   [E5] a READ failure retries on the reloader's own queue and gives up at exactly
//        MaxReadAttempts, counting attempts rather than elapsed time. The read branch --
//        `catch when (ex is IOException || ex is UnauthorizedAccessException)` -- also has
//        to hold: narrow that filter and the exception escapes into the host tick, the same
//        failure mode [E3] guards on the callback side. Staged with an exclusive handle
//        (ReadLock), the sharing violation this budget exists for; a deletion is a
//        different outcome now and is [E11]'s business.
//   [E7] REGRESSION (the starvation fix): a fresh file event resets the read budget. The
//        watcher's settle window is re-stamped by every write, so a burst holds the path
//        back and no retry can even be attempted while it runs -- a wall-clock budget is
//        spent by the writer and the save is then dropped on its first failed read, with a
//        log line blaming a file that was merely busy. The equivalent test existed once
//        ([R1]-[R5b], "fresh-event reset") and was deleted with the watcher-side budget on
//        2026-09-01, the same change that dropped the reset. Two properties: attempts start
//        over after a genuine event, and nothing is given up on mid-burst.
//   [E8] a retry whose read SUCCEEDS retires the budget, and the path is not attempted
//        again. This is the ordinary rename-replace recovery -- the file becomes readable
//        with no further FS event -- and it cannot be staged through the watcher, since
//        making the file readable again is itself an event. So the entry is injected
//        directly and the file lives outside the watched root: no FSW involvement, fully
//        deterministic. Drop the retirement and the entry stays due forever, re-evaluating
//        the file on every single Poll.
//   [E11] a file that VANISHES between the event and the read is dropped outright: no
//        retry budget, no log line. FileNotFoundException derives from IOException, so
//        before the narrow catch was put ahead of the retry filter every editor temp file
//        that disappeared mid-drain burned two attempts and then logged "Reload failed",
//        which in the Unity console is indistinguishable from a real compile error against
//        a file the user never edited. The counterpart to [E5]: busy retries, gone does not.
//   [E11b] the same, for an ordinary `rm` of a watched source file. Deleted is not
//        subscribed, so this looks unreachable -- but the FSEvents-backed watcher raises
//        Changed for a plain delete too, which marks the path like any other event. Whether
//        it marks is platform-dependent and only reported; the silence is asserted.
//   [E12] editor lock/auto-save/backup names never enter the pipeline, and the gate's
//        accepting half still lets an upper-cased `.CLJ` through. Emacs writes
//        `.#core.clj` on the first keystroke in a buffer and Path.GetExtension of that is
//        ".clj", so the original extension-only gate accepted it -- the one editor
//        convention that mangles the PREFIX rather than the suffix, which is why vim's
//        .swp, JetBrains' ___jb_tmp___ and Emacs' own `~`/`#` forms never showed the bug.
//   [E9] a due retry is DROPPED while its path is pending again in the watcher: the newer
//        save's settled drain is the retry, with fresher content and a clean budget, and
//        reading now would mostly catch the file mid-write. Staged like [E8] (injected
//        entry, due immediately) but keyed by the watcher's OWN pending key -- FSW's
//        FullPath, /private-prefixed on macOS -- since a key mismatch would read as the
//        supersede check failing. One Poll inside the settle window discriminates three
//        ways: superseded log + entry gone + var unbound is the fix; var bound with the
//        mark still pending is the regression (the retry read through an in-flight save);
//        var bound with the mark gone is a staging race, reported as such.
//   [E10] Dispose() from inside onChanged stops the drain. onChanged is called
//        synchronously by Attempt(), so a host that disposes there returns into a loop
//        still holding the rest of TakeSettled()'s list -- and by then _logger is null, so
//        the reloads that follow are silent as well as unwanted. Two files settle into one
//        drain and either one disposes when evaled, so exactly one of the two vars may be
//        bound; two = the guard is gone. Order-independent on purpose: which file FSW
//        reports first is not something to assert on.
//
// Needs the stock ClojureCLR runtime: unlike Probe 5 this one actually boots it
// (RT.Init) and evals, so run.sh copies the whole clojure-clr dir.
class ReloaderEvalTest {

    static readonly List<string> Log = new List<string>();
    static readonly object LogLock = new object();

    // Set if an exception escapes Poll() -- the [E3] failure mode.
    static Exception Escaped;

    static int failures;

    static int Main() {
        var root = Path.Combine(Path.GetTempPath(), "reloadereval-" + Guid.NewGuid().ToString("N"));
        var src = Path.Combine(root, "src");
        Directory.CreateDirectory(src);

        try {
            RT.Init();
            using (var r = new ClojureReloader(new[] { src }, Sink, OnChanged)) {
                E1_ReloadRedefinesVar(r, src);
                E2_EvalFailureRetiresRetryBudget(r, src);
                E2b_EvalFailureClearsAReadFailureBudget(r, src);
                E3_ThrowingCallbackDoesNotEscapePoll(r, src);
                E4_ReaderConditionalPicksCljr(r, src);
                E5_ReadFailureRetriesThenGivesUp(r, src);
                E7_FreshEventResetsTheReadBudget(r, src);
                E8_SuccessfulRetryRetiresTheBudget(r, root);
                E9_DueRetryDroppedWhilePathIsPendingAgain(r, src);
                E11_VanishedFileIsDroppedWithoutRetrying(r, src);
                E12_EditorTempNamesAreNeverMarked(r, src);
            }
            // Owns and disposes its own reloader, so it stands outside the block above.
            E10_DisposeFromCallbackStopsTheDrain(src);
        } finally {
            try { Directory.Delete(root, true); } catch { /* best effort */ }
        }

        if (failures == 0) {
            Console.WriteLine("PROBE7-EVAL-VERIFIED");
            return 0;
        }
        Console.WriteLine("PROBE7-FAIL");
        return 1;
    }

    // --- [E1] ------------------------------------------------------------------

    static void E1_ReloadRedefinesVar(ClojureReloader r, string src) {
        var f = Path.Combine(src, "probe_a.clj");
        File.WriteAllText(f, "(ns probe.a)\n(def v 1)\n");
        var first = DrainUntil(r, () => Eq(VarValue("probe.a", "v"), 1L));

        // The second save is the part that matters: a reloader that only ever loads a
        // file once would pass the first assertion and fail this one.
        File.WriteAllText(f, "(ns probe.a)\n(def v 2)\n");
        var second = DrainUntil(r, () => Eq(VarValue("probe.a", "v"), 2L));

        var got = VarValue("probe.a", "v");
        Console.WriteLine($"[E1] first load reached 1 = {first}; after re-save probe.a/v = {Show(got)} (expect 2)");
        if (first && second) {
            Console.WriteLine("[E1] PASS: a save evals the file and a re-save redefines the var");
        } else if (!first) {
            Fail("E1", "the file never loaded at all (probe.a/v never became 1)");
        } else {
            Fail("E1", $"reload did not redefine the var: probe.a/v = {Show(got)}");
        }
    }

    // --- [E2] ------------------------------------------------------------------

    static void E2_EvalFailureRetiresRetryBudget(ClojureReloader r, string src) {
        var f = Path.Combine(src, "probe_bad.clj");
        // Unbalanced: the reader hits EOF mid-form, so Compiler.load throws.
        File.WriteAllText(f, "(ns probe.bad)\n(def x \n");
        var logged = DrainUntil(r, () => LoggedContains("Reload failed for", "probe_bad.clj"));

        // Converge instead of sampling once: a straggler event from this save landing after
        // a point-in-time count would re-Mark the path and read as a failure. Still
        // discriminating -- before the fix nothing ever removed the retry entry, so this
        // cannot converge and times out.
        var retired = DrainUntilRetired(r, "probe_bad.clj");

        var retries = KeysFor(r, EntryKind.Retrying, "probe_bad.clj");
        var pending = KeysFor(r, EntryKind.Pending, "probe_bad.clj");
        Console.WriteLine($"[E2] eval failure logged = {logged}; left for probe_bad.clj: " +
            $"retry entries = {retries}, pending = {pending} (expect 0/0)");
        if (retries < 0) {
            Fail("E2", "the reloader has no watcher -- construction armed none, so nothing was under test");
        } else if (!logged) {
            Fail("E2", "the bad file never produced a 'Reload failed' log line");
        } else if (!retired) {
            Fail("E2", $"eval failure left bookkeeping behind: {retries} retry entry(ies), " +
                $"{pending} pending (_retries not cleared for that path)");
        } else {
            Console.WriteLine("[E2] PASS: eval failure retired the path's retry bookkeeping");
        }
    }

    // --- [E2b] -----------------------------------------------------------------

    static void E2b_EvalFailureClearsAReadFailureBudget(ClojureReloader r, string src) {
        const string name = "probe_bad2.clj";
        var f = Path.Combine(src, name);
        File.WriteAllText(f, "(ns probe.bad2)\n(def y 1)\n");
        if (!WaitUntil(() => KeysFor(r, EntryKind.Pending, name) > 0)) {
            Fail("E2b", "the setup save was never marked, so the read could not be made to fail");
            return;
        }
        // Hold it open exclusively before the drain reads it, so the read fails with an
        // IOException and Poll() opens a _retries entry. Opening fires no event of its own,
        // so the read is guaranteed to fail whenever the next Poll lands.
        var unreadable = new ReadLock(f);
        var opened = DrainUntil(r, () => KeysFor(r, EntryKind.Retrying, name) > 0, 3000);
        unreadable.Dispose(); // released here so the early return below cannot strand it
        if (!opened) {
            Fail("E2b", "an unreadable file did not open a retry budget -- the read branch never ran");
            return;
        }
        // Rewrite it uncompilable: the next attempt gets past the read and dies in
        // Compiler.load, which is the branch under test.
        File.WriteAllText(f, "(ns probe.bad2)\n(def y \n");
        var logged = DrainUntil(r, () => LoggedContains("Reload failed for", name));
        var retired = DrainUntilRetired(r, name);

        var retries = KeysFor(r, EntryKind.Retrying, name);
        Console.WriteLine($"[E2b] budget opened by read failure = {opened}; eval failure logged = " +
            $"{logged}; retry entries left = {retries} (expect 0)");
        if (!logged) {
            Fail("E2b", "the recreated bad file never produced a 'Reload failed' log line");
        } else if (!retired) {
            Fail("E2b", $"eval failure left {retries} retry entry(ies) behind -- " +
                "the _retries.Remove that follows a successful read is missing");
        } else {
            Console.WriteLine("[E2b] PASS: eval failure cleared a budget opened by an earlier read failure");
        }
    }

    // --- [E3] ------------------------------------------------------------------

    static void E3_ThrowingCallbackDoesNotEscapePoll(ClojureReloader r, string src) {
        // OnChanged throws for this filename only (see OnChanged).
        var f = Path.Combine(src, "throwy.clj");
        File.WriteAllText(f, "(ns probe.c)\n(def ok 1)\n");
        var contained = DrainUntil(r, () => LoggedContains("Reload callback failed for", "throwy.clj"));
        DrainFor(r, 400);

        var reloaded = Eq(VarValue("probe.c", "ok"), 1L);
        Console.WriteLine($"[E3] escaped exception = {(Escaped == null ? "none" : Escaped.GetType().Name)} (expect none); " +
            $"callback failure logged = {contained}; file still reloaded = {reloaded}");
        if (Escaped != null) {
            Fail("E3", $"a throwing onChanged escaped Poll() as {Escaped.GetType().Name} -- this would kill the host tick");
        } else if (!contained) {
            Fail("E3", "the callback threw but nothing was logged (failure swallowed silently)");
        } else if (!reloaded) {
            Fail("E3", "the callback threw and took the reload with it (probe.c/ok never bound)");
        } else {
            Console.WriteLine("[E3] PASS: callback failure was contained, logged, and the reload still stood");
        }
    }

    // --- [E4] ------------------------------------------------------------------

    static void E4_ReaderConditionalPicksCljr(ClojureReloader r, string src) {
        var f = Path.Combine(src, "probe_d.cljc");
        File.WriteAllText(f,
            "(ns probe.d)\n(def which #?(:cljr \"cljr\" :clj \"clj\" :default \"default\"))\n");
        DrainUntil(r, () => VarValue("probe.d", "which") != null);

        var got = VarValue("probe.d", "which") as string;
        Console.WriteLine($"[E4] probe.d/which = {Show(got)} (expect \"cljr\")");
        if (got == "cljr") {
            Console.WriteLine("[E4] PASS: .cljc read with reader conditionals, :cljr branch selected");
        } else if (got == null) {
            Fail("E4", "the .cljc never loaded (a reader-conditional form would throw without read-cond enabled)");
        } else {
            Fail("E4", $"wrong reader-conditional branch: {Show(got)} -- Compiler.load's file-name argument shape changed");
        }
    }

    // --- [E5] ------------------------------------------------------------------

    static void E5_ReadFailureRetriesThenGivesUp(ClojureReloader r, string src) {
        const string name = "probe_gone.clj";
        var f = Path.Combine(src, name);
        File.WriteAllText(f, "(ns probe.gone)\n(def z 1)\n");

        // Wait for the mark WITHOUT polling -- a Poll() here would read the file while it
        // still exists and reload it successfully, which is not the branch under test.
        var marked = WaitUntil(() => KeysFor(r, EntryKind.Pending, name) > 0);
        // Holding the file open exclusively makes every File.ReadAllText throw a plain
        // IOException, so the retry branch is reached deterministically rather than by
        // racing a real mid-write read. Opening for read fires no event.
        var unreadable = new ReadLock(f);

        var escapedBefore = Escaped;
        var cap = MaxReadAttempts();
        var deferred = DrainUntil(r, () => LoggedContains("Reload deferred (attempt", name));
        var gaveUp = DrainUntil(r, () => LoggedContains("Reload failed for", name, "unreadable after"));
        // Sampled before the extra polling below, so a straggling event from the setup save
        // cannot open a second budget and inflate the count.
        var deferrals = LoggedCount("Reload deferred (attempt", name);
        var retired = DrainUntilRetired(r, name);
        unreadable.Dispose();

        var escaped = Escaped != escapedBefore;
        Console.WriteLine($"[E5] marked = {marked}; deferrals = {deferrals} (expect {cap - 1}); " +
            $"gave up at the cap = {gaveUp}; bookkeeping retired = {retired}; " +
            $"escaped Poll = {(escaped ? Escaped.GetType().Name : "none")}");
        if (!marked) {
            Fail("E5", "the save was never marked, so the delete could not force a read failure");
        } else if (escaped) {
            Fail("E5", $"a read failure escaped Poll() as {Escaped.GetType().Name} -- " +
                "the catch filter no longer covers it, and this would kill the host tick");
        } else if (!deferred) {
            Fail("E5", "an unreadable file was never retried (the read failure opens no budget)");
        } else if (!gaveUp) {
            Fail("E5", $"the unreadable file was never given up on -- it retries past {cap} attempts forever");
        } else if (deferrals != cap - 1) {
            Fail("E5", $"{deferrals} deferral(s) before give-up, expected {cap - 1} " +
                "(fewer = the budget is being consumed by something other than attempts; " +
                "more = a second event opened a second budget)");
        } else if (!retired) {
            Fail("E5", "giving up left retry bookkeeping behind for the path");
        } else {
            Console.WriteLine($"[E5] PASS: read failure retried {cap - 1}x, gave up at the cap, left nothing behind");
        }
    }

    // --- [E7] ------------------------------------------------------------------

    static void E7_FreshEventResetsTheReadBudget(ClojureReloader r, string src) {
        const string name = "probe_burst.clj";
        var f = Path.Combine(src, name);
        var cap = MaxReadAttempts();

        File.WriteAllText(f, "(ns probe.burst)\n(def b 1)\n");
        if (!WaitUntil(() => KeysFor(r, EntryKind.Pending, name) > 0)) {
            Fail("E7", "the setup save was never marked");
            return;
        }
        // Every read from here on fails with an IOException, and opening fires no event.
        var unreadable = new ReadLock(f);

        // Spend the budget down to its last attempt. Retry state only advances inside
        // Poll(), and the probe owns every Poll, so this stops exactly where it is told to.
        if (!DrainUntil(r, () => AttemptsFor(r, name) == cap - 1)) {
            unreadable.Dispose();
            Fail("E7", $"could not drive the budget to {cap - 1} attempts (got " +
                $"{AttemptsFor(r, name)}) -- attempts are not counted per path");
            return;
        }

        // The reported scenario: a burst of saves, each landing inside the settle window, so
        // the path never settles and no retry can be attempted while the burst runs. Spans
        // well over a second, which is where a wall-clock budget dies -- not to one failed
        // read, but to the writer holding the path back while the window drains.
        unreadable.Dispose(); // the burst has to be able to write
        File.WriteAllText(f, "(ns probe.burst)\n(def b 2)\n");
        for (var i = 0; i < 8; i++) {
            Thread.Sleep(80);
            File.AppendAllText(f, ";; burst " + i + "\n");
        }
        var afterBurst = AttemptsFor(r, name);

        // Let the burst settle BEFORE polling again, so the next Poll's very first act is the
        // hand-out. Poll drains settled paths ahead of due retries, and that ordering is the
        // whole point: if the stale retry fires first it exhausts the budget on its own
        // generation, the give-up clears the entry, and the hand-out that follows then resets
        // nothing and proves nothing. Sleep rather than WaitUntil -- the settle window runs
        // from when the watcher saw the write, not from when we issued it.
        Thread.Sleep(600);
        var takeable = KeysFor(r, EntryKind.Pending, name) > 0;
        // Make the fresh generation's read fail too, otherwise it just succeeds and the
        // budget is cleared for the boring reason instead of by the reset.
        unreadable = new ReadLock(f);

        var gaveUpBefore = LoggedCount("Reload failed for", name, "unreadable after");
        SafePoll(r); // exactly one: hand-out, reset, read failure, deferral
        var attempts = AttemptsFor(r, name);
        var reset = attempts == 1;
        var gaveUp = LoggedCount("Reload failed for", name, "unreadable after") > gaveUpBefore;
        unreadable.Dispose();

        // Liveness, the user-visible half: the next real save still lands. Without the reset
        // the burst's own save was dropped -- silently, and only a further save recovers.
        File.WriteAllText(f, "(ns probe.burst)\n(def b 3)\n");
        var reloaded = DrainUntil(r, () => Eq(VarValue("probe.burst", "b"), 3L));

        Console.WriteLine($"[E7] attempts after a {cap - 1}-attempt budget and an ~800ms write burst = " +
            $"{afterBurst} (expect {cap - 1}: the writer must not spend it); attempts after the fresh " +
            $"event's failed read = {attempts} (expect 1); gave up mid-burst = {gaveUp} (expect false); " +
            $"next save reloaded = {reloaded}");
        if (!takeable) {
            Fail("E7", "the burst's last write never became takeable within 600ms, so the hand-out " +
                "under test never happened (FSW latency, not a reset bug)");
        } else if (afterBurst != cap - 1) {
            Fail("E7", $"the write burst moved the budget from {cap - 1} to {afterBurst} without a " +
                "single read attempt -- the budget is measured in elapsed time, so the writer spends it");
        } else if (gaveUp) {
            Fail("E7", "the hand-out was given up on immediately: the fresh event inherited the old " +
                "budget, so one failed read exhausted it -- and since the give-up is terminal and the " +
                "watcher has already handed the path over, that save is dropped outright");
        } else if (!reset || attempts != 1) {
            Fail("E7", $"the fresh event did not reset the budget (attempts = {attempts}, expect 1) -- " +
                "Poll()'s _retries.Remove for settled paths is missing");
        } else if (!reloaded) {
            Fail("E7", "probe.burst/b never reached 3 -- the reset held but reloads stopped happening");
        } else {
            Console.WriteLine("[E7] PASS: the burst spent no budget, the fresh event reset it, nothing was dropped");
        }
    }

    // --- [E8] ------------------------------------------------------------------

    static void E8_SuccessfulRetryRetiresTheBudget(ClojureReloader r, string root) {
        // Outside the watched source root on purpose: nothing here reaches the watcher, so
        // the retry queue is the only thing that can attempt this path. That is what makes
        // the check deterministic -- staging it through FSW is impossible, since making a
        // file readable again is itself an event.
        var dir = Path.Combine(root, "unwatched");
        Directory.CreateDirectory(dir);
        const string name = "probe_retryok.clj";
        var f = Path.Combine(dir, name);
        File.WriteAllText(f, "(ns probe.retryok)\n(def w 7)\n");

        if (!InjectRetry(r, f, 1)) {
            Fail("E8", "could not inject a retry entry -- the _retries value type changed shape");
            return;
        }
        var loaded = DrainUntil(r, () => Eq(VarValue("probe.retryok", "w"), 7L), 3000);
        var retired = AttemptsFor(r, name) == 0;

        // Rewrite with nobody listening. A retired entry means nothing attempts this path
        // again; a surviving one keeps its due time in the past and fires every Poll.
        File.WriteAllText(f, "(ns probe.retryok)\n(def w 8)\n");
        DrainFor(r, 400);
        var reEvaled = Eq(VarValue("probe.retryok", "w"), 8L);

        Console.WriteLine($"[E8] retry read the file and evaled it = {loaded}; entry retired = {retired}; " +
            $"re-evaled with no new event = {reEvaled} (expect false)");
        if (!loaded) {
            Fail("E8", "an injected retry never ran -- Poll() does not drain its own retry queue");
        } else if (!retired) {
            Fail("E8", "a successful retry left its budget behind (the _retries.Remove after a " +
                "successful read is missing)");
        } else if (reEvaled) {
            Fail("E8", "the path was attempted again with no new event -- a stale retry entry stays " +
                "due forever and re-evals the file on every Poll");
        } else {
            Console.WriteLine("[E8] PASS: a successful retry retired its budget and was not attempted again");
        }
    }

    // --- [E9] ------------------------------------------------------------------

    static void E9_DueRetryDroppedWhilePathIsPendingAgain(ClojureReloader r, string src) {
        const string name = "probe_super.clj";
        var f = Path.Combine(src, name);
        File.WriteAllText(f, "(ns probe.super)\n(def s 1)\n");
        // Wait for the mark without polling (as in [E5]): the retry has to be injected and
        // polled while the mark is fresh, so the Poll below lands inside the settle window.
        if (!WaitUntil(() => KeysFor(r, EntryKind.Pending, name) > 0)) {
            Fail("E9", "the save was never marked, so there was no pending mark to supersede the retry");
            return;
        }
        // The combination -- a due retry AND a pending mark for one path -- cannot be staged
        // through FSW: the stale retry belongs to a previous save's generation, the mark to
        // the save that superseded it. Injected, like [E8], but under the watcher's own key:
        // IsPending is a ContainsKey, and FSW's FullPath is /private-prefixed on macOS.
        var key = PendingKeyFor(r, name);
        if (key == null || !InjectRetry(r, key, 1)) {
            Fail("E9", "could not inject a retry entry under the watcher's pending key");
            return;
        }
        SafePoll(r); // one Poll: the drain takes nothing (unsettled), then the retry comes due
        var superseded = LoggedContains("Reload retry for", name, "superseded");
        var dropped = KeysFor(r, EntryKind.Retrying, name) == 0;
        var stillPending = KeysFor(r, EntryKind.Pending, name) > 0;
        var bound = VarValue("probe.super", "s") != null;

        // Liveness, the half that makes dropping safe: the mark is still queued, so the
        // settled drain that follows must eval the save the retry was dropped for.
        var reloaded = DrainUntil(r, () => Eq(VarValue("probe.super", "s"), 1L));

        Console.WriteLine($"[E9] superseded logged = {superseded}; entry dropped = {dropped}; " +
            $"evaled by the retry = {bound} (expect False); save still reloaded = {reloaded}");
        if (superseded && dropped && !bound && reloaded) {
            Console.WriteLine("[E9] PASS: the due retry was dropped for the pending save, which then reloaded");
        } else if (bound && stillPending) {
            Fail("E9", "the due retry read and evaled the file while a newer save's mark was pending -- " +
                "the IsPending supersede check is gone, so a retry can read a mid-burst file torn");
        } else if (bound) {
            Fail("E9", "the mark settled and drained before the probe's Poll landed -- a staging race " +
                "(the settle window elapsed between WaitUntil and Poll), not a verdict on the check");
        } else if (!superseded) {
            Fail("E9", "the retry neither ran nor logged as superseded -- dropped silently, or the log format changed");
        } else if (!dropped) {
            Fail("E9", "the superseded retry left its _retries entry behind");
        } else {
            Fail("E9", "the superseded save never reloaded -- dropping the retry dropped the save with it");
        }
    }

    // --- [E10] -----------------------------------------------------------------

    static void E10_DisposeFromCallbackStopsTheDrain(string src) {
        var a = Path.Combine(src, "dispose_a.clj");
        var b = Path.Combine(src, "dispose_b.clj");
        using (var r2 = new ClojureReloader(new[] { src }, Sink, OnChanged)) {
            try {
                // OnChanged disposes for either name (see OnChanged), so whichever file the
                // drain evals first tears the reloader down mid-loop.
                DisposeTarget = r2;
                File.WriteAllText(a, "(ns probe.disp1)\n(def x 1)\n");
                File.WriteAllText(b, "(ns probe.disp2)\n(def x 1)\n");

                // Both marks must be settled before the Poll, so one TakeSettled() hands over
                // both and the second path is still in the list when Dispose returns. Sleep
                // rather than drain: a Poll here would consume the first mark on its own.
                if (!WaitUntil(() => KeysFor(r2, EntryKind.Pending, "dispose_a.clj") > 0
                        && KeysFor(r2, EntryKind.Pending, "dispose_b.clj") > 0)) {
                    Fail("E10", "the two saves were not both marked, so one drain could not carry both");
                    return;
                }
                Thread.Sleep(600);
                SafePoll(r2);

                var bound = (VarValue("probe.disp1", "x") != null ? 1 : 0)
                    + (VarValue("probe.disp2", "x") != null ? 1 : 0);
                var disposed = IsDisposed(r2);

                Console.WriteLine($"[E10] files evaled before/after the in-callback Dispose = {bound} " +
                    $"(expect 1); reloader actually disposed = {disposed}");
                if (!disposed) {
                    Fail("E10", "onChanged never disposed the reloader, so nothing was under test");
                } else if (bound == 0) {
                    Fail("E10", "neither file evaled -- the drain never carried them (staging), " +
                        "not a verdict on the guard");
                } else if (bound == 2) {
                    Fail("E10", "the drain kept evaluating after Dispose() returned: the _disposed " +
                        "guard in Attempt() is gone, so a torn-down reloader keeps reloading");
                } else {
                    Console.WriteLine("[E10] PASS: Dispose() inside onChanged stopped the rest of the drain");
                }
            } finally {
                DisposeTarget = null;
            }
        }
    }

    static void E11_VanishedFileIsDroppedWithoutRetrying(ClojureReloader r, string src) {
        const string name = "probe_vanish.clj";
        var f = Path.Combine(src, name);
        File.WriteAllText(f, "(ns probe.vanish)\n(def v 1)\n");
        if (!WaitUntil(() => KeysFor(r, EntryKind.Pending, name) > 0)) {
            Fail("E11", "the setup save was never marked, so the delete could not race the read");
            return;
        }
        File.Delete(f); // Deleted is not subscribed, so this fires no event of its own

        var escapedBefore = Escaped;
        var failedBefore = LoggedCount("Reload failed for", name);
        var deferredBefore = LoggedCount("Reload deferred (attempt", name);
        // Drain well past the point a retry would have come due (MaxReadAttempts x the
        // backoff), so "never retried" is a measurement and not an early look.
        DrainFor(r, 1500);

        var retries = KeysFor(r, EntryKind.Retrying, name);
        var failed = LoggedCount("Reload failed for", name) - failedBefore;
        var deferred = LoggedCount("Reload deferred (attempt", name) - deferredBefore;
        var escaped = Escaped != escapedBefore;
        Console.WriteLine($"[E11] retry entries = {retries} (expect 0); 'Reload deferred' lines = " +
            $"{deferred} (expect 0); 'Reload failed' lines = {failed} (expect 0); " +
            $"escaped Poll = {(escaped ? Escaped.GetType().Name : "none")}");
        if (escaped) {
            Fail("E11", $"a vanished file escaped Poll() as {Escaped.GetType().Name} -- the narrow " +
                "catch was added ahead of the retry filter but does not cover what the read throws");
        } else if (retries != 0) {
            Fail("E11", $"a vanished file opened {retries} retry entry(ies) -- FileNotFoundException " +
                "derives from IOException, so it must be caught ahead of the retry filter, not by it");
        } else if (deferred != 0) {
            Fail("E11", $"a vanished file was deferred {deferred}x -- it is not coming back, and the " +
                "budget is for files that are merely busy");
        } else if (failed != 0) {
            Fail("E11", "a vanished file logged 'Reload failed' -- in the Unity console that is " +
                "indistinguishable from a real compile error, against a file the user never edited");
        } else {
            Console.WriteLine("[E11] PASS: the vanished file was dropped silently -- no budget, no log line");
        }

        // Same branch, reached the way a user actually reaches it. Deleted is not
        // subscribed, so the obvious reading is that a plain delete cannot get here at
        // all -- but the FSEvents-backed watcher raises Changed for one too, so it marks
        // the path like any other event and the read then finds nothing. Reported rather
        // than asserted: a backend that reports deletes precisely (inotify) would not
        // mark it, and the silence below is the point either way.
        const string plain = "probe_vanish_plain.clj";
        var g = Path.Combine(src, plain);
        File.WriteAllText(g, "(ns probe.vanish-plain)\n(def v 1)\n");
        if (!DrainUntil(r, () => Eq(VarValue("probe.vanish-plain", "v"), 1L))) {
            Fail("E11", "the setup save never reloaded, so the plain delete below proves nothing");
            return;
        }
        DrainUntilRetired(r, plain); // settle: nothing of this path left in either table

        failedBefore = LoggedCount("Reload failed for", plain);
        deferredBefore = LoggedCount("Reload deferred (attempt", plain);
        File.Delete(g);
        var markedByDelete = WaitUntil(() => KeysFor(r, EntryKind.Pending, plain) > 0, 2500);
        DrainFor(r, 1500);

        var plainFailed = LoggedCount("Reload failed for", plain) - failedBefore;
        var plainDeferred = LoggedCount("Reload deferred (attempt", plain) - deferredBefore;
        var plainRetries = KeysFor(r, EntryKind.Retrying, plain);
        Console.WriteLine($"[E11b] a plain delete marked the path = {markedByDelete} (platform-dependent, " +
            $"not asserted); retry entries = {plainRetries} (expect 0); 'Reload deferred' = " +
            $"{plainDeferred} (expect 0); 'Reload failed' = {plainFailed} (expect 0)");
        if (plainRetries != 0 || plainDeferred != 0 || plainFailed != 0) {
            Fail("E11b", "deleting a file the user had been editing produced retry traffic or a log " +
                "line -- deleting a source file is not an error and must be silent");
        } else {
            Console.WriteLine("[E11b] PASS: deleting a watched source file was silent");
        }
    }

    static void E12_EditorTempNamesAreNeverMarked(ClojureReloader r, string src) {
        // Real files, not symlinks: the gate is lexical and has to reject these on the name
        // alone, before any I/O gets the chance to decide for it. On Windows Emacs writes
        // its lock as a regular file with exactly this name, and it reads back as content.
        var rejected = new[] {
            ".#probe_lock.clj",   // Emacs lock, the reported case: extension is still .clj
            ".#probe_lock.cljc",
            "#probe_auto.clj#",   // Emacs auto-save
            "probe_backup.clj~",  // Emacs backup
        };
        foreach (var n in rejected) {
            File.WriteAllText(Path.Combine(src, n), "(ns probe.nope)\n(def loaded 1)\n");
        }
        // A real save issued after them: once this one has reloaded, the rejected names have
        // had at least as long to be marked, so "none pending" is a measurement, not a race.
        const string real = "probe_gate_ok.clj";
        File.WriteAllText(Path.Combine(src, real), "(ns probe.gate-ok)\n(def ok 1)\n");
        var realReloaded = DrainUntil(r, () => Eq(VarValue("probe.gate-ok", "ok"), 1L));

        // The accepting half of the same gate: the extension match is case-insensitive, so
        // an upper-cased one still reloads. On a case-insensitive volume `Probe_Case.CLJ`
        // is an ordinary loadable file, and an ordinal == would drop it silently.
        File.WriteAllText(Path.Combine(src, "probe_case.CLJ"), "(ns probe.case)\n(def c 1)\n");
        var upperReloaded = DrainUntil(r, () => Eq(VarValue("probe.case", "c"), 1L));

        var marked = 0;
        var loggedFor = 0;
        foreach (var n in rejected) {
            marked += Math.Max(0, KeysFor(r, EntryKind.Any, n));
            loggedFor += LoggedCount(n);
        }
        var evaled = VarValue("probe.nope", "loaded");

        Console.WriteLine($"[E12] real save reloaded = {realReloaded}; upper-case ext reloaded = " +
            $"{upperReloaded}; temp names tabled = {marked} (expect 0); log lines naming one = " +
            $"{loggedFor} (expect 0); probe.nope/loaded = {Show(evaled)} (expect <unbound>)");
        if (!realReloaded) {
            Fail("E12", "the ordinary save never reloaded, so the gate was never given a fair " +
                "chance to have marked the temp names (watcher setup, not a gate bug)");
        } else if (marked != 0) {
            Fail("E12", $"{marked} editor temp file(s) reached the reloader's tables -- " +
                "Path.GetExtension(\".#core.clj\") is \".clj\", so an extension-only gate admits them");
        } else if (evaled != null) {
            Fail("E12", "an editor temp file was evaluated -- its content is not Clojure the user wrote");
        } else if (loggedFor != 0) {
            Fail("E12", $"{loggedFor} log line(s) name an editor temp file: rejected, but still noisy");
        } else if (!upperReloaded) {
            Fail("E12", "a `.CLJ` file never reloaded -- the extension match is ordinal, so it drops " +
                "a file the filesystem and Compiler.load both consider perfectly ordinary");
        } else {
            Console.WriteLine("[E12] PASS: lock, auto-save and backup names stayed out; `.CLJ` still reloads");
        }
    }

    // --- read-failure staging --------------------------------------------------

    // Makes every read of `path` fail with a plain IOException for as long as it is held,
    // by taking an exclusive handle. That is the production scenario the retry budget
    // exists for: an editor rewriting the file holds it briefly and the reloader's read
    // collides with the write.
    //
    // Deleting the file instead -- what [E2b]/[E5]/[E7] did until the reloader learned to
    // tell the two apart -- no longer reaches the retry branch at all: a missing file
    // raises FileNotFoundException, which is dropped as "gone, nothing to reload" ([E11]).
    // Opening a file for read fires no FileSystemWatcher event, so holding this does not
    // disturb the pending table.
    sealed class ReadLock : IDisposable {
        FileStream _held;

        public ReadLock(string path) {
            _held = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.None);
        }

        public void Dispose() {
            if (_held != null) {
                _held.Dispose();
                _held = null;
            }
        }
    }

    // --- drain helpers ---------------------------------------------------------

    // Poll() until `done` or the deadline. Poll() is the thing under test, so an
    // exception escaping it is recorded rather than thrown: [E3] asserts on it, and
    // the other checks would otherwise die here with a confusing stack.
    static bool DrainUntil(ClojureReloader r, Func<bool> done, long deadlineMs = 8000) {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < deadlineMs) {
            if (!SafePoll(r)) {
                return false;
            }
            if (done()) {
                return true;
            }
            Thread.Sleep(20);
        }
        return done();
    }

    static void DrainFor(ClojureReloader r, long ms) {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < ms) {
            if (!SafePoll(r)) {
                return;
            }
            Thread.Sleep(20);
        }
    }

    // Poll until nothing is left for `fileName` in either per-path table -- the retry budget
    // and the pending queue. Call it only after the path's outcome has been observed: both
    // tables are trivially empty before the first event arrives.
    static bool DrainUntilRetired(ClojureReloader r, string fileName, long deadlineMs = 4000) {
        return DrainUntil(r, () => KeysFor(r, EntryKind.Any, fileName) == 0, deadlineMs);
    }

    // Spin without polling, for the setup step in [E5] where a Poll() would consume the
    // very mark being waited on.
    static bool WaitUntil(Func<bool> done, long deadlineMs = 4000) {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < deadlineMs) {
            if (done()) {
                return true;
            }
            Thread.Sleep(20);
        }
        return done();
    }

    static bool SafePoll(ClojureReloader r) {
        try {
            r.Poll();
            return true;
        } catch (Exception ex) {
            if (Escaped == null) {
                Escaped = ex;
            }
            return false;
        }
    }

    // --- clojure / reflection helpers -----------------------------------------

    // Read a var without interning one: RT.var would create an unbound var for a ns
    // that never loaded, turning "did not reload" into a confusing deref error.
    static object VarValue(string ns, string name) {
        var n = Namespace.find(Symbol.intern(ns));
        if (n == null) {
            return null;
        }
        var v = n.FindInternedVar(Symbol.intern(name));
        if (v == null || !v.isBound) {
            return null;
        }
        return v.deref();
    }

    // The two per-path tables, plus the reloader's cap. Private names, so a rename breaks
    // this probe -- Field() below says so out loud instead of NullReferencing.
    const string PendingField = "_pending";   // on DebouncedFileWatcher
    const string RetriesField = "_retries";   // on ClojureReloader
    const string CapField = "MaxReadAttempts";
    const string AttemptsField = "Attempts";  // on the _retries value struct
    const string DueField = "DueMs";

    // Which table KeysFor should count in. The retry queue lives in the reloader and the
    // pending mark in the watcher, so these are two tables, not two views of one.
    enum EntryKind { Pending, Retrying, Any }

    static FieldInfo Field(Type t, string name) {
        var f = t.GetField(name, BindingFlags.Instance | BindingFlags.Static |
            BindingFlags.Public | BindingFlags.NonPublic);
        if (f == null) {
            throw new InvalidOperationException(
                $"{t.Name} has no field '{name}' -- it was renamed; update this probe's field constants.");
        }
        return f;
    }

    static object WatcherOf(ClojureReloader r) {
        return Field(typeof(ClojureReloader), "_watcher").GetValue(r);
    }

    // The reloader's own tombstone. _watcher is readonly and survives Dispose(), so the
    // roots table on it is no longer the way to tell a disposed reloader from a live one.
    static bool IsDisposed(ClojureReloader r) {
        return (bool)Field(typeof(ClojureReloader), "_disposed").GetValue(r);
    }

    // Count entries for a file across the tables `want` selects, matched on file name:
    // the watcher keys on FileSystemWatcher's FullPath, which on macOS may be the
    // /private-prefixed form of the temp path we wrote to. Returns -1 when the reloader
    // holds no watcher, so that setup failure is reported as itself rather than as a
    // stray table entry.
    static int KeysFor(ClojureReloader r, EntryKind want, string fileName) {
        var w = WatcherOf(r);
        if (w == null) {
            return -1;
        }
        var n = 0;
        if (want == EntryKind.Pending || want == EntryKind.Any) {
            n += CountKeys(
                (IDictionary)Field(typeof(DebouncedFileWatcher), PendingField).GetValue(w), fileName);
        }
        if (want == EntryKind.Retrying || want == EntryKind.Any) {
            n += CountKeys(Retries(r), fileName);
        }
        return n;
    }

    // The watcher's own key for a pending file: FSW's FullPath, which on macOS may be the
    // /private-prefixed form of the temp path the probe wrote to. [E9] injects a retry that
    // must collide with this key, not with the probe's spelling of the same file.
    static string PendingKeyFor(ClojureReloader r, string fileName) {
        var w = WatcherOf(r);
        if (w == null) {
            return null;
        }
        var pending = (IDictionary)Field(typeof(DebouncedFileWatcher), PendingField).GetValue(w);
        foreach (DictionaryEntry e in pending) {
            if (Path.GetFileName((string)e.Key) == fileName) {
                return (string)e.Key;
            }
        }
        return null;
    }

    static IDictionary Retries(ClojureReloader r) {
        return (IDictionary)Field(typeof(ClojureReloader), RetriesField).GetValue(r);
    }

    // The consumer-side cap, read rather than hardcoded: retuning it must not silently
    // turn [E5]/[E7]'s expected counts into a lie.
    static int MaxReadAttempts() {
        return (int)Field(typeof(ClojureReloader), CapField).GetValue(null);
    }

    // How many consecutive failed reads the reloader has recorded for a file. 0 = no entry,
    // which is also what a retired budget looks like -- the callers distinguish the two by
    // what they did just before.
    static int AttemptsFor(ClojureReloader r, string fileName) {
        var best = 0;
        foreach (DictionaryEntry e in Retries(r)) {
            if (Path.GetFileName((string)e.Key) != fileName) {
                continue;
            }
            var f = e.Value.GetType().GetField(AttemptsField);
            if (f == null) {
                throw new InvalidOperationException(
                    $"a retry entry has no field '{AttemptsField}' -- update this probe's constants.");
            }
            var n = (int)f.GetValue(e.Value);
            if (n > best) {
                best = n;
            }
        }
        return best;
    }

    // Put a path on the retry queue directly, due immediately. [E8] needs a retry whose read
    // succeeds, and there is no way to stage that through the watcher.
    static bool InjectRetry(ClojureReloader r, string path, int attempts) {
        var field = Field(typeof(ClojureReloader), RetriesField);
        var entryType = field.FieldType.GetGenericArguments()[1];
        var af = entryType.GetField(AttemptsField);
        var df = entryType.GetField(DueField);
        if (af == null || df == null) {
            return false;
        }
        var entry = Activator.CreateInstance(entryType); // boxed struct: SetValue mutates the box
        af.SetValue(entry, attempts);
        df.SetValue(entry, 0L); // the reloader's Stopwatch only moves forward, so 0 is always due
        ((IDictionary)field.GetValue(r))[path] = entry;
        return true;
    }

    static int CountKeys(IDictionary dict, string fileName) {
        var n = 0;
        foreach (DictionaryEntry e in dict) {
            if (Path.GetFileName((string)e.Key) == fileName) {
                n++;
            }
        }
        return n;
    }

    static bool Eq(object got, long want) {
        return got is long && (long)got == want;
    }

    static string Show(object o) {
        return o == null ? "<unbound>" : (o is string ? "\"" + o + "\"" : o.ToString());
    }

    // --- reloader callbacks ----------------------------------------------------

    static void Sink(string message) {
        lock (LogLock) {
            Log.Add(message);
        }
    }

    // Set by [E10] only, for the window in which disposing from the callback is the point.
    static ClojureReloader DisposeTarget;

    static void OnChanged(string path) {
        var name = Path.GetFileName(path);
        if (name == "throwy.clj") {
            throw new InvalidOperationException("deliberate onChanged failure ([E3])");
        }
        if (name == "dispose_a.clj" || name == "dispose_b.clj") {
            DisposeTarget?.Dispose(); // [E10]: tear down mid-drain, from inside the drain
        }
    }

    static bool LoggedContains(params string[] fragments) {
        return LoggedCount(fragments) > 0;
    }

    // Lines carrying every fragment. [E5] asserts an exact number of deferrals, so counting
    // matters, not just presence.
    static int LoggedCount(params string[] fragments) {
        var n = 0;
        lock (LogLock) {
            foreach (var m in Log) {
                var all = true;
                foreach (var fragment in fragments) {
                    if (!m.Contains(fragment)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    n++;
                }
            }
        }
        return n;
    }

    static void Fail(string tag, string why) {
        Console.WriteLine($"[{tag}] FAIL: {why}");
        failures++;
    }

}
