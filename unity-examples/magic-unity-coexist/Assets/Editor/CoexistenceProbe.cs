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

    static bool IsCljAssembly(string name, string suffix)
    {
        return Extensions.Any(e => name.EndsWith(e + suffix, StringComparison.OrdinalIgnoreCase));
    }

    public static void Run()
    {
        var csharpInDomain = AppDomain
            .CurrentDomain.GetAssemblies()
            .Any(a => a.GetName().Name == CsharpName);

        var preloaded = AppDomain
            .CurrentDomain.GetAssemblies()
            .Select(a => a.GetName().Name)
            .Where(n => IsCljAssembly(n, ""))
            .OrderBy(n => n)
            .ToArray();

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
        return ReferenceCount(type, r => IsCljAssembly(r, ".dll"));
    }
}
