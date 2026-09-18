using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading;
using Magic.Unity;

// Probe 4 — asserts the Dispose/finalizer contract of DebouncedFileWatcher
// (commits 470ea42 "drop the no-op finalizer" and b3026df "AddRoot throws after
// Dispose"). Compiled with the real DebouncedFileWatcher.cs, so it FAILS if that
// behaviour regresses. Clojure/Unity-free: bare csc + mono, like Probe 3.
//
//   [C1] the type declares NO finalizer (the 470ea42 claim, asserted directly by
//        reflection rather than by flaky GC.Collect timing).
//   [C2] Dispose() deterministically releases the FileSystemWatcher: a save AFTER
//        Dispose produces zero TakeSettled results (the watcher is really gone, not just
//        relying on its own finalizer someday).
//   [C3] Dispose() is idempotent: a second call does not throw.
//   [C4] AddRoot() after Dispose throws ObjectDisposedException (b3026df), not a
//        silent no-op and not some other exception type.
//   [C5] AddRoot racing Dispose is safe: the only tolerated exception is
//        ObjectDisposedException (a loser of the race), nothing else escapes, and
//        no watcher survives teardown (_disposed is checked under _rootsLock).
class DisposeTest {

    static readonly Func<string, bool> Gate = p => {
        var e = Path.GetExtension(p);
        return e == ".clj" || e == ".cljc" || e == ".cljr";
    };

    static readonly List<string> TempDirs = new List<string>();

    static int Main() {
        int failures = 0;

        // --- C1: no finalizer (reflection) ---
        bool declares = DeclaresFinalizer(typeof(DebouncedFileWatcher));
        Report("C1", !declares,
            $"DebouncedFileWatcher declares finalizer = {declares} (expect False)",
            "no finalizer",
            "still declares Finalize() — the 470ea42 removal regressed");
        if (declares) failures++;

        // --- C2: Dispose releases the watcher ---
        var dir = NewDir();
        var target = Path.Combine(dir, "probe.cljc");
        File.WriteAllText(target, "(ns t)\n");
        var w = new DebouncedFileWatcher(Gate, Console.Error.WriteLine, 150);
        w.AddRoot(dir);
        Thread.Sleep(500); // let the watcher arm before the first save

        VimSave(target, 1);
        int armed = DrainOneSave(w, target);      // expect exactly 1 while armed
        w.Dispose();
        VimSave(target, 2);                        // save with NO handler attached
        int afterDispose = CountWithin(w, target, 2000); // expect 0
        bool c2 = armed == 1 && afterDispose == 0;
        Report("C2", c2,
            $"armed-save results = {armed} (expect 1); post-Dispose results = {afterDispose} (expect 0)",
            "Dispose released the FileSystemWatcher (no events after)",
            "events still fired after Dispose — the watcher was not torn down");
        if (!c2) failures++;

        // --- C3: Dispose is idempotent ---
        bool c3 = true;
        try {
            w.Dispose();
        } catch (Exception ex) {
            c3 = false;
            Console.WriteLine($"[C3] second Dispose threw: {ex.GetType().Name}: {ex.Message}");
        }
        Report("C3", c3, "second Dispose() call", "idempotent (no throw)", "threw on second Dispose");
        if (!c3) failures++;

        // --- C4: AddRoot after Dispose throws ObjectDisposedException ---
        var w4 = new DebouncedFileWatcher(Gate, Console.Error.WriteLine, 150);
        w4.Dispose();
        string c4msg;
        bool c4;
        try {
            w4.AddRoot(NewDir());
            c4 = false;
            c4msg = "AddRoot returned normally (expected ObjectDisposedException)";
        } catch (ObjectDisposedException) {
            c4 = true;
            c4msg = "AddRoot threw ObjectDisposedException";
        } catch (Exception ex) {
            c4 = false;
            c4msg = $"AddRoot threw {ex.GetType().Name} (expected ObjectDisposedException)";
        }
        Report("C4", c4, c4msg, "fail-fast on post-Dispose AddRoot", "wrong or missing exception");
        if (!c4) failures++;

        // --- C5: AddRoot racing Dispose is safe ---
        var w5 = new DebouncedFileWatcher(Gate, Console.Error.WriteLine, 150);
        var raceDirs = new List<string>();
        for (int i = 0; i < 8; i++) raceDirs.Add(NewDir());
        var unexpected = new ConcurrentQueue<Exception>();
        var threads = new List<Thread>();
        for (int t = 0; t < 4; t++) {
            var th = new Thread(() => {
                for (int j = 0; j < 40; j++) {
                    try {
                        w5.AddRoot(raceDirs[j % raceDirs.Count]);
                    } catch (ObjectDisposedException) {
                        // Expected: this call lost the race and ran after Dispose.
                    } catch (Exception ex) {
                        unexpected.Enqueue(ex);
                    }
                }
            });
            threads.Add(th);
        }
        foreach (var th in threads) th.Start();
        Thread.Sleep(3); // let some AddRoots land before tearing down mid-flight
        w5.Dispose();
        foreach (var th in threads) th.Join();
        int survivors = WatcherCount(w5);
        bool c5 = unexpected.IsEmpty && survivors == 0;
        Report("C5", c5,
            $"unexpected exceptions = {unexpected.Count} (expect 0); watchers after Dispose = {survivors} (expect 0)",
            "concurrent AddRoot/Dispose left no armed watcher and threw only ObjectDisposedException",
            "torn state under contention");
        if (!c5) {
            foreach (var ex in unexpected) {
                Console.WriteLine($"[C5]   unexpected: {ex.GetType().Name}: {ex.Message}");
            }
            failures++;
        }

        Cleanup();
        if (failures == 0) {
            Console.WriteLine("PROBE4-DISPOSE-VERIFIED");
            return 0;
        }
        Console.WriteLine("PROBE4-REGRESSION");
        return 1;
    }

