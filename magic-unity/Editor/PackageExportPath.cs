using System;
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
        static PackageInfo Package => PackageInfo.FindForAssembly(typeof(PackageExportPath).Assembly);

        internal static string ExportDirectory => Path.Combine(Package.resolvedPath, "Runtime", "Infrastructure", "Export");

        internal static string MagicRuntimeDll => Path.Combine(ExportDirectory, "Magic.Runtime.dll");

        internal static string ExportAssetPath => Package.assetPath + "/Runtime/Infrastructure/Export";

        // Asset paths under Packages/ are virtual; map them to the physical
        // location before any File or Cecil access. Assets/ paths and
        // absolute paths pass through.
        internal static string PhysicalPath(string assetPath)
        {
            if (assetPath.StartsWith("Packages/", StringComparison.Ordinal))
            {
                var package = PackageInfo.FindForAssetPath(assetPath);
                if (package != null)
                {
                    var relative = assetPath.Substring(package.assetPath.Length + 1);
                    return Path.Combine(package.resolvedPath, relative);
                }
            }
            return assetPath;
        }
    }
}
