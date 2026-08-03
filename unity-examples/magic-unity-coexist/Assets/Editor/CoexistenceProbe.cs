using System;
using System.Linq;
using UnityEditor.Compilation;
using UnityEngine;
// UnityEditor.Compilation defines its own Assembly type; alias the reflection
// one rather than dropping the using, since the whole point of this probe is to
// run stock RT's own Assembly.Load of clojure.core.clj.
using Assembly = System.Reflection.Assembly;

// Headless assertion helper for the dual-runtime regression. Reports which
// Clojure runtime the Editor actually ended up with, and reproduces the exact
// init-time probe stock ClojureCLR's RT runs (Assembly.Load("clojure.core.clj")).
//
// The package ships both runtimes and a define constraint on every DLL picks
// one, so there are two valid steady states (docs/dual-runtimes.md):
//
//   MAGIC_RUNTIME_IN_EDITOR unset (default)  stock Editor
//     symbol=unset preloaded-clj=0 core-clj-loadable=false clojure-versions=[1.11.0.0]
//   MAGIC_RUNTIME_IN_EDITOR set              MAGIC Editor
//     symbol=set   preloaded-clj=<stdlib+consumer> core-clj-loadable=true clojure-versions=[1.0.0.0]
//
// In the stock state the fork clj.dll are excluded from the editor domain, so
// the stock probe fails (nothing is there to answer it) and #25 stays fixed by
// construction rather than by a guard. In the MAGIC state the fork wins the
// Clojure.dll dedup and the whole stdlib is loadable.
//
// Run headless with -executeMethod CoexistenceProbe.Run; grep the log for the
// single [CoexistenceProbe] marker line alongside the narration-line count.
public static class CoexistenceProbe
{
    // Read from the compiled define set, not from PlayerSettings: this is the
    // symbol as the Editor compilation actually saw it, which is what the
    // plugin define constraints were evaluated against. Without it a null
    // result cannot be told from a symbol that never reached the compiler.
#if MAGIC_RUNTIME_IN_EDITOR
    const string SymbolState = "set";
#else
    const string SymbolState = "unset";
#endif

    public static void Run()
    {
        var preloaded = AppDomain.CurrentDomain.GetAssemblies()
            .Select(a => a.GetName().Name)
            .Where(n => n.EndsWith(".clj", StringComparison.OrdinalIgnoreCase))
            .OrderBy(n => n)
            .ToArray();

        bool coreLoadable;
        string loadDetail;
        try
        {
            var asm = Assembly.Load("clojure.core.clj");
            coreLoadable = asm != null;
            // name/version, not FullName: the marker line is parsed as
            // space-separated key=value pairs and FullName has spaces in it.
            loadDetail = asm == null ? "null" : asm.GetName().Name + "/" + asm.GetName().Version;
        }
        catch (Exception e)
        {
            coreLoadable = false;
            loadDetail = e.GetType().Name;
        }

        var clojureVersions = AppDomain.CurrentDomain.GetAssemblies()
            .Where(a => a.GetName().Name == "Clojure")
            .Select(a => a.GetName().Version.ToString())
            .OrderBy(v => v)
            .ToArray();

        Debug.Log($"[CoexistenceProbe] symbol={SymbolState} "
                  + $"preloaded-clj={preloaded.Length} "
                  + $"core-clj-loadable={coreLoadable.ToString().ToLowerInvariant()} "
                  + $"core-clj-load={loadDetail} "
                  + $"clojure-versions=[{string.Join(",", clojureVersions)}] "
                  + $"editor-clj-refs={CljReferences(AssembliesType.Editor)} "
                  + $"player-clj-refs={CljReferences(AssembliesType.PlayerWithoutTestAssemblies)}");
    }

    // The static half of the check: player references must not move when the
    // symbol does. They are what an IL2CPP build ships, and the MAGIC
    // constraint is satisfied in a player whatever the symbol says -- but only
    // because the symbol appears there un-negated, so it is worth measuring.
    static int CljReferences(AssembliesType type)
    {
        return CompilationPipeline.GetAssemblies(type)
            .SelectMany(a => a.allReferences)
            .Where(r => r.EndsWith(".clj.dll", StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Count();
    }
}