    static void Report(string id, bool ok, string detail, string passMsg, string failMsg) {
        Console.WriteLine($"[{id}] {detail}");
        Console.WriteLine(ok ? $"[{id}] PASS: {passMsg}" : $"[{id}] FAIL: {failMsg}");
    }

    static bool DeclaresFinalizer(Type t) {
        // A C# `~T()` compiles to an override of object.Finalize(); if T does not
        // override it, GetMethod resolves object's and DeclaringType != t.
        var f = t.GetMethod("Finalize", BindingFlags.Instance | BindingFlags.NonPublic);
        return f != null && f.DeclaringType == t;
    }

    // Private name, so a rename in DebouncedFileWatcher breaks this probe -- say so out
    // loud rather than NullReferencing on the missing FieldInfo.
    const string RootsField = "_watchersByRoot";

    static int WatcherCount(DebouncedFileWatcher w) {
        var f = typeof(DebouncedFileWatcher)
            .GetField(RootsField, BindingFlags.Instance | BindingFlags.NonPublic);
        if (f == null) {
            throw new InvalidOperationException(
                $"DebouncedFileWatcher has no field '{RootsField}' -- it was renamed; update this probe.");
        }
        var d = (System.Collections.IDictionary)f.GetValue(w);
        return d.Count;
    }

    // Drain until we have >=1 result for target then a quiet stretch past the settle
    // window (a straggling duplicate would still be caught). Hard-capped so a no-event
    // backend can't hang. Mirrors ReloaderQueueTest.DrainOneSave.
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
                }
            }
            if (count > 0 && sw.ElapsedMilliseconds - lastHit > quietMs) {
                break;
            }
            Thread.Sleep(20);
        }
        return count;
    }

    // Count results for target over a fixed window (for the expect-zero case: we
    // cannot wait for a hit that must never come, so poll a bounded stretch).
    static int CountWithin(DebouncedFileWatcher w, string target, long ms) {
        var sw = Stopwatch.StartNew();
        int count = 0;
        while (sw.ElapsedMilliseconds < ms) {
            foreach (var p in w.TakeSettled()) {
                if (PathEq(p, target)) count++;
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

    static string NewDir() {
        var d = Path.Combine(Path.GetTempPath(), "disposetest-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(d);
        TempDirs.Add(d);
        return d;
    }

    static void Cleanup() {
        foreach (var d in TempDirs) {
            try { Directory.Delete(d, true); } catch { /* best effort */ }
        }
    }

    static bool PathEq(string a, string b) {
        return string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), StringComparison.Ordinal);
    }

}
