using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using UnityEditor;
using UnityEngine;

// Step 0 of nostrand-in-unity-process.md: the two assumptions the whole plan
// rests on, measured inside Unity's Mono rather than standalone Mono.
//
//   emit      AssemblyBuilder.Save works at all, both into an explicit
//             directory and into the cwd, which is the only thing
//             magic.emission/fresh-module can target (api.clj:235,238).
//   dirs      the compiler .clj.dll set still resolves when it lives in a
//             directory other than Clojure.dll's -- the packaging split.
//   compiler  the real path: require magic.api, eval a form, and
//             compile-file to disk, which is AssemblyBuilder.Save for real.
//
// Run headless with -executeMethod EmissionProbe.Run, or from the menu; grep
// the log for the [EmissionProbe.*] marker lines. Marker values never contain
// spaces, so the driver can parse them as key=value pairs.
public static class EmissionProbe
{
    const string Probe = "[EmissionProbe";

    [MenuItem("MAGIC/Probe/Step 0 (Reflection.Emit)")]
    public static void Run()
    {
        var scratch = Path.Combine(Path.GetTempPath(), "magic-step0-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(scratch);
        try
        {
            EmitProbe(scratch);
            DirsProbe();
            CompilerProbe(scratch);
        }
        finally
        {
            // Leave the scratch tree: a failed run's half-written .dll is the
            // evidence. Report it instead of deleting it.
            Debug.Log($"{Probe}.scratch] dir={Sanitize(scratch)}");
        }
    }

    // Spaces would break the driver's key=value split; a path holding one is
    // still legible escaped.
    static string Sanitize(string s)
    {
        return string.IsNullOrEmpty(s) ? "none" : s.Replace(" ", "%20");
    }

    static string Fault(Exception e)
    {
        var inner = e is TargetInvocationException && e.InnerException != null ? e.InnerException : e;
        Debug.Log($"{Probe}.detail] {inner}");
        return inner.GetType().Name;
    }

    // ---------------------------------------------------------------- emit

    // A saveable dynamic assembly holding one method that returns 42: run it
    // in memory, Save it, then load the saved file back and run it again. A
    // Save that writes an unloadable file would otherwise read as a pass.
    static string EmitOnce(string tag, string dir, out string savedPath)
    {
        savedPath = null;
        var name = new AssemblyName("step0_emit_" + tag);
        var file = name.Name + ".dll";
        var builder = dir == null
            ? AppDomain.CurrentDomain.DefineDynamicAssembly(name, AssemblyBuilderAccess.RunAndSave)
            : AppDomain.CurrentDomain.DefineDynamicAssembly(name, AssemblyBuilderAccess.RunAndSave, dir);
        var module = builder.DefineDynamicModule(name.Name, file);
        var type = module.DefineType(
            "Step0Probe",
            TypeAttributes.Public | TypeAttributes.Abstract | TypeAttributes.Sealed
        );
        var method = type.DefineMethod(
            "FortyTwo",
            MethodAttributes.Public | MethodAttributes.Static,
            typeof(int),
            Type.EmptyTypes
        );
        var il = method.GetILGenerator();
        il.Emit(OpCodes.Ldc_I4, 42);
        il.Emit(OpCodes.Ret);
        var created = type.CreateType();
        var ran = (int)created.GetMethod("FortyTwo").Invoke(null, null);
        if (ran != 42)
            return "ran-" + ran;
        builder.Save(file);
        savedPath = Path.Combine(dir ?? Directory.GetCurrentDirectory(), file);
        return File.Exists(savedPath) ? "ok" : "no-file";
    }

    static string EmitTo(string tag, string dir, out string savedPath)
    {
        savedPath = null;
        try
        {
            return EmitOnce(tag, dir, out savedPath);
        }
        catch (Exception e)
        {
            return Fault(e);
        }
    }

    static string Reload(string path)
    {
        if (path == null || !File.Exists(path))
            return "skipped";
        try
        {
            var loaded = Assembly.LoadFrom(path);
            var value = loaded.GetType("Step0Probe").GetMethod("FortyTwo").Invoke(null, null);
            return Equals(value, 42) ? "ok" : "got-" + value;
        }
        catch (Exception e)
        {
            return Fault(e);
        }
    }

    static void EmitProbe(string scratch)
    {
        var cwd = Directory.GetCurrentDirectory();
        string dirPath, cwdPath;
        var dirResult = EmitTo("dir", scratch, out dirPath);
        var cwdResult = EmitTo("cwd", null, out cwdPath);
        Debug.Log(
            $"{Probe}.emit] save-dir={dirResult} reload-dir={Reload(dirPath)} "
                + $"save-cwd={cwdResult} cwd={Sanitize(cwd)} "
                + $"cwd-writable={CanWrite(cwd).ToString().ToLowerInvariant()}"
        );
        if (cwdPath != null && File.Exists(cwdPath))
            File.Delete(cwdPath);
    }

    static bool CanWrite(string dir)
    {
        try
        {
            var probe = Path.Combine(dir, "step0-write-probe.tmp");
            File.WriteAllText(probe, "x");
            File.Delete(probe);
            return true;
        }
        catch
        {
            return false;
        }
    }

    // ---------------------------------------------------------------- dirs

    // Where the domain found each half of the split. The compiler set is
    // reached through RT.load, never a C# type reference, so Assembly.Load by
    // simple name is exactly how it gets there.
    static string AssemblyDir(string simpleName)
    {
        try
        {
            var loaded = AppDomain
                .CurrentDomain.GetAssemblies()
                .FirstOrDefault(a => a.GetName().Name == simpleName) ?? Assembly.Load(simpleName);
            return Sanitize(Path.GetDirectoryName(loaded.Location));
        }
        catch (Exception e)
        {
            return Fault(e);
        }
    }

    // How many of the compiler set Unity had already put in the domain before
    // anything asked for them. §7 of the plan cannot settle this without Unity:
    // an editor-eligible plugin that is not auto-referenced may or may not be
    // loaded eagerly, and the answer is what compiler-in-domain gets pinned to.
    static int CompilerInDomain()
    {
        return AppDomain
            .CurrentDomain.GetAssemblies()
            .Count(a =>
                a.GetName().Name.StartsWith("magic.", StringComparison.Ordinal)
                || a.GetName().Name.StartsWith("mage.", StringComparison.Ordinal)
                || a.GetName().Name.StartsWith("clojure.tools.analyzer", StringComparison.Ordinal)
            );
    }

    static void DirsProbe()
    {
        var eager = CompilerInDomain();
        var clojure = AssemblyDir("Clojure");
        var runtime = AssemblyDir("Magic.Runtime");
        var compiler = AssemblyDir("magic.api.clj");
        Debug.Log(
            $"{Probe}.dirs] clojure-dir={clojure} runtime-dir={runtime} compiler-dir={compiler} "
                + $"split={(compiler != clojure).ToString().ToLowerInvariant()} "
                + $"compiler-in-domain-before={eager} compiler-in-domain-after={CompilerInDomain()}"
        );
    }

    // ------------------------------------------------------------ compiler

#if MAGIC_RUNTIME_IN_EDITOR
    // The real emission path, which is what actually has to work: boot the
    // runtime, require the compiler off the shipped DLLs, eval a form, then
    // compile a source file to disk. compile-file saves through
    // Magic.Emission.EmitAssembly with a bare file name, so the cwd is the
    // save directory; it is moved to the cwd deliberately here rather than
    // depending on wherever Unity left it.
    static void CompilerProbe(string scratch)
    {
        string boot = "ok",
            require = "skipped",
            evaluated = "skipped",
            compiled = "skipped",
            dll = "none";
        var cwd = Directory.GetCurrentDirectory();
        try
        {
            Magic.Unity.Clojure.Boot();
            try
            {
                clojure.lang.RT.var("clojure.core", "require")
                    .invoke(clojure.lang.Symbol.intern("magic.api"));
                require = "ok";
                evaluated = Convert.ToString(
                    clojure.lang.RT.var("magic.api", "eval")
                        .invoke(clojure.lang.RT.readString("(+ 1 2)"))
                );

                var source = Path.Combine(scratch, "step0.clj");
                File.WriteAllText(source, "(ns step0)\n(defn answer [] (* 6 7))\n");
                Directory.SetCurrentDirectory(scratch);
                try
                {
                    clojure.lang.RT.var("magic.api", "compile-file")
                        .invoke(
                            source,
                            "step0",
                            clojure.lang.RT.map(
                                clojure.lang.Keyword.intern("write-files"),
                                true,
                                clojure.lang.Keyword.intern("suppress-print-forms"),
                                true
                            )
                        );
                }
                finally
                {
                    Directory.SetCurrentDirectory(cwd);
                }
                var emitted = Path.Combine(scratch, "step0.clj.dll");
                compiled = File.Exists(emitted) ? "ok" : "no-file";
                dll = File.Exists(emitted) ? new FileInfo(emitted).Length.ToString() : "none";
            }
            catch (Exception e)
            {
                var fault = Fault(e);
                if (require == "skipped")
                    require = fault;
                else if (evaluated == "skipped")
                    evaluated = fault;
                else
                    compiled = fault;
            }
        }
        catch (Exception e)
        {
            boot = Fault(e);
        }
        Debug.Log(
            $"{Probe}.compiler] boot={boot} require={require} eval={evaluated} "
                + $"compile-file={compiled} dll-bytes={dll}"
        );
    }
#else
    // The ClojureCLR Editor state has no MAGIC compiler to exercise; the
    // marker still prints so a run in the wrong state is obvious rather than
    // silently short.
    static void CompilerProbe(string scratch)
    {
        Debug.Log($"{Probe}.compiler] boot=no-symbol require=skipped eval=skipped compile-file=skipped dll-bytes=none");
    }
#endif
}
