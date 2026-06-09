using System;
using System.IO;
using System.Linq;
using Mono.Cecil;
using UnityEditor;
using UnityEngine;

namespace Magic.Unity
{
    // Stock clojure.lang.RT probes Assembly.Load("clojure.core.clj") during
    // init; finding the fork-compiled DLL throws a TypeLoadException storm.
    // So while a foreign (strong-named) Clojure.dll is present under
    // Assets, fork .clj.dll plugins are imported with editor loading off.
    // Only the clj.dlls: excluding the fork Clojure.dll itself would leave
    // Magic.Unity with no Clojure to compile against once the stock copy
    // is removed, breaking the revert under batch mode.
    //
    // Separate bootstrap class: import callbacks touch the helper class,
    // and a static ctor that throws mid import (asset database calls do)
    // would poison it for the whole domain.
    [InitializeOnLoad]
    internal static class StockClojureCoexistenceBootstrap
    {
        static StockClojureCoexistenceBootstrap()
        {
            // delayCall may never fire before a batch run ends; the direct
            // call throws when the domain load is inside an import batch.
            if (Application.isBatchMode)
            {
                try
                {
                    StockClojureCoexistence.Reconcile();
                }
                catch (UnityException)
                {
                    EditorApplication.delayCall += StockClojureCoexistence.Reconcile;
                }
            }
            else
            {
                EditorApplication.delayCall += StockClojureCoexistence.Reconcile;
            }
        }
    }

    internal static class StockClojureCoexistence
    {
        // The state file shares the artifact database's lifetime: a Library
        // wipe loses both, and the rebuilt artifacts go through the
        // preprocessor with the current state anyway.
        public static void Reconcile()
        {
            var foreign = ForeignClojurePresent();
            var current = foreign ? "foreign-clojure" : "pure-magic";
            if (LastAppliedState() == current)
            {
                return;
            }
            Debug.Log(foreign
                ? "[Magic.Unity/StockClojureCoexistence] foreign Clojure.dll detected, reimporting fork clj.dll plugins with editor loading off"
                : "[Magic.Unity/StockClojureCoexistence] no foreign Clojure.dll present, reimporting fork clj.dll plugins with their pristine settings");
            foreach (var importer in PluginImporter.GetAllImporters())
            {
                if (!importer.isNativePlugin && importer.assetPath.EndsWith(".clj.dll", StringComparison.OrdinalIgnoreCase))
                {
                    AssetDatabase.ImportAsset(importer.assetPath);
                }
            }
            try
            {
                File.WriteAllText(StateFilePath, current);
            }
            catch (Exception e)
            {
                Debug.LogWarning($"[Magic.Unity/StockClojureCoexistence] could not write {StateFilePath}: {e.Message}");
            }
        }

        static string StateFilePath =>
            Path.Combine(Path.GetDirectoryName(Application.dataPath), "Library", "MagicUnityCoexistenceState.txt");

        static string LastAppliedState()
        {
            try
            {
                return File.Exists(StateFilePath) ? File.ReadAllText(StateFilePath).Trim() : "";
            }
            catch (Exception)
            {
                return "";
            }
        }

        // Filesystem scan, not a PluginImporter scan: the asset database is
        // not queryable inside import callbacks. A vendored fork copy in
        // Assets has no strong name and stays non-foreign.
        internal static bool ForeignClojurePresent()
        {
            try
            {
                return Directory.EnumerateFiles(Application.dataPath, "Clojure.dll", SearchOption.AllDirectories).Any(IsStockClojure);
            }
            catch (Exception e)
            {
                Debug.LogWarning($"[Magic.Unity/StockClojureCoexistence] foreign Clojure scan failed: {e.Message}");
                return false;
            }
        }

        static bool IsStockClojure(string path)
        {
            try
            {
                var token = System.Reflection.AssemblyName.GetAssemblyName(path).GetPublicKeyToken();
                return token != null && token.Length > 0;
            }
            catch (Exception e)
            {
                Debug.LogWarning($"[Magic.Unity/StockClojureCoexistence] could not inspect {path}: {e.Message}");
                return false;
            }
        }

