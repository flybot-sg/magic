using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;
using clojure.lang;

// Probe 8: the reloader's boot call (plan §2.3, "Trigger"). Before each Compiler.load,
// ClojureReloader finds Magic.Unity.Clojure among the loaded assemblies and invokes its
// Boot() by reflection. Built three ways, each in its own mono process, since the
// reloader caches what it found:
//
//   -define:BOOT_STUB   [B1] each save calls Boot once, before Compiler.load: the stub
//                       binds boot.probe/count, which the saved file reads.
//   -define:BOOT_THROWS [B2] a throwing Boot is logged by its inner exception, not as
//                       TargetInvocationException; the file is not loaded, and the next
//                       save calls Boot again, so a fixed cause is picked up.
//   neither             [B3] with no Magic.Unity.Clojure the saves still load, and the
//                       missing type is logged once, not per save.
//
// Runs on the ClojureCLR the package ships, booted by RT.Init as in Probe 7: the stub
// stands in for Magic.Unity.Clojure, so no MAGIC runtime is needed.
#if BOOT_STUB || BOOT_THROWS
namespace Magic.Unity {
    public static class Clojure {
        public static int Count;

        public static void Boot() {
            Count++;
#if BOOT_THROWS
            throw new InvalidOperationException("stub boot failure: no roots file");
#else
            RT.var("boot.probe", "count").bindRoot((long)Count);
#endif
        }
    }
}
#endif

class ReloaderBootTest {
    static readonly List<string> Log = new List<string>();
    static readonly object LogLock = new object();
    static int failures;

    static int Main() {
        var root = Path.Combine(Path.GetTempPath(), "reloaderboot-" + Guid.NewGuid().ToString("N"));
        var src = Path.Combine(root, "src");
        Directory.CreateDirectory(src);
        try {
            RT.Init();
            using (var r = new Magic.Unity.ClojureReloader(new[] { src }, Sink)) {
#if BOOT_STUB
                B1_EachSaveBootsBeforeLoad(r, src);
#elif BOOT_THROWS
                B2_ThrowingBootIsLoggedByItsCause(r, src);
#else
                B3_MissingTypeIsLoggedOnce(r, src);
#endif
            }
        } finally {
            try { Directory.Delete(root, true); } catch { /* best effort */ }
        }
        if (failures == 0) {
            Console.WriteLine("PROBE8-BOOT-VERIFIED");
            return 0;
        }
        Console.WriteLine("PROBE8-FAIL");
        return 1;
    }

#if BOOT_STUB
    static void B1_EachSaveBootsBeforeLoad(Magic.Unity.ClojureReloader r, string src) {
        var f = Path.Combine(src, "probe_boot.clj");
        for (long save = 1; save <= 2; save++) {
            File.WriteAllText(f, $"(ns probe.boot)\n(def seen boot.probe/count)\n(def save {save})\n");
            var s = save;
            var loaded = DrainUntil(r, () => Equals(VarValue("probe.boot", "save"), s));
            var seen = VarValue("probe.boot", "seen");
            Console.WriteLine($"[B1] save {save}: loaded = {loaded}; probe.boot/seen = {Show(seen)}; Boot calls = {Magic.Unity.Clojure.Count} (expect {save})");
            if (!loaded) {
                Fail("B1", $"save {save} never loaded; log: {string.Join(" | ", Log)}");
                return;
            }
            if (!Equals(seen, save) || Magic.Unity.Clojure.Count != save) {
                Fail("B1", $"save {save}: expected Boot called {save} times, before the load");
                return;
            }
        }
        Console.WriteLine("[B1] PASS: each save calls Boot once, before Compiler.load");
    }
#endif

#if BOOT_THROWS
    static void B2_ThrowingBootIsLoggedByItsCause(Magic.Unity.ClojureReloader r, string src) {
        var f = Path.Combine(src, "probe_boot.clj");
        File.WriteAllText(f, "(ns probe.boot)\n(def loaded true)\n");
        var logged = DrainUntil(r, () => LoggedCount("stub boot failure: no roots file") == 1);
        Console.WriteLine($"[B2] first save: cause logged = {logged}; Boot calls = {Magic.Unity.Clojure.Count}; log: {string.Join(" | ", Log)}");
        if (!logged) {
            Fail("B2", "the throwing Boot's message was not logged");
        }
        if (LoggedCount("TargetInvocationException") != 0) {
            Fail("B2", "the log names TargetInvocationException rather than the inner exception");
        }
        if (VarValue("probe.boot", "loaded") != null) {
            Fail("B2", "the file was loaded although Boot threw");
        }

        File.WriteAllText(f, "(ns probe.boot)\n(def loaded :again)\n");
        var retried = DrainUntil(r, () => Magic.Unity.Clojure.Count == 2);
        Console.WriteLine($"[B2] second save: Boot calls = {Magic.Unity.Clojure.Count} (expect 2)");
        if (!retried) {
            Fail("B2", "the second save did not call Boot again");
        }
        if (failures == 0) {
            Console.WriteLine("[B2] PASS: a throwing Boot is logged by its cause, skips the load, and is retried");
        }
    }
#endif

#if !BOOT_STUB && !BOOT_THROWS
    static void B3_MissingTypeIsLoggedOnce(Magic.Unity.ClojureReloader r, string src) {
        var f = Path.Combine(src, "probe_boot.clj");
        for (long save = 1; save <= 2; save++) {
            File.WriteAllText(f, $"(ns probe.boot)\n(def save {save})\n");
            var s = save;
            if (!DrainUntil(r, () => Equals(VarValue("probe.boot", "save"), s))) {
                Fail("B3", $"save {save} never loaded; log: {string.Join(" | ", Log)}");
                return;
            }
        }
        var n = LoggedCount("Magic.Unity.Clojure");
        Console.WriteLine($"[B3] two saves loaded; lines naming Magic.Unity.Clojure = {n} (expect 1); log: {string.Join(" | ", Log)}");
        if (n != 1) {
            Fail("B3", $"the missing type was logged {n} times");
        } else {
            Console.WriteLine("[B3] PASS: without Magic.Unity.Clojure the saves load and the type is reported once");
        }
    }
#endif

    static bool DrainUntil(Magic.Unity.ClojureReloader r, Func<bool> done, long deadlineMs = 8000) {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < deadlineMs) {
            r.Poll();
            if (done()) {
                return true;
            }
            Thread.Sleep(20);
        }
        return done();
    }

    // Without interning: RT.var would create the var being asked about.
    static object VarValue(string ns, string name) {
        var n = Namespace.find(Symbol.intern(ns));
        var v = n?.FindInternedVar(Symbol.intern(name));
        return v != null && v.isBound ? v.deref() : null;
    }

    static string Show(object o) {
        return o == null ? "<unbound>" : o.ToString();
    }

    static void Sink(string message) {
        lock (LogLock) {
            Log.Add(message);
        }
    }

    static int LoggedCount(string fragment) {
        var n = 0;
        lock (LogLock) {
            foreach (var m in Log) {
                if (m.Contains(fragment)) {
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
