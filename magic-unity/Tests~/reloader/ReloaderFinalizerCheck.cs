using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Threading;
using Magic.Unity;

// Probe 5 — ClojureReloader-level contract. This type references
// clojure.lang.Compiler, so it must be compiled against Clojure.dll; run.sh skips
// this probe if the stock ClojureCLR runtime has not been fetched.
//
//   [F] declares NO finalizer (asserts e3fc488, which dropped ~ClojureReloader():
//       the type owns only a managed IDisposable, so a finalizer would reach into
//       managed state from the finalizer thread for no benefit).
//   [L3] constructing with no source root logs and arms nothing. The roots are the
//       host's to supply (in integrated, CljSourceRoots.Installed), so the constructor
//       can be reached with nothing to watch. The chosen posture is log-and-return (a
//       missing root is an environment problem, and the host builds the reloader in a
//       boot step that does not survive an exception), so the assertion is on the two
//       observables that distinguish it from a silent no-op: the disabled line reaches
//       the logger, and _watcher stays null so Poll() can never fire.
//   [L] lifecycle null-safety on that same inert instance: Poll() with nothing armed,
//       double Dispose(), Poll() after Dispose() — all must no-op, never throw.
//
// Re-arming is not a contract: the reloader is constructed armed or inert and disposed
// once, so there is no Init-after-Dispose case to assert on (see PROVENANCE.md).
//
//   [L4] PARKED 2026-09-07, with the OpenFds/Collect helpers it needed: the constructor
//       try/catch it asserted on was removed in review (#173), so the check now fails by
//       design. Commented out rather than deleted -- uncomment both blocks to restore it
//       along with the guard. What it measured, and why the guard went anyway, is in
//       README.md under "Unresolved".
//
// None of these paths touch the Clojure runtime -- the constructor only arms
// FileSystemWatchers. Clojure.dll is needed only so the compiler and JIT can resolve
// the Compiler.load callsite inside Poll()'s body.
class ReloaderFinalizerCheck {

    // Same convention as Probe 7's reflection helpers: a renamed field must report itself
    // by name rather than NullReference somewhere downstream.
    static object WatcherOf(ClojureReloader r) {
        var f = typeof(ClojureReloader).GetField("_watcher",
            BindingFlags.Instance | BindingFlags.NonPublic);
        if (f == null) {
            throw new InvalidOperationException(
                "ClojureReloader._watcher is gone -- [L3] cannot assert on it");
        }
        return f.GetValue(r);
    }

    // PARKED with [L4]; uncomment together with the block in Main().
    // An armed FileSystemWatcher holds file descriptors on macOS (two kqueue, plus one per
    // directory in the root's own path chain); Dispose gives them back. That makes /dev/fd
    // the one observable for a watcher the probe cannot reach --
    // a constructor that throws hands back no reference to inspect. Process-wide, so [L4]
    // compares against a baseline taken moments earlier rather than an absolute count.
    // const int FdSlack = 2;
    // const int FdSettleMs = 300;
    //
    // static int OpenFds() {
    //     return Directory.GetFileSystemEntries("/dev/fd").Length;
    // }
    //
    // static void Collect() {
    //     Thread.Sleep(FdSettleMs);
    //     GC.Collect();
    //     GC.WaitForPendingFinalizers();
    //     GC.Collect();
    // }