        internal static bool ReferencesForkClojure(string path)
        {
            try
            {
                using (var assembly = AssemblyDefinition.ReadAssembly(path))
                {
                    var clojureReference = assembly.MainModule.AssemblyReferences.FirstOrDefault(r => r.Name == "Clojure");
                    return clojureReference != null
                        && (clojureReference.PublicKeyToken == null || clojureReference.PublicKeyToken.Length == 0);
                }
            }
            catch (Exception e)
            {
                Debug.LogWarning($"[Magic.Unity/StockClojureCoexistence] could not inspect {path}: {e.Message}");
                return false;
            }
        }

        internal static System.Collections.Generic.IEnumerable<BuildTarget> ValidBuildTargets()
        {
            foreach (BuildTarget target in Enum.GetValues(typeof(BuildTarget)))
            {
                if ((int)target < 0)
                {
                    continue;
                }
                var field = typeof(BuildTarget).GetField(target.ToString());
                if (field == null || field.GetCustomAttributes(typeof(ObsoleteAttribute), false).Any())
                {
                    continue;
                }
                yield return target;
            }
        }
    }

    // Importer changes made here are written back to the .meta on mutable
    // installs (the userData marker drives the restore) and stay
    // artifact-only on immutable installs, where the pristine .meta is the
    // restore state.
    internal class CljPluginPreprocessor : AssetPostprocessor
    {
        void OnPreprocessAsset()
        {
            if (!assetPath.EndsWith(".clj.dll", StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
            var importer = assetImporter as PluginImporter;
            if (importer == null)
            {
                return;
            }
            if (StockClojureCoexistence.ForeignClojurePresent())
            {
                DisableEditorLoading(importer);
            }
            else
            {
                RestoreEditorLoading(importer);
            }
        }

        // SetExcludeEditorFromAnyPlatform is not honored by the plugin
        // loader, hence the switch to explicit per-platform compatibility.
        // Restore goes by marker only, never touching a plugin the consumer
        // made editor-only themselves.
        const string AnyPlatformMarker = "Magic.Unity.StockClojureCoexistence:any-platform";
        const string CustomPlatformsMarker = "Magic.Unity.StockClojureCoexistence:custom-platforms";

        static void DisableEditorLoading(PluginImporter importer)
        {
            if (HasMarker(importer)
                || (!importer.GetCompatibleWithAnyPlatform() && !importer.GetCompatibleWithEditor()))
            {
                return;
            }
            if (!StockClojureCoexistence.ReferencesForkClojure(PackageExportPath.PhysicalPath(importer.assetPath)))
            {
                return;
            }
            string marker;
            if (importer.GetCompatibleWithAnyPlatform())
            {
                importer.SetCompatibleWithAnyPlatform(false);
                foreach (var target in StockClojureCoexistence.ValidBuildTargets())
                {
                    try { importer.SetCompatibleWithPlatform(target, true); } catch { }
                }
                marker = AnyPlatformMarker;
            }
            else
            {
                marker = CustomPlatformsMarker;
            }
            AddMarker(importer, marker);
            importer.SetCompatibleWithEditor(false);
            Debug.Log($"[Magic.Unity/StockClojureCoexistence] editor loading off for {importer.assetPath}");
        }

        static void RestoreEditorLoading(PluginImporter importer)
        {
            if (!HasMarker(importer))
            {
                return;
            }
            var wasAnyPlatform = importer.userData.Contains(AnyPlatformMarker);
            RemoveMarker(importer);
            importer.SetCompatibleWithEditor(true);
            if (wasAnyPlatform)
            {
                importer.SetCompatibleWithAnyPlatform(true);
                importer.SetExcludeEditorFromAnyPlatform(false);
            }
            Debug.Log($"[Magic.Unity/StockClojureCoexistence] editor loading on for {importer.assetPath}");
        }

        static bool HasMarker(PluginImporter importer)
        {
            return importer.userData != null && importer.userData.Contains("Magic.Unity.StockClojureCoexistence:");
        }

        static void AddMarker(PluginImporter importer, string marker)
        {
            importer.userData = string.IsNullOrEmpty(importer.userData) ? marker : marker + ";" + importer.userData;
        }

        static void RemoveMarker(PluginImporter importer)
        {
            importer.userData = importer.userData
                .Replace(AnyPlatformMarker + ";", "").Replace(AnyPlatformMarker, "")
                .Replace(CustomPlatformsMarker + ";", "").Replace(CustomPlatformsMarker, "");
        }
    }
}
