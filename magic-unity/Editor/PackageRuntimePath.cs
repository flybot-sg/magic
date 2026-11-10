using System;
using System.IO;
using UnityEditor.PackageManager;

namespace Magic.Unity
{
    // Located by package path, not Assembly.Load: the default (ClojureCLR)
    // Editor loads ClojureCLR's Clojure and no Magic.Runtime.
    internal static class PackageRuntimePath
    {
        static PackageInfo Package => PackageInfo.FindForAssembly(typeof(PackageRuntimePath).Assembly);

        internal static string MagicRuntimeDirectory => Path.Combine(Package.resolvedPath, "Runtime", "magic");

        internal static string MagicRuntimeDll => Path.Combine(MagicRuntimeDirectory, "Magic.Runtime.dll");

        internal static string MagicRuntimeAssetPath => Package.assetPath + "/Runtime/magic";

        // Asset paths under Packages/ are virtual; File and Cecil need the
        // physical location.
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
