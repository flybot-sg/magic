using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Magic.Unity
{
    // A consumer's .clj.dll under Assets/ is release output, which only players
    // load; a MAGIC Editor compiles those namespaces from source.
    //
    // Two entry points: OnPreprocessAsset for DLLs as they import, Reconcile for
    // DLLs already imported when this package version arrived -- a package
    // install does not dirty the project, so the callback never re-runs for those.
    internal sealed class CljPluginConstraints : AssetPostprocessor
    {
        // Synced with consumer-entry in bb/magic/unity.clj
        const string PlayerOnly = "!UNITY_EDITOR";

        // What earlier versions stamped.
        const string MagicEditorEntry = "!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR";

        const string Tag = "[Magic.Unity/CljPluginConstraints]";
        const int NamedInSummary = 5;

        // Constrained on import, not yet reported. Import callbacks are main-thread.
        static readonly List<string> _pendingReport = new List<string>();

        static int _staleStamped;

        void OnPreprocessAsset()
        {
            if (assetImporter is not PluginImporter importer || !NeedsStamp(importer))
            {
                return;
            }
            if (!importer.importSettingsMissing && InDomain(importer.DefineConstraints))
            {
                _staleStamped++;
            }
            importer.DefineConstraints = new[] { PlayerOnly };
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
            var work = PluginImporter.GetAllImporters().Where(NeedsStamp).ToList();
            if (work.Count == 0)
            {
                return;
            }

            var refused = new List<string>();
            var written = new List<(PluginImporter importer, bool inDomain)>();
            AssetDatabase.StartAssetEditing();
            try
            {
                foreach (var importer in work)
                {
                    try
                    {
                        var inDomain = InDomain(importer.DefineConstraints);
                        // Constrained before the reimport, so OnPreprocessAsset
                        // does not duplicate the reporting of the stamp
                        importer.DefineConstraints = new[] { PlayerOnly };
                        importer.SaveAndReimport();
                        written.Add((importer, inDomain));
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
            var stale = 0;
            foreach (var (importer, inDomain) in written)
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
                    if (inDomain)
                    {
                        stale++;
                    }
                }
                else
                {
                    refused.Add($"{importer.assetPath} ({complaint})");
                }
            }

            if (constrained > 0)
            {
                Debug.Log(
                    $"{Tag} made {constrained} already-imported "
                        + $"{Assemblies(constrained)} player-only."
                );
            }
            if (refused.Count > 0)
            {
                Debug.LogWarning(
                    $"{Tag} {refused.Count} {Assemblies(refused.Count)} would not take the "
                        + "player-only constraint and may still load in the Editor. Usually a"
                        + $" read-only .meta: {Named(refused)}"
                );
            }
            RequestReloadForStaleDomain(stale);
        }

        // Writing the meta settles where the assembly loads, but the running
        // domain was built before that
        static void RequestReloadForStaleDomain(int stale)
        {
            // Batch runs are one launch anyway, and a reload queued under -quit
            // is untested; convergence there waits for the caller's next launch.
            if (Application.isBatchMode || stale <= 0)
            {
                return;
            }
            Debug.Log(
                $"{Tag} reloading scripts: {stale} {Assemblies(stale)} "
                    + "loaded before they were made player-only."
            );
            // Both callers can be inside an import; reload on the next tick instead.
            EditorApplication.delayCall += EditorUtility.RequestScriptReload;
        }

        // Package DLLs keep their authored constraints.
        static bool NeedsStamp(PluginImporter importer)
        {
            return importer != null
                && importer.assetPath != null
                && importer.assetPath.StartsWith("Assets/", StringComparison.Ordinal)
                && PlayerCljAssemblies.IsCljAssembly(importer.assetPath)
                && NeedsConstraint(importer.DefineConstraints);
        }

        static bool NeedsConstraint(string[] constraints)
        {
            return constraints == null
                || constraints.Length != 1
                || constraints[0]?.Trim() != PlayerOnly;
        }

        // Whether the running domain loaded the assembly under these constraints.
        static bool InDomain(string[] constraints)
        {
            var entries = (constraints ?? Array.Empty<string>()).Select(e => e?.Trim());
#if MAGIC_RUNTIME_IN_EDITOR
            return entries.All(e => e == MagicEditorEntry);
#else
            return !entries.Any();
#endif
        }

        // Null when the meta on disk carries exactly the constraint.
        static string DiskComplaint(string assetPath)
        {
            // against the project root, not the working directory
            var meta = Path.Combine(Path.GetDirectoryName(Application.dataPath), assetPath + ".meta");
            try
            {
                if (!File.Exists(meta))
                {
                    return $"no .meta at {meta}";
                }
                return NeedsConstraint(MetaConstraints(File.ReadAllLines(meta)))
                    ? "meta on disk is not player-only"
                    : null;
            }
            catch (Exception e)
            {
                return $"meta unreadable ({e.GetType().Name})";
            }
        }

        // The defineConstraints entries of a plugin meta, unquoted; `[]` reads none.
        static string[] MetaConstraints(string[] lines)
        {
            return lines
                .SkipWhile(line => !line.Trim().StartsWith("defineConstraints:", StringComparison.Ordinal))
                .Skip(1)
                .Select(line => line.Trim())
                .TakeWhile(line => line.StartsWith("- ", StringComparison.Ordinal))
                .Select(line => line.Substring(2).Trim().Trim('\''))
                .ToArray();
        }

        static void FlushReport()
        {
            if (_pendingReport.Count == 0)
            {
                return;
            }
            Debug.Log(
                $"{Tag} made {_pendingReport.Count} imported "
                    + $"{Assemblies(_pendingReport.Count)} player-only: "
                    + Named(_pendingReport)
            );
            RequestReloadForStaleDomain(_staleStamped);
            _staleStamped = 0;
            _pendingReport.Clear();
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
