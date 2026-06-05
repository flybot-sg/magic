using System.IO;
using UnityEditor.PackageManager;

namespace Magic.Unity
{
    // The pre-build rewrite must read the package's own runtime DLLs. They
    // cannot be located through typeof(...).Assembly.Location or
    // Assembly.Load: when a consumer keeps a stock ClojureCLR in Assets,
    // Unity dedups Clojure.dll by file name and those anchors bind to the
    // stock copy. Resolve the package install path instead; resolvedPath is
    // the physical location for git, registry, local and embedded packages.
    internal static class PackageExportPath
    {
        internal static string ExportDirectory
        {
            get
            {
                var package = PackageInfo.FindForAssembly(typeof(PackageExportPath).Assembly);
                return Path.Combine(package.resolvedPath, "Runtime", "Infrastructure", "Export");
            }
        }

        internal static string MagicRuntimeDll => Path.Combine(ExportDirectory, "Magic.Runtime.dll");
    }
}
