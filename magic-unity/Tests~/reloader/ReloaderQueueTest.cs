using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;
using Magic.Unity;

// Probe 3 — asserts the v2 FIX (compiled with the real DebouncedFileWatcher.cs).
// Unlike Probes 1-2 (which measure the FSW *environment* and keep passing after the
// fix), this drives the reloader's debounce/drain logic and FAILS if that regresses.
//
//   [A] 5 vim-style saves, drained one at a time -> each coalesces to exactly ONE
//       TakeSettled() result (the old design evaluated every raw event, i.e. ~2 per save).
//   [B] one rename-over-target save using a NON-clj temp name (probe.tmp -> probe.cljc)
//       -> the target appears exactly once. This is the acceptance test for the
//       Filter="*" + in-handler gate decision (#7): the old Filter="*.clj*" config saw
//       ZERO events for this save style (measured in Probe 1).
//
// Drain-until-quiet loops (no fixed sleeps that assume FSEvents latency); no thread-id
// assertions (single-threaded here, they'd only test the harness).
class ReloaderQueueTest {

    static readonly Func<string, bool> Gate = p => {
        var e = Path.GetExtension(p);
        return e == ".clj" || e == ".cljc" || e == ".cljr";
    };

    // Any TakeSettled() result that is NOT the target is a gate/dedupe leak (e.g. the vim
    // backup probe.cljc~ slipping past the extension gate) — collected here and
    // asserted at the end, so leaks can't hide behind the target-only counters.
    static readonly List<string> Unexpected = new List<string>();

    static int Main() {
        var dir = Path.Combine(Path.GetTempPath(), "reloadqtest-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        var target = Path.Combine(dir, "probe.cljc");
        File.WriteAllText(target, "(ns t)\n");

        int failures = 0;
        using (var w = new DebouncedFileWatcher(Gate, Console.Error.WriteLine, 150)) {
            w.AddRoot(dir);
            Thread.Sleep(500); // let the watcher arm before the first save

            // --- Test A: duplicate-event coalescing ---
            var perSave = new List<int>();
            int total = 0;
            for (int i = 1; i <= 5; i++) {
                VimSave(target, i);
                int got = DrainOneSave(w, target);
                perSave.Add(got);
                total += got;
            }
            Console.WriteLine($"[A] vim saves: per-save = [{string.Join(",", perSave)}], total = {total} (expect 5)");
            if (total == 5) {
                Console.WriteLine("[A] PASS: each save coalesced to exactly one reload");
            } else {
                Console.WriteLine("[A] FAIL: expected 5 (one per save); duplicate coalescing broken");
                failures++;
            }

            // --- Test B: rename-over-target with a non-clj temp name ---
            var tmp = Path.Combine(dir, "probe.tmp");
            File.WriteAllText(tmp, "(ns t)\n;; rename-over\n");
            RenameOver(tmp, target);
            int gotB = DrainOneSave(w, target);
            Console.WriteLine($"[B] rename-over (non-clj temp): target results = {gotB} (expect 1)");
            if (gotB == 1) {
                Console.WriteLine("[B] PASS: rename-over delivered once (Filter=\"*\" + gate)");
            } else {
                Console.WriteLine("[B] FAIL: rename-over not delivered exactly once");
                failures++;
            }

            // --- Test C: the extension gate held ---
            if (Unexpected.Count == 0) {
                Console.WriteLine("[C] PASS: gate held — TakeSettled() only ever returned the target (no .cljc~/.tmp leaks)");
            } else {
                Console.WriteLine($"[C] FAIL: TakeSettled() returned unexpected path(s): {string.Join(", ", Unexpected)}");
                failures++;
            }
        }
        try { Directory.Delete(dir, true); } catch { /* best effort */ }

        if (failures == 0) {
            Console.WriteLine("PROBE3-FIX-VERIFIED");
            return 0;
        }
        Console.WriteLine("PROBE3-REGRESSION");
        return 1;
    }

    // Drain everything attributable to one save: poll until we have at least one result
    // for the target and then a quiet stretch longer than the settle window, so a
    // straggling duplicate would be caught. Hard-capped so a no-event backend can't hang.
    static int DrainOneSave(DebouncedFileWatcher w, string target) {
        const long deadlineMs = 8000;
        const long quietMs = 1200;
        var sw = Stopwatch.StartNew();
        int count = 0;
        long lastHit = 0;
        while (sw.ElapsedMilliseconds < deadlineMs) {
            foreach (var p in w.TakeSettled()) {
                if (PathEq(p, target)) {
                    count++;
                    lastHit = sw.ElapsedMilliseconds;
                } else {
                    Unexpected.Add(p);
                }
            }
            if (count > 0 && sw.ElapsedMilliseconds - lastHit > quietMs) {
                break;
            }
            Thread.Sleep(20);
        }
        return count;
    }

    // vim/Neovim classic save: rename the original away, write a fresh file at the path.
    static void VimSave(string target, int i) {
        var bak = target + "~";
        if (File.Exists(bak)) {
            File.Delete(bak);
        }
        File.Move(target, bak);
        File.WriteAllText(target, $"(ns t)\n;; save {i}\n");
        File.Delete(bak);
    }

    // VS Code-style safe write: write a temp file, then rename it over the target.
    static void RenameOver(string tmp, string target) {
        try {
            File.Replace(tmp, target, null);
        } catch (PlatformNotSupportedException) {
            File.Delete(target);
            File.Move(tmp, target);
        }
    }

    static bool PathEq(string a, string b) {
        return string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), StringComparison.Ordinal);
    }

}
