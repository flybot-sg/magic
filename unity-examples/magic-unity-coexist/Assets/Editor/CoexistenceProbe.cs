using System;
using System.Linq;
using System.Reflection;
using UnityEditor;
using UnityEngine;

// Headless assertion helper for the coexistence repro. Reports the editor-domain
// state the coexistence guard is responsible for.
//
// The Assembly.Load below is a canary, not a reproduction of what ClojureCLR
// does: ClojureCLR resolves a namespace by scanning the loaded assemblies for a
// <ns>__Init type and taking the first match, never by simple name. Both routes
// need the same thing though, so resolving the fork clojure.core.clj here means
// it is in the domain and can win that scan.
//
// In a correctly guarded coexisting editor the expected steady state is:
//   preloaded-clj=0  core-clj-loadable=false  clojure-versions=[1.11.0.0]
// i.e. the fork clj.dll plugins are excluded from the editor domain, so the
// load fails (the fork DLL is not there to answer it) and only ClojureCLR
// 1.11.0 wins the Clojure.dll dedup. If the guard regresses, the probe
// resolves the fork clojure.core.clj and the TypeLoadException storm returns.
//
// Run headless with -executeMethod CoexistenceProbe.Run; grep the log for the
// single [CoexistenceProbe] marker line alongside the narration-line count.
public static class CoexistenceProbe
{
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
            loadDetail = asm?.GetName().FullName ?? "null";
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

        var forkExportCljEditorOff = PluginImporter.GetAllImporters()
            .Count(i => !i.isNativePlugin
                        && i.assetPath.EndsWith(".clj.dll", StringComparison.OrdinalIgnoreCase)
                        && i.assetPath.Contains("/Runtime/Infrastructure/Export/")
                        && !i.GetCompatibleWithEditor());

        Debug.Log($"[CoexistenceProbe] preloaded-clj={preloaded.Length} "
                  + $"core-clj-loadable={coreLoadable.ToString().ToLowerInvariant()} "
                  + $"core-clj-load={loadDetail} "
                  + $"clojure-versions=[{string.Join(",", clojureVersions)}] "
                  + $"export-clj-editor-off={forkExportCljEditorOff}");
    }
}