    static int Main() {
        var failures = 0;

        var t = typeof(ClojureReloader);
        var f = t.GetMethod("Finalize", BindingFlags.Instance | BindingFlags.NonPublic);
        // A `~T()` compiles to an override of object.Finalize(); absent an override,
        // GetMethod resolves object's method and DeclaringType != t.
        var declares = f != null && f.DeclaringType == t;
        Console.WriteLine($"[F] ClojureReloader declares finalizer = {declares} (expect False)");
        if (declares) {
            Console.WriteLine("[F] FAIL: finalizer is back");
            failures++;
        } else {
            Console.WriteLine("[F] PASS: no finalizer");
        }

        // The roots are a constructor argument, so "nothing to watch" is simply an empty
        // list -- no environment to clear first.
        {
            var logged = new List<string>();
            ClojureReloader inert = null;
            bool l3;
            string l3msg;
            try {
                inert = new ClojureReloader(Array.Empty<string>(), logged.Add);
                // The logger is the only thing the constructor tells the host; _watcher is
                // the state that decides whether Poll() can ever fire. Assert both -- the log
                // alone would pass for a reloader that logged the line and then armed a
                // rootless watcher.
                var armed = WatcherOf(inert) != null;
                var told = logged.Contains("No clj source root. Reload disabled.");
                l3 = !armed && told;
                l3msg = $"armed a watcher = {armed} (expect False); " +
                    $"logged the disabled line = {told} (expect True)";
            } catch (Exception ex) {
                l3 = false;
                l3msg = $"the constructor threw {ex.GetType().Name}: {ex.Message} " +
                    "(expected log-and-return)";
            }
            if (l3) {
                Console.WriteLine($"[L3] PASS: constructing with no source root logged and " +
                    $"armed nothing -- {l3msg}");
            } else {
                Console.WriteLine($"[L3] FAIL: {l3msg}");
                failures++;
            }

            if (inert == null) {
                Console.WriteLine("[L] SKIP: the constructor threw, so there is no instance to exercise");
                failures++;
            } else {
                try {
                    inert.Poll();    // nothing armed: must no-op (watcher is null)
                    inert.Dispose();
                    inert.Dispose(); // must be idempotent
                    inert.Poll();    // after Dispose: must no-op
                    Console.WriteLine("[L] PASS: Poll/Dispose no-op with nothing armed and after Dispose");
                } catch (Exception ex) {
                    Console.WriteLine($"[L] FAIL: lifecycle call threw {ex.GetType().Name}: {ex.Message}");
                    failures++;
                }
            }
        }

        // PARKED: [L4] asserts a constructor guard that no longer exists.
        // [L4] -- needs a real root to arm, so it runs after [L3]/[L], which need none.
        // var l4root = Path.Combine(Path.GetTempPath(), "probe5_l4_" + Guid.NewGuid().ToString("N"));
        // var l4roots = new[] { l4root };
        // try {
        //     Directory.CreateDirectory(Path.Combine(l4root, "a", "b"));
        //     File.WriteAllText(Path.Combine(l4root, "a", "b", "x.clj"), "(ns a.b.x)\n");
        //     {
        //         var baseline = OpenFds();
        //
        // Calibration: a reloader that arms and is disposed normally. Without a
        // measurable delta here the fd observable proves nothing, and [L4] would
        // pass for a leak it simply cannot see.
        //         int armed;
        //         var good = new ClojureReloader(l4roots, _ => { });
        //         try {
        //             Thread.Sleep(FdSettleMs); // the fds appear a moment after arming
        //             armed = OpenFds();
        //         } finally {
        //             good.Dispose();
        //         }
        //         Collect();
        //         var settled = OpenFds();
        //
        //         var threw = false;
        //         try {
        // Throws on the success line, i.e. after AddRoot armed a real watch.
        //             new ClojureReloader(l4roots, msg => {
        //                 if (msg.StartsWith("Watching clj source root.", StringComparison.Ordinal)) {
        //                     throw new InvalidOperationException("logger contract violation");
        //                 }
        //             });
        //         } catch (InvalidOperationException) {
        //             threw = true;
        //         } catch (Exception ex) {
        //             Console.WriteLine($"[L4] note: constructor threw {ex.GetType().Name} rather than the logger's own");
        //         }
        //         Collect();
        //         var after = OpenFds();
        //
        //         var calibrated = armed > baseline;
        //         var released = after <= baseline + FdSlack;
        //         Console.WriteLine($"[L4] fds: baseline = {baseline}, armed = {armed} (must exceed " +
        //             $"baseline, else the check is blind), after a normal Dispose = {settled}, " +
        //             $"after the throwing construction = {after} (expect <= {baseline + FdSlack}); " +
        //             $"constructor propagated = {threw} (expect True)");
        //         if (!calibrated) {
        //             Console.WriteLine("[L4] FAIL: arming a watcher opened no file descriptor, so the " +
        //                 "leak this checks for would be invisible -- not a verdict on the constructor");
        //             failures++;
        //         } else if (!threw) {
        //             Console.WriteLine("[L4] FAIL: the constructor swallowed the logger's exception " +
        //                 "instead of propagating it");
        //             failures++;
        //         } else if (!released) {
        //             Console.WriteLine("[L4] FAIL: the throwing construction stranded an armed watch " +
        //                 $"({after - baseline} fd(s) still open) -- the caller got no reference, so " +
        //                 "nothing else can ever dispose it");
        //             failures++;
        //         } else {
        //             Console.WriteLine("[L4] PASS: the constructor disposed what it had armed, then rethrew");
        //         }
        //     }
        // } catch (Exception ex) {
        //     Console.WriteLine($"[L4] FAIL: staging threw {ex.GetType().Name}: {ex.Message}");
        //     failures++;
        // } finally {
        //     try { Directory.Delete(l4root, true); } catch { /* best effort */ }
        // }

        if (failures == 0) {
            Console.WriteLine("PROBE5-PASS");
            return 0;
        }
        Console.WriteLine("PROBE5-FAIL");
        return 1;
    }

}
