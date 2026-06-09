using System;
using System.Collections.Generic;
using System.Linq;
using UnityEditor.Compilation;

namespace Magic.Unity
{
    // The pre-build rewrite and the link.xml generator used to discover clj
    // assemblies by scanning the loaded AppDomain. In a coexisting editor
    // the fork runtime DLLs are excluded from the editor domain (see
    // StockClojureCoexistence), which would turn that scan into a silent
    // no-op: no workarounds generated, no link.xml entries, devices fail at
    // runtime. Discover them from player compilation references instead;
    // that is the set that actually ships, independent of editor state.
    internal static class PlayerCljAssemblies
    {
        internal static List<string> Paths()
        {
            var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var assembly in CompilationPipeline.GetAssemblies(AssembliesType.PlayerWithoutTestAssemblies))
            {
                foreach (var reference in assembly.allReferences)
                {
                    if (reference.EndsWith(".clj.dll", StringComparison.OrdinalIgnoreCase))
                    {
                        paths.Add(PackageExportPath.PhysicalPath(reference));
                    }
                }
            }
            if (paths.Count == 0)
            {
                throw new InvalidOperationException("[Magic.Unity] no .clj.dll player compilation references found, refusing to continue with an empty clj assembly set");
            }
            return paths.OrderBy(p => p, StringComparer.OrdinalIgnoreCase).ToList();
        }
    }
}
