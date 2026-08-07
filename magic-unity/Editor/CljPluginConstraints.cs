using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Magic.Unity
{
    // A consumer's .clj.dll imports with no define constraint, so it needs the
    // same one the package's own MAGIC DLLs carry.
    //
    // Two entry points: OnPreprocessAsset for DLLs as they import, Reconcile for
    // DLLs already imported when this package version arrived -- a package
    // install does not dirty the project, so the callback never re-runs for those.
    internal sealed class CljPluginConstraints : AssetPostprocessor
    {
        // Synced with the :magic block in bb/magic/unity.clj
        const string MagicConstraint = "!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR";

        const string Tag = "[Magic.Unity/CljPluginConstraints]";
        const int NamedInSummary = 5;

        const string ImmutableReportedKey = "Magic.Unity.CljPluginConstraints.immutableReported";

        // Constrained on import, not yet reported. Import callbacks are main-thread.
        static readonly List<string> _pendingReport = new List<string>();

        static int _staleStamped;

        void OnPreprocessAsset()
        {
            if (assetImporter is not PluginImporter importer || !ShouldConstrain(importer))
            {
                return;
            }
            if (!importer.importSettingsMissing)
            {
                _staleStamped++;
            }
            importer.DefineConstraints = Constrain(importer.DefineConstraints);
            _pendingReport.Add(assetPath);
        }

        // Unity calls this after every domain reload, imports or not, with the
        // asset database past import.
        static void OnPostprocessAllAssets(
            string[] importedAssets,
            string[] deletedAssets,
            string[] movedAssets,
            string[] movedFromAssetPaths,
            bool didDomainReload
        )
        {
            // The import batch is over, which is what the stamps were waiting on.
            FlushReport();
            if (!didDomainReload)
            {
                return;
            }
            // A delayCall may never fire before the run ends for a batch run
            if (Application.isBatchMode)
            {
                RunReconcile();
            }
            else
            {
                EditorApplication.delayCall += RunReconcile;
            }
        }

        static void RunReconcile()
        {
            try
            {
                Reconcile();
            }
            catch (Exception e)
            {
                Debug.LogWarning(
                    $"{Tag} reconcile failed ({e.GetType().Name}: {e.Message});"
                        + " the next domain load retries."
                );
            }
        }

        static void Reconcile()
        {
            var outdated = new List<PluginImporter>();
            var immutable = new List<string>();
            foreach (var importer in PluginImporter.GetAllImporters().Where(Unconstrained))
            {
                if (IsWritableLocation(importer.assetPath))
                {
                    outdated.Add(importer);
                }
                else
                {
                    immutable.Add(importer.assetPath);
                }
            }

            ReportImmutable(immutable);
            if (outdated.Count == 0)
            {
                return;
            }

            var refused = new List<string>();
            var written = new List<PluginImporter>();
            AssetDatabase.StartAssetEditing();
            try
            {
                foreach (var importer in outdated)
                {
                    try
                    {
                        // Constrained before the reimport, so OnPreprocessAsset
                        // does not duplicate the reporting of the stamp
                        importer.DefineConstraints = Constrain(importer.DefineConstraints);
                        importer.SaveAndReimport();
                        written.Add(importer);
                    }
                    catch (Exception e)
                    {
                        refused.Add($"{importer.assetPath} ({e.GetType().Name})");
                    }
                }
            }
            finally
            {
                AssetDatabase.StopAssetEditing();
            }

            // SaveAndReimport only queues, and that queue does not drain before
            // this returns, so flush the meta explicitly. Then check the file,
            // because a declined write does not throw.
            var constrained = 0;
            foreach (var importer in written)
            {
                try
                {
                    AssetDatabase.WriteImportSettingsIfDirty(importer.assetPath);
                }
                catch (Exception)
                {
                    // DiskComplaint reads the file next and says so with a reason.
                }
                var complaint = DiskComplaint(importer.assetPath);
                if (complaint == null)
                {
                    constrained++;
                }
                else
                {
                    refused.Add($"{importer.assetPath} ({complaint})");
                }
            }

            if (constrained > 0)
            {
                Debug.Log(
                    $"{Tag} brought {constrained} already-imported "
                        + $"{Assemblies(constrained)} under {EditorRuntime.Symbol}."
                );
            }
            if (refused.Count > 0)
            {
                Debug.LogWarning(
                    $"{Tag} {refused.Count} {Assemblies(refused.Count)} would not take the "
                        + "constraint and stay visible to the Editor. Usually a read-only "
                        + $".meta: {Named(refused)}"
                );
            }
            RequestReloadForStaleDomain(constrained);
        }

        // Writing the constraint fixes the meta, but the running domain was
        // built while the assembly was unconstrained
        static void RequestReloadForStaleDomain(int stamped)
        {
            // Batch runs are one launch anyway, and a reload queued under -quit
            // is untested; convergence there waits for the caller's next launch.
            if (Application.isBatchMode || stamped <= 0)
            {
                return;
            }
            Debug.Log(
                $"{Tag} reloading scripts: {stamped} {Assemblies(stamped)} "
                    + "loaded before the constraint was applied."
            );
            // Both callers can be inside an import; reload on the next tick instead.
            EditorApplication.delayCall += EditorUtility.RequestScriptReload;
        }

        static bool Unconstrained(PluginImporter importer)
        {
            return importer != null
                && importer.assetPath != null
                && PlayerCljAssemblies.IsCljAssembly(importer.assetPath)
                && NeedsConstraint(importer.DefineConstraints);
        }

        static bool ShouldConstrain(PluginImporter importer)
        {
            return Unconstrained(importer) && IsWritableLocation(importer.assetPath);
        }

        // A PackageCache is immutable. A file: dependency is `Local` only when
        // it points at a directory, a tarball is an immutable `LocalTarball`.
        static bool IsWritableLocation(string assetPath)
        {
            if (assetPath.StartsWith("Assets/", StringComparison.Ordinal))
            {
                return true;
            }
            if (!assetPath.StartsWith("Packages/", StringComparison.Ordinal))
            {
                return false;
            }
            var package = UnityEditor.PackageManager.PackageInfo.FindForAssetPath(assetPath);
            return package != null
                && (
                    package.source == UnityEditor.PackageManager.PackageSource.Embedded
                    || package.source == UnityEditor.PackageManager.PackageSource.Local
                );
        }

        static bool NeedsConstraint(string[] constraints)
        {
            return constraints == null
                || !constraints.Any(c =>
                    string.Equals(c?.Trim(), MagicConstraint, StringComparison.Ordinal)
                );
        }

        static string[] Constrain(string[] constraints)
        {
            return (constraints ?? Array.Empty<string>())
                .Concat(new[] { MagicConstraint })
                .ToArray();
        }

        static string DiskComplaint(string assetPath)
        {
            string meta;
            try
            {
                meta = MetaPath(assetPath);
            }
            catch (Exception e)
            {
                return $"meta path unresolvable ({e.GetType().Name})";
            }
            try
            {
                if (!File.Exists(meta))
                {
                    return $"no .meta at {meta}";
                }
                return File.ReadAllText(meta).Contains(MagicConstraint)
                    ? null
                    : "meta on disk still lacks the constraint";
            }
            catch (Exception e)
            {
                return $"meta unreadable ({e.GetType().Name})";
            }
        }

        // A Packages/ path is virtual and a local package can resolve outside
        // the project.
        static string MetaPath(string assetPath)
        {
            var physical = PackageRuntimePath.PhysicalPath(assetPath) + ".meta";
            return Path.IsPathRooted(physical)
                ? physical
                // against the project root, not the working directory
                : Path.Combine(Path.GetDirectoryName(Application.dataPath), physical);
        }

        static void FlushReport()
        {
            if (_pendingReport.Count == 0)
            {
                return;
            }
            Debug.Log(
                $"{Tag} constrained {_pendingReport.Count} imported "
                    + $"{Assemblies(_pendingReport.Count)} to {EditorRuntime.Symbol}: "
                    + Named(_pendingReport)
            );
            RequestReloadForStaleDomain(_staleStamped);
            _staleStamped = 0;
            _pendingReport.Clear();
        }

        static void ReportImmutable(IReadOnlyList<string> assetPaths)
        {
            var sorted = assetPaths.OrderBy(p => p, StringComparer.Ordinal).ToList();
            var current = string.Join("\n", sorted);
            // An unchanged set was already logged on an earlier domain reload.
            if (current == SessionState.GetString(ImmutableReportedKey, ""))
            {
                return;
            }
            SessionState.SetString(ImmutableReportedKey, current);
            if (sorted.Count == 0)
            {
                return;
            }
            Debug.LogWarning(
                $"{Tag} {sorted.Count} {Assemblies(sorted.Count)} in an immutable package are"
                    + $" not under {EditorRuntime.Symbol} and stay visible to the Editor:"
                    + $" {Named(sorted)}. Unity discards a .meta write under"
                    + " Library/PackageCache, so the constraint has to ship with the package --"
                    + " ask its author, or consume the package embedded."
            );
        }

        static string Named(IReadOnlyList<string> assetPaths)
        {
            var named = string.Join(", ", assetPaths.Take(NamedInSummary));
            var rest = assetPaths.Count - NamedInSummary;
            return rest > 0 ? $"{named}, and {rest} more" : named;
        }

        static string Assemblies(int count)
        {
            return count == 1 ? "assembly" : "assemblies";
        }
    }
}
