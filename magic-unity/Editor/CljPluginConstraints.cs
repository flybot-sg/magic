using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Magic.Unity
{
    // A consumer's .clj.dll imports with no define constraint, so it needs the
    // same one the package's own MAGIC DLLs carry. One coming from the retired
    // dual package also needs its editor loading put back.
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

        // sg.flybot.magic.unity.dual excluded a consumer's clj assemblies from the
        // Editor and recorded in userData the shape it undid. An Editor-excluded
        // plugin ignores MagicConstraint.
        //
        // Delete once all projects are done upgrading from that package.
        const string DualPackage = "sg.flybot.magic.unity.dual";
        const string CoexistenceMarker = "Magic.Unity.StockClojureCoexistence:";
        const string AnyPlatformMarker = "Magic.Unity.StockClojureCoexistence:any-platform";

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
            var stamping = new HashSet<PluginImporter>();
            var restoring = new HashSet<PluginImporter>();
            var immutable = new List<string>();
            var work = new List<PluginImporter>();
            foreach (var importer in PluginImporter.GetAllImporters())
            {
                var needsConstraint = Unconstrained(importer);
                var needsRestore = Excluded(importer);
                if (!needsConstraint && !needsRestore)
                {
                    continue;
                }
                if (!IsWritableLocation(importer.assetPath))
                {
                    if (needsConstraint)
                    {
                        immutable.Add(importer.assetPath);
                    }
                    continue;
                }
                if (needsConstraint)
                {
                    stamping.Add(importer);
                }
                if (needsRestore)
                {
                    restoring.Add(importer);
                }
                work.Add(importer);
            }

            ReportImmutable(immutable);
            if (work.Count == 0)
            {
                return;
            }

            var refused = new List<string>();
            var refusedRestore = new List<string>();
            var written = new List<PluginImporter>();
            AssetDatabase.StartAssetEditing();
            try
            {
                foreach (var importer in work)
                {
                    try
                    {
                        // Constrained before the reimport, so OnPreprocessAsset
                        // does not duplicate the reporting of the stamp
                        if (stamping.Contains(importer))
                        {
                            importer.DefineConstraints = Constrain(importer.DefineConstraints);
                        }
                        if (restoring.Contains(importer))
                        {
                            Restore(importer);
                        }
                        importer.SaveAndReimport();
                        written.Add(importer);
                    }
                    catch (Exception e)
                    {
                        if (stamping.Contains(importer))
                        {
                            refused.Add($"{importer.assetPath} ({e.GetType().Name})");
                        }
                        if (restoring.Contains(importer))
                        {
                            refusedRestore.Add($"{importer.assetPath} ({e.GetType().Name})");
                        }
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
            var restored = 0;
            var stale = 0;
            foreach (var importer in written)
            {
                var wrote = false;
                try
                {
                    AssetDatabase.WriteImportSettingsIfDirty(importer.assetPath);
                }
                catch (Exception)
                {
                    // DiskComplaint reads the file next and says so with a reason.
                }
                if (stamping.Contains(importer))
                {
                    var complaint = DiskComplaint(
                        importer.assetPath,
                        MagicConstraint,
                        true,
                        "meta on disk still lacks the constraint"
                    );
                    if (complaint == null)
                    {
                        constrained++;
                        wrote = true;
                    }
                    else
                    {
                        refused.Add($"{importer.assetPath} ({complaint})");
                    }
                }
                if (restoring.Contains(importer))
                {
                    var complaint = DiskComplaint(
                        importer.assetPath,
                        CoexistenceMarker,
                        false,
                        "meta on disk still carries the exclusion marker"
                    );
                    if (complaint == null)
                    {
                        restored++;
                        wrote = true;
                    }
                    else
                    {
                        refusedRestore.Add($"{importer.assetPath} ({complaint})");
                    }
                }
                if (wrote)
                {
                    stale++;
                }
            }

            if (constrained > 0)
            {
                Debug.Log(
                    $"{Tag} brought {constrained} already-imported "
                        + $"{Assemblies(constrained)} under {EditorRuntime.Symbol}."
                );
            }
            if (restored > 0)
            {
                Debug.Log(
                    $"{Tag} restored editor loading on {restored} already-imported "
                        + $"{Assemblies(restored)} that {DualPackage} had excluded."
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
            if (refusedRestore.Count > 0)
            {
                Debug.LogWarning(
                    $"{Tag} {refusedRestore.Count} {Assemblies(refusedRestore.Count)} stay "
                        + $"excluded from the Editor by {DualPackage} and cannot load under "
                        + $"{EditorRuntime.Symbol}. Usually a read-only .meta: "
                        + Named(refusedRestore)
                );
            }
            RequestReloadForStaleDomain(stale);
        }

        // Writing the meta settles which runtime the assembly follows, but the
        // running domain was built before that
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
                    + "loaded before the Editor's runtime selection was applied."
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

        // A plugin the consumer made editor-only themselves is never touched.
        static bool Excluded(PluginImporter importer)
        {
            return importer != null
                && importer.assetPath != null
                && PlayerCljAssemblies.IsCljAssembly(importer.assetPath)
                && importer.userData != null
                && importer.userData.Contains(CoexistenceMarker);
        }

        // Dual turned Any Platform off on that shape only, so read the marker
        // before stripping it. Its per-target bits stay: inert once it is back on.
        static void Restore(PluginImporter importer)
        {
            var wasAnyPlatform = importer.userData.Contains(AnyPlatformMarker);
            importer.userData = StripMarker(importer.userData);
            importer.SetCompatibleWithEditor(true);
            if (wasAnyPlatform)
            {
                importer.SetCompatibleWithAnyPlatform(true);
                importer.SetExcludeEditorFromAnyPlatform(false);
            }
        }

        // Stripping makes a second pass a no-op.
        static string StripMarker(string userData)
        {
            return string.Join(
                ";",
                userData
                    .Split(';')
                    .Where(field => !field.StartsWith(CoexistenceMarker, StringComparison.Ordinal))
            );
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

        // Null when the meta on disk holds `needle` as `present` says it should.
        static string DiskComplaint(string assetPath, string needle, bool present, string mismatch)
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
                return File.ReadAllText(meta).Contains(needle) == present ? null : mismatch;
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
