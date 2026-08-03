using System;
using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Magic.Unity
{
    // A consumer's own MAGIC-compiled .clj.dll under Assets/ is imported with
    // no define constraint, so it stays Editor-eligible even when the MAGIC
    // runtime is excluded: it is admitted to the Editor domain binding an
    // unsigned Clojure 1.0.0.0 that is not loaded, with no warning and no log
    // line at all. The mismatch then surfaces only when something touches a
    // type from it. Stamp the constraint the package's own Export DLLs carry,
    // so consumer DLLs follow the Editor runtime selection with them.
    //
    // Assets/ only. Package metas are immutable on a PackageCache install, and
    // every DLL this package ships is constrained at authoring time instead
    // (enforced by magic.unity/check-constraints! under bb check-drift).
    //
    // A *.clj.dll is always MAGIC-compiled: stock ClojureCLR ships its stdlib
    // as Clojure.dll + Clojure.Source.dll and its side libraries as
    // clojure.spec.alpha.dll, never clojure.spec.alpha.clj.dll. So the
    // extension decides on its own -- no need to read the assembly's Clojure
    // reference, as the coexistence guard this replaces had to.
    internal class CljPluginConstraints : AssetPostprocessor
    {
        internal const string MagicConstraint = "!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR";

        void OnPreprocessAsset()
        {
            if (!assetPath.StartsWith("Assets/", StringComparison.Ordinal)
                || !PlayerCljAssemblies.IsCljAssembly(assetPath))
            {
                return;
            }
            var importer = assetImporter as PluginImporter;
            if (importer == null)
            {
                return;
            }
            var constraints = importer.DefineConstraints ?? new string[0];
            if (constraints.Contains(MagicConstraint, StringComparer.Ordinal))
            {
                return;
            }
            // Appended, never replaced: Unity ANDs the entries of a block, so a
            // constraint the consumer added themselves keeps holding.
            importer.DefineConstraints = constraints.Concat(new[] { MagicConstraint }).ToArray();
            Debug.Log($"[Magic.Unity/CljPluginConstraints] {assetPath}: follows the Editor Clojure runtime "
                      + $"({EditorRuntime.Symbol})");
        }
    }
}
