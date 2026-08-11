using System;
using System.IO;
using UnityEditor.PackageManager;

namespace Magic.Unity
{
    // The pre-build rewrite must read the package's own runtime DLLs. They
    // cannot be located through typeof(...).Assembly.Location or
    // Assembly.Load: when a consumer keeps a ClojureCLR Clojure.dll in
    // Assets, Unity dedups Clojure.dll by file name and those anchors bind
    // to that copy. Resolve the package install path instead; resolvedPath
    // is the physical location for git, registry, local and embedded packages.
    internal static class PackageRuntimePath
    {
        static PackageInfo Package => PackageInfo.FindForAssembly(typeof(PackageRuntimePath).Assembly);

        internal static string MagicRuntimeDirectory => Path.Combine(Package.resolvedPath, "Runtime", "magic");

        internal static string MagicRuntimeDll => Path.Combine(MagicRuntimeDirectory, "Magic.Runtime.dll");

        internal static string MagicRuntimeAssetPath => Package.assetPath + "/Runtime/magic";

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
