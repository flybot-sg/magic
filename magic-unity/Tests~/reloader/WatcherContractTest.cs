using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading;
using Magic.Unity;

// Probe 6 — contract tests for DebouncedFileWatcher behaviors that were previously
// verified only by code review. Compiled against the real source; bare csc + mono,
// no Clojure, no Unity.
//
//   [D1-D3] root dedupe & segment-aware covering (guards the #8 fix: integrated's
//           reload.edn maps six namespaces to one directory; naive string-prefix
//           covering would also swallow sibling dirs like foo/barbaz)
//   [Q1-Q5] GONE (2026-09-01, second pass): the requeue contract went out with the API.
//           Retry queueing moved wholly into ClojureReloader, so the watcher has one write
//           door (the OS event stream) and cannot re-present a path at all. What the [Q]
//           checks were really protecting -- that no retry cap lives here -- is now
//           structural. The consumer-side budget is Probe 7's [E5]/[E7].
//   [S1]    coalescing/liveness STRESS: 60 rapid writes -> the final version is always
//           delivered after quiescence. Real FSW load; measured, it does NOT discriminate
//           the conditional remove (see [S2]) because macOS FSW latency keeps events out
//           of the microsecond claim window.
//   [S2]    no-lost-update: the claim in TakeSettled() must be a compare-and-remove. A
//           large table widens the snapshot->remove window; one mark is then fired from
//           another thread against an in-flight drain, and the re-marked path must still
//           come back. Table-only (no roots armed): this tests the claim protocol, not FSW.
//   [P1]    IsPending: false before a mark, true from the mark to the hand-out (settled
//           or not), false after TakeSettled removes it; a gate-rejected path never reads
//           pending. Table-only, like [S2]: FSW adds nothing to a ContainsKey contract.
//   [GATE]  cross-cutting: TakeSettled() never returns a path outside each test's targets
//           (editor droppings, sibling leaks).
class WatcherContractTest {

    static readonly Func<string, bool> Gate = p => {
        var e = Path.GetExtension(p);
        return e == ".clj" || e == ".cljc" || e == ".cljr";
    };

    static int _failures;
    static readonly List<string> _unexpected = new List<string>();

    static int Main() {
        var root = Path.Combine(Path.GetTempPath(), "watchcontract-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try {
            DedupeChildThenParent(root);
            DedupeParentThenChild(root);
            SiblingPrefixNotCovered(root);
            NoLostUpdateStress(root);
            LostUpdateOnClaim();
            IsPendingLifecycle();
        } finally {
            try { Directory.Delete(root, true); } catch { /* best effort */ }
        }

        if (_unexpected.Count == 0) {
            Pass("GATE", "TakeSettled() only ever returned expected targets");
        } else {
            Fail("GATE", $"TakeSettled() returned unexpected path(s): {string.Join(", ", _unexpected)}");
        }

        if (_failures == 0) {
            Console.WriteLine("PROBE6-CONTRACT-VERIFIED");
            return 0;
        }
        Console.WriteLine("PROBE6-REGRESSION");
        return 1;
    }

    // --- tests ---

    static void DedupeChildThenParent(string root) {
        var dir = Path.Combine(root, "d1");
        var sub = Path.Combine(dir, "inner");
        Directory.CreateDirectory(sub);
        var inChild = Path.Combine(sub, "a.cljc");
        var inParent = Path.Combine(dir, "b.cljc");
        File.WriteAllText(inChild, "(ns a)\n");
        File.WriteAllText(inParent, "(ns b)\n");
        using (var w = new DebouncedFileWatcher(Gate, null)) {
            w.AddRoot(sub);
            w.AddRoot(dir); // shallower root must REPLACE the covered child watcher
            Thread.Sleep(500);
            Touch(inChild);
            var n = Drain(w, new[] { inChild })[Full(inChild)];
            Check("D1", n == 1, $"save under deduped child root delivered {n}x (expect 1; 2 = duplicate watcher survived)");
            Touch(inParent);
            var n2 = Drain(w, new[] { inParent }, tolerate: new[] { inChild })[Full(inParent)];
            Check("D1b", n2 == 1, $"save under the replacing parent root delivered {n2}x (expect 1; 0 = parent never armed)");
        }
    }

    static void DedupeParentThenChild(string root) {
        var dir = Path.Combine(root, "d2");
        var sub = Path.Combine(dir, "inner");
        Directory.CreateDirectory(sub);
        var inChild = Path.Combine(sub, "a.cljc");
        File.WriteAllText(inChild, "(ns a)\n");
        using (var w = new DebouncedFileWatcher(Gate, null)) {
            w.AddRoot(dir);
            w.AddRoot(sub); // already covered by the ancestor: must be skipped
            Thread.Sleep(500);
            Touch(inChild);
            var n = Drain(w, new[] { inChild })[Full(inChild)];
            Check("D2", n == 1, $"save under covered child root delivered {n}x (expect 1; 2 = redundant watcher armed)");
        }
    }

