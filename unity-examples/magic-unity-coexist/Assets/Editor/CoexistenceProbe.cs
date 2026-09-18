using System;
using System.Linq;
using UnityEditor.Compilation;
using UnityEngine;
using Assembly = System.Reflection.Assembly;

// Headless probe reporting which Clojure runtime the Editor ended up with;
// the expected state per symbol is tabulated in this project's README.md.
// The Assembly.Load of clojure.core.clj is a canary: if it resolves, the fork
// DLL is in the domain and can win ClojureCLR's <ns>__Init scan.
//
// Run with -executeMethod CoexistenceProbe.Run; grep the log for the single
// [CoexistenceProbe] marker line.
public static class CoexistenceProbe
{
    // The symbol as this compilation actually saw it, not PlayerSettings.
#if MAGIC_RUNTIME_IN_EDITOR
    const string SymbolState = "set";
#else
    const string SymbolState = "unset";
#endif

    // The same extensions as in Magic.Unity's PlayerCljAssemblies
    static readonly string[] Extensions = { ".clj", ".cljc", ".cljr" };

    // The unconstrained plugin, expected in both Editor states.
    const string CsharpName = "smoke_csharp";

    // The compiler set ships editor-only under Editor/Compiler; the stdlib
    // ships under Runtime/magic. Both are .clj.dll, so the probe separates
    // them by the directory they were loaded or referenced from.
    const string CompilerDir = "/Compiler/";

    static bool IsCljAssembly(string name, string suffix)
    {
        return Extensions.Any(e => name.EndsWith(e + suffix, StringComparison.OrdinalIgnoreCase));
    }

    public static void Run()
    {
        var csharpInDomain = AppDomain
            .CurrentDomain.GetAssemblies()
            .Any(a => a.GetName().Name == CsharpName);

        var loadedClj = AppDomain
            .CurrentDomain.GetAssemblies()
            .Where(a => IsCljAssembly(a.GetName().Name, ""))
            .ToArray();

        var preloaded = loadedClj.Where(a => !FromCompilerDir(a)).ToArray();

        // Unity loads every editor-eligible plugin in the set eagerly, whatever
        // isExplicitlyReferenced says, so this is the whole compiler set in the
        // MAGIC state and nothing in the ClojureCLR state, where the define
        // constraint and the platform table both exclude it.
        var compilerInDomain = loadedClj.Count(FromCompilerDir);

        bool coreLoadable;
        string loadDetail;
        try
        {
            var asm = Assembly.Load("clojure.core.clj");
            coreLoadable = asm != null;
            // Not FullName: the marker line is parsed as space-separated
            // key=value pairs and FullName has spaces in it.
            loadDetail = asm == null ? "null" : asm.GetName().Name + "/" + asm.GetName().Version;
        }
        catch (Exception e)
        {
            coreLoadable = false;
            loadDetail = e.GetType().Name;
        }

        var clojureVersions = AppDomain
            .CurrentDomain.GetAssemblies()
            .Where(a => a.GetName().Name == "Clojure")
            .Select(a => a.GetName().Version.ToString())
            .OrderBy(v => v)
            .ToArray();

        Debug.Log(
            $"[CoexistenceProbe] symbol={SymbolState} "
                + $"preloaded-clj={preloaded.Length} "
                + $"compiler-in-domain={compilerInDomain} "
                + $"compiler-player-refs={CompilerReferences(AssembliesType.PlayerWithoutTestAssemblies)} "
                + $"compiler-boots={CompilerBoots()} "
                + $"core-clj-loadable={coreLoadable.ToString().ToLowerInvariant()} "
                + $"core-clj-load={loadDetail} "
                + $"clojure-versions=[{string.Join(",", clojureVersions)}] "
                + $"editor-clj-refs={CljReferences(AssembliesType.Editor)} "
                + $"player-clj-refs={CljReferences(AssembliesType.PlayerWithoutTestAssemblies)} "
                + $"csharp-in-domain={csharpInDomain.ToString().ToLowerInvariant()} "
                + $"csharp-editor-refs={CsharpReferences(AssembliesType.Editor)}"
        );
    }

    static int ReferenceCount(AssembliesType type, Func<string, bool> matches)
    {
        return CompilationPipeline
            .GetAssemblies(type)
            .SelectMany(a => a.allReferences)
            .Where(matches)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Count();
    }

    static int CsharpReferences(AssembliesType type)
    {
        return ReferenceCount(
            type,
            r => r.EndsWith("/" + CsharpName + ".dll", StringComparison.OrdinalIgnoreCase)
        );
    }

    static int CljReferences(AssembliesType type)
    {
        return ReferenceCount(type, r => IsCljAssembly(r, ".dll") && !r.Contains(CompilerDir));
    }

    // The direct assertion for the bug that moving the compiler out of
    // Runtime/magic fixed: the compiler must never reach a player build.
    static int CompilerReferences(AssembliesType type)
    {
        return ReferenceCount(type, r => IsCljAssembly(r, ".dll") && r.Contains(CompilerDir));
    }

    static bool FromCompilerDir(Assembly a)
    {
        try
        {
            return !a.IsDynamic
                && !string.IsNullOrEmpty(a.Location)
                && a.Location.Replace('\\', '/').Contains(CompilerDir);
        }
        catch (NotSupportedException)
        {
            return false;
        }
    }

    // Whether the in-process host can actually boot the compiler it ships with.
    // The host assembly is constrained to the MAGIC state, so in the other one
    // there is nothing to call and the field reports "absent".
    static string CompilerBoots()
    {
#if MAGIC_RUNTIME_IN_EDITOR
        try
        {
            var nostrand = new Magic.Unity.NostrandEditor();
            nostrand.Prewarm();
            return nostrand.Host.IsPrewarmed ? "true" : "false";
        }
        catch (Exception e)
        {
            // The field stays a bare type name so it can be asserted, but a
            // type name alone is not diagnosable: log the whole thing.
            // Not the [CoexistenceProbe] tag: the harness takes the first line
            // carrying it as the marker, and this one would shadow it.
            Debug.Log("[CoexistenceProbe/detail] compiler-boots threw: " + e);
            return e.GetType().Name;
        }
#else
        return "absent";
#endif
    }
}
