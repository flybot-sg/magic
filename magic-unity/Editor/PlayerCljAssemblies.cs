using System;
using System.Collections.Generic;
using System.Linq;
using UnityEditor.Compilation;

namespace Magic.Unity
{
    // Discovers clj assemblies from player compilation references, never by
    // scanning the loaded AppDomain: when the Editor runs ClojureCLR
    // (the default), the MAGIC runtime is excluded from the editor domain
    // (see EditorRuntime) and that scan is a silent no-op -- no workarounds
    // generated, no link.xml entries, devices fail at runtime. The player
    // references are the set that actually ships, independent of editor state.
    internal static class PlayerCljAssemblies
    {
        // Mirrors source-extensions in magic-compiler; nothing enforces the match.
        static readonly string[] Extensions = { ".clj.dll", ".cljc.dll", ".cljr.dll" };

        internal static bool IsCljAssembly(string path)
        {
            foreach (var extension in Extensions)
            {
                if (path.EndsWith(extension, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }

        internal static List<string> Paths()
        {
            var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var assembly in CompilationPipeline.GetAssemblies(AssembliesType.PlayerWithoutTestAssemblies))
            {
                foreach (var reference in assembly.allReferences)
                {
                    if (IsCljAssembly(reference))
                    {
                        paths.Add(PackageRuntimePath.PhysicalPath(reference));
                    }
                }
            }
            if (paths.Count == 0)
            {
                throw new InvalidOperationException("[Magic.Unity] no clj player compilation references found (" + string.Join(", ", Extensions) + "), refusing to continue with an empty clj assembly set");
            }
            return paths.OrderBy(p => p, StringComparer.OrdinalIgnoreCase).ToList();
        }
    }
}