    static void SiblingPrefixNotCovered(string root) {
        // foo/bar must never cover foo/barbaz: with naive string-prefix logic the
        // second AddRoot would be skipped and saves under it would vanish.
        var bar = Path.Combine(root, "d3", "bar");
        var barbaz = Path.Combine(root, "d3", "barbaz");
        Directory.CreateDirectory(bar);
        Directory.CreateDirectory(barbaz);
        var fa = Path.Combine(bar, "a.cljc");
        var fb = Path.Combine(barbaz, "b.cljc");
        File.WriteAllText(fa, "(ns a)\n");
        File.WriteAllText(fb, "(ns b)\n");
        using (var w = new DebouncedFileWatcher(Gate, null)) {
            w.AddRoot(bar);
            w.AddRoot(barbaz);
            Thread.Sleep(500);
            Touch(fa);
            Touch(fb);
            var counts = Drain(w, new[] { fa, fb });
            Check("D3", counts[Full(fa)] == 1 && counts[Full(fb)] == 1,
                $"bar={counts[Full(fa)]} barbaz={counts[Full(fb)]} (expect 1/1; barbaz=0 = prefix-covered by sibling)");
        }
    }

    static void NoLostUpdateStress(string root) {
        var dir = Path.Combine(root, "s");
        Directory.CreateDirectory(dir);
        var t = Path.Combine(dir, "x.cljc");
        File.WriteAllText(t, "v0");
        using (var w = new DebouncedFileWatcher(Gate, null)) {
            w.AddRoot(dir);
            Thread.Sleep(500);
            const int n = 60;
            string lastDrained = null;
            int drains = 0;
            for (var i = 1; i <= n; i++) {
                File.WriteAllText(t, "v" + i);
                foreach (var p in w.TakeSettled()) {
                    if (PathEq(p, t)) {
                        lastDrained = File.ReadAllText(p);
                        drains++;
                    } else {
                        _unexpected.Add(p);
                    }
                }
                Thread.Sleep(5);
            }
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 8000 && lastDrained != "v" + n) {
                foreach (var p in w.TakeSettled()) {
                    if (PathEq(p, t)) {
                        lastDrained = File.ReadAllText(p);
                        drains++;
                    } else {
                        _unexpected.Add(p);
                    }
                }
                Thread.Sleep(20);
            }
            Check("S1", lastDrained == "v" + n,
                $"final of {n} rapid writes drained after {drains} delivery(ies): last={lastDrained ?? "none"} (expect v{n}; anything else = the last save never surfaced)");
        }
    }

    // Races a re-mark against a drain that is already in flight. Needs no files and no
    // roots: real FSW latency keeps events out of the microsecond claim window this is
    // trying to hit, so it marks through the private door (see Mark below) to exercise the
    // claim protocol directly. Junk entries are there only to lengthen the enumerate+claim
    // walk, which is the window a plain TryRemove would lose a mark in. (Longer still when
    // TakeSettled snapshotted and sorted first; the window survived that going away —
    // 4000 entries between the enumerator seeing the target and the claim reaching it.)
    static void LostUpdateOnClaim() {
        const string target = "/lostupdate/target.cljc";
        const int rounds = 150;
        const int junk = 4000;
        var lost = 0;
        using (var w = new DebouncedFileWatcher(Gate, null, 50)) {
            for (var r = 0; r < rounds; r++) {
                for (var i = 0; i < junk; i++) {
                    Mark(w, "/lostupdate/j" + i + ".cljc");
                }
                Mark(w, target);
                Thread.Sleep(60); // let the whole table settle

                var fire = new ManualResetEventSlim(false);
                var racer = new Thread(() => { fire.Wait(); Mark(w, target); });
                racer.Start();

                var handedOut = false;
                fire.Set();
                foreach (var p in w.TakeSettled()) {
                    if (p == target) {
                        handedOut = true;
                    }
                }
                racer.Join();

                // The racing mark either lost the claim (still queued) or landed after the
                // removal (queued afresh). Either way the path must come back.
                Thread.Sleep(70);
                var returned = false;
                foreach (var p in w.TakeSettled()) {
                    if (p == target) {
                        returned = true;
                    }
                }
                if (handedOut && !returned) {
                    lost++;
                }
            }
        }
        Check("S2", lost == 0,
            $"re-mark lost on {lost}/{rounds} raced claims (expect 0; >0 = TakeSettled's " +
            "claim is not a compare-and-remove)");
    }

    // IsPending is what the reloader's supersede check rides on (a due retry is dropped
    // while a newer save's mark is queued), so the contract that matters is the pending
    // *interval*: it must open at the mark -- settled or not -- and close only at the
    // hand-out. A query that turned false at settle would un-gate exactly the mid-write
    // window the check exists for.
    static void IsPendingLifecycle() {
        const string target = "/ispending/t.cljc";
        const string rejected = "/ispending/nope.txt";
        using (var w = new DebouncedFileWatcher(Gate, null, 50)) {
            var before = w.IsPending(target);
            Check("P1", !before, $"pending before any mark = {before} (expect False)");
            Mark(w, target);
            var inWindow = w.IsPending(target);
            Check("P1b", inWindow, $"pending inside the settle window = {inWindow} " +
                "(expect True -- this is where the supersede check needs it)");
            Thread.Sleep(60); // past the settle window: settled, not yet drained
            var settled = w.IsPending(target);
            Check("P1c", settled, $"pending once settled, before the hand-out = {settled} (expect True)");
            var handed = false;
            foreach (var p in w.TakeSettled()) {
                if (p == target) {
                    handed = true;
                } else {
                    _unexpected.Add(p);
                }
            }
            var after = w.IsPending(target);
            Check("P1d", handed && !after,
                $"handed out = {handed}, pending after = {after} (expect True/False: the hand-out is the removal)");
            Mark(w, rejected);
            var rej = w.IsPending(rejected);
            Check("P1e", !rej, $"gate-rejected path pending = {rej} (expect False: Mark must not table what _accept refuses)");
        }
    }

    // --- helpers ---

    // The watcher's only write door is private now that Requeue is gone. [S2] needs it:
    // it deliberately arms no roots, so there is no FS event to mark with.
    static readonly MethodInfo MarkMethod = typeof(DebouncedFileWatcher)
        .GetMethod("Mark", BindingFlags.Instance | BindingFlags.NonPublic);

    static void Mark(DebouncedFileWatcher w, string path) {
        if (MarkMethod == null) {
            throw new InvalidOperationException(
                "DebouncedFileWatcher has no private Mark(string) -- renamed? update this probe.");
        }
        MarkMethod.Invoke(w, new object[] { path });
    }

    // Drain until every target has appeared at least once AND nothing new has arrived
    // for `quietMs` (so straggling duplicates are still counted), or the deadline hits.
    // Non-target, non-tolerated results are recorded as [GATE] violations.
    static Dictionary<string, int> Drain(DebouncedFileWatcher w, string[] targets,
        string[] tolerate = null, long deadlineMs = 8000, long quietMs = 700) {
        var counts = new Dictionary<string, int>(StringComparer.Ordinal);
        foreach (var t in targets) {
            counts[Full(t)] = 0;
        }
        var tolerated = new HashSet<string>(StringComparer.Ordinal);
        if (tolerate != null) {
            foreach (var t in tolerate) {
                tolerated.Add(Full(t));
            }
        }
        var sw = Stopwatch.StartNew();
        long lastHit = -1;
        while (sw.ElapsedMilliseconds < deadlineMs) {
            foreach (var p in w.TakeSettled()) {
                var full = Full(p);
                if (counts.ContainsKey(full)) {
                    counts[full]++;
                    lastHit = sw.ElapsedMilliseconds;
                } else if (!tolerated.Contains(full)) {
                    _unexpected.Add(p);
                }
            }
            var allSeen = true;
            foreach (var v in counts.Values) {
                if (v == 0) {
                    allSeen = false;
                    break;
                }
            }
            if (allSeen && lastHit >= 0 && sw.ElapsedMilliseconds - lastHit > quietMs) {
                break;
            }
            Thread.Sleep(20);
        }
        return counts;
    }

    static void Touch(string path) {
        File.AppendAllText(path, ";; touch\n");
    }

    static string Full(string p) {
        return Path.GetFullPath(p);
    }

    static bool PathEq(string a, string b) {
        return string.Equals(Full(a), Full(b), StringComparison.Ordinal);
    }

    static void Check(string label, bool ok, string detail) {
        if (ok) {
            Pass(label, detail);
        } else {
            Fail(label, detail);
        }
    }

    static void Pass(string label, string detail) {
        Console.WriteLine($"[{label}] PASS: {detail}");
    }

    static void Fail(string label, string detail) {
        Console.WriteLine($"[{label}] FAIL: {detail}");
        _failures++;
    }

}
