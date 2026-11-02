using System;
using System.IO;
using System.Reflection;
using clojure.lang;

// Probe 9: RT.UseMagicCompiler() in the MAGIC fork (plan §2.3, item 1). Mimics a MAGIC
// Editor's domain: every *.clj.dll of the staged directory is loaded before the runtime
// boots, then the runtime boots as Magic.Unity.Clojure.BootMagicRuntime does.
// Takes `<runtime-dir> absent|present`, one mono process per staging:
//
//   absent  [C1] Runtime/magic only: UseMagicCompiler() throws, naming magic/api.
//   present [C2] Runtime/magic + Editor/Compiler: it sets Compiler.Backend to
//                magic.api/backend, and eval compiles a form.
//
// Called by reflection so the probe builds, and fails by its own report, before the
// method exists.
class UseMagicCompilerTest {
    static int Main(string[] args) {
        if (args.Length != 2 || (args[1] != "absent" && args[1] != "present")) {
            Console.WriteLine("usage: UseMagicCompilerTest.exe <runtime-dir> absent|present");
            return 2;
        }
        foreach (var dll in Directory.EnumerateFiles(Path.GetFullPath(args[0]), "*.clj.dll"))
            Assembly.LoadFile(dll);
        RT.Initialize(true, false);
        RT.TryLoadInitType("clojure/core");

        var use = typeof(RT).GetMethod("UseMagicCompiler", BindingFlags.Public | BindingFlags.Static, null, Type.EmptyTypes, null);
        if (use == null) {
            Console.WriteLine("[C0] FAIL: the fork's RT has no public static UseMagicCompiler()");
            return Report(false);
        }
        Exception thrown = null;
        try {
            use.Invoke(null, null);
        } catch (TargetInvocationException ex) {
            thrown = ex.InnerException;
        }
        return Report(args[1] == "absent" ? C1(thrown) : C2(thrown));
    }

    static bool C1(Exception thrown) {
        Console.WriteLine($"[C1] without Editor/Compiler: threw {(thrown == null ? "nothing" : thrown.GetType().Name + ": " + thrown.Message)}");
        if (thrown == null || !thrown.Message.Contains("magic/api")) {
            Console.WriteLine("[C1] FAIL: expected an exception naming magic/api");
            return false;
        }
        Console.WriteLine("[C1] PASS: a missing compiler fails naming magic/api");
        return true;
    }

    static bool C2(Exception thrown) {
        if (thrown != null) {
            Console.WriteLine($"[C2] FAIL: UseMagicCompiler threw with the compiler staged: {thrown}");
            return false;
        }
        var field = typeof(clojure.lang.Compiler).GetField("Backend", BindingFlags.Public | BindingFlags.Static);
        if (field == null) {
            Console.WriteLine("[C2] FAIL: the fork's Compiler has no public static Backend");
            return false;
        }
        var backend = field.GetValue(null);
        var want = RT.var("magic.api", "backend").deref();
        Console.WriteLine($"[C2] Compiler.Backend = {backend ?? "<null>"} (expect {want})");
        var ok = backend != null && ReferenceEquals(backend, want);
        object sum = null;
        try {
            sum = RT.var("clojure.core", "eval").invoke(RT.var("clojure.core", "read-string").invoke("(+ 1 2)"));
        } catch (Exception ex) {
            Console.WriteLine($"[C2] eval threw: {ex}");
        }
        Console.WriteLine($"[C2] (eval '(+ 1 2)) = {sum ?? "<none>"}");
        if (!ok || !Equals(sum, 3L)) {
            Console.WriteLine("[C2] FAIL: Compiler.Backend is not magic.api/backend");
            return false;
        }
        Console.WriteLine("[C2] PASS: Compiler.Backend is magic.api/backend and eval compiles");
        return true;
    }

    static int Report(bool ok) {
        Console.WriteLine(ok ? "PROBE9-USE-MAGIC-COMPILER-VERIFIED" : "PROBE9-FAIL");
        return ok ? 0 : 1;
    }
}
