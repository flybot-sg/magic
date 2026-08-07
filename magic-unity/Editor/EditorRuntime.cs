using System;
using System.Collections.Generic;
using System.Linq;
using UnityEditor;
using UnityEditor.Build;
using UnityEngine;

namespace Magic.Unity
{
    /// <summary>
    /// Selects the Editor's Clojure runtime by writing
    /// <c>MAGIC_RUNTIME_IN_EDITOR</c> to the active build target.
    /// </summary>
    /// <remarks>
    /// PlayerSettings reads throw for a build target the Editor cannot resolve,
    /// so each entry point catches for itself: the scripting API throws to its
    /// caller with the runtime unchanged, the settings page renders the failure,
    /// and the load-time notices stay quiet rather than repeat on every reload.
    /// </remarks>
    public static class EditorRuntime
    {
        internal const string Symbol = "MAGIC_RUNTIME_IN_EDITOR";

        const string Tag = "[Magic.Unity/EditorRuntime]";

        const string SettingsPath = "Project/MAGIC";

        const string SettingsLocation = "Project Settings > MAGIC";

        const string LastReported = "Magic.Unity.EditorRuntime.reported";

        const ApiCompatibilityLevel DotNetFramework = ApiCompatibilityLevel.NET_Unity_4_8;

        const int ClojureCLRIndex = 0;

        const int MagicIndex = 1;

        static readonly string[] RuntimeLabels = { "ClojureCLR", "MAGIC" };

        // To prevent failure log spam, since guiHandler runs on every IMGUI pass.
        static bool _readFailureLogged;

        /// <summary>
        /// Whether the Editor is set to load MAGIC.
        /// </summary>
        public static bool IsMagicEnabled()
        {
            return IsMagic(ActiveTarget);
        }

        /// <summary>
        /// Triggers a recompile to load MAGIC in the Editor. Writes the
        /// active build target only.
        /// </summary>
        public static void UseMagic()
        {
            Apply(ActiveTarget, true);
        }

        /// <summary>
        /// Load ClojureCLR in the Editor. Writes the active build target.
        /// </summary>
        public static void UseClojureCLR()
        {
            Apply(ActiveTarget, false);
        }

        static NamedBuildTarget ActiveTarget =>
            NamedTargetFor(EditorUserBuildSettings.activeBuildTarget);

        // Dedicated Server shares Standalone's group but has its own settings
        // slot, and the wrong slot reads back a plausible default rather than
        // throwing.
        static NamedBuildTarget NamedTargetFor(BuildTarget platform)
        {
            var group = BuildPipeline.GetBuildTargetGroup(platform);
            return
                group == BuildTargetGroup.Standalone
                && EditorUserBuildSettings.standaloneBuildSubtarget
                    == StandaloneBuildSubtarget.Server
                ? NamedBuildTarget.Server
                : NamedBuildTarget.FromBuildTargetGroup(group);
        }

        static bool IsMagic(NamedBuildTarget target) => Symbols(target).Contains(Symbol);

        // A List, not a set: the define list is hand-curated and lives in a
        // tracked ProjectSettings.asset. Order in, order out.
        static List<string> Symbols(NamedBuildTarget target) =>
            (PlayerSettings.GetScriptingDefineSymbols(target) ?? "")
                .Split(';')
                .Select(s => s.Trim())
                .Where(s => s.Length > 0)
                .ToList();

        static void Apply(NamedBuildTarget target, bool useMagic)
        {
            var runtime = useMagic ? "MAGIC" : "ClojureCLR";
            var symbols = Symbols(target);
            if (symbols.Contains(Symbol) == useMagic)
            {
                Debug.Log($"{Tag} Editor runtime: {runtime} on {target.TargetName} already.");
                return;
            }
            if (useMagic)
            {
                symbols.Add(Symbol);
            }
            else
            {
                symbols.RemoveAll(s => s == Symbol);
            }
            // The string overload, not string[]: writes back exactly this list.
            PlayerSettings.SetScriptingDefineSymbols(target, string.Join(";", symbols));
            Debug.Log(
                $"{Tag} Editor runtime: {runtime} on {target.TargetName}"
                    + $" ({Symbol} {(useMagic ? "set" : "cleared")})."
            );
        }

        [InitializeOnLoadMethod]
        static void ReportEditorRuntime()
        {
            try
            {
                var target = ActiveTarget;
                var magic = IsMagic(target);
                var state = StateKey(target, magic);
                // An empty value for `previous` means a fresh Unity start
                var previous = SessionState.GetString(LastReported, "");
                SessionState.SetString(LastReported, state);
                if (previous == state)
                {
                    return;
                }
                // Skip logging if ClojureCLR runtime (default) and a fresh start
                if (previous.Length == 0 && !magic)
                {
                    return;
                }
                Debug.Log(
                    $"{Tag} Editor runtime: {(magic ? "MAGIC" : "ClojureCLR")} on"
                        + $" {target.TargetName}, the active build target."
                        + (magic ? $" Switch to ClojureCLR from '{SettingsLocation}'." : "")
                );
            }
            catch (Exception)
            {
                // Quiet by design -- see the class remarks.
            }
        }

        static string StateKey(NamedBuildTarget target, bool magic) =>
            $"{(magic ? "magic" : "clojure-clr")}:{target.TargetName}";

        [InitializeOnLoadMethod]
        static void WarnIfApiLevelUnsupported()
        {
            try
            {
                var target = ActiveTarget;
                // The MAGIC state has no DLR, so the level does not constrain it.
                if (IsMagic(target))
                {
                    return;
                }
                var level = PlayerSettings.GetApiCompatibilityLevel(target);
                if (level == DotNetFramework)
                {
                    return;
                }
                Debug.LogWarning(
                    $"{Tag} ClojureCLR needs API Compatibility Level"
                        + $" '{LevelLabel(DotNetFramework)}'; it is '{LevelLabel(level)}'. Fix it"
                        + $" from '{SettingsLocation}'."
                );
            }
            catch (Exception)
            {
                // Quiet by design -- see the class remarks.
            }
        }

        [SettingsProvider]
        static SettingsProvider CreateSettingsProvider()
        {
            return new SettingsProvider(SettingsPath, SettingsScope.Project)
            {
                label = "MAGIC",
                guiHandler = _ => DrawSettings(),
                keywords = new HashSet<string>
                {
                    "MAGIC",
                    "Clojure",
                    "ClojureCLR",
                    "runtime",
                    "editor",
                    Symbol,
                },
            };
        }

        static void DrawSettings()
        {
            EditorGUILayout.Space();
            EditorGUILayout.LabelField("Editor Runtime", EditorStyles.boldLabel);
            EditorGUILayout.LabelField(
                "ClojureCLR hot-reloads, so develop in it. Player builds always run MAGIC; "
                    + "switch to it to reproduce one.",
                EditorStyles.wordWrappedLabel
            );
            EditorGUILayout.Space();

            // Read before drawing: a throw partway down would leave this pass
            // issuing a different number of controls than the layout pass did.
            NamedBuildTarget target;
            bool magic;
            try
            {
                target = ActiveTarget;
                magic = IsMagic(target);
            }
            catch (Exception e)
            {
                if (!_readFailureLogged)
                {
                    _readFailureLogged = true;
                    Debug.LogException(e);
                }
                EditorGUILayout.HelpBox(
                    "Could not read the runtime selection of the active build target "
                        + $"({e.GetType().Name}), so it cannot be changed here. Set or clear "
                        + $"{Symbol} by hand in 'Project Settings > Player'.",
                    MessageType.Warning
                );
                return;
            }
            // A write mid-compile queues a second recompile from a domain the
            // running one is about to replace.
            using (new EditorGUI.DisabledScope(EditorApplication.isCompiling))
            {
                var chooseMagic =
                    EditorGUILayout.Popup(
                        new GUIContent("Runtime", $"Sets {Symbol} on the active build target."),
                        magic ? MagicIndex : ClojureCLRIndex,
                        RuntimeLabels
                    ) == MagicIndex;
                if (chooseMagic != magic)
                {
                    try
                    {
                        Apply(target, chooseMagic);
                    }
                    catch (Exception e)
                    {
                        Debug.LogWarning(
                            $"{Tag} Could not switch the Editor runtime on {target.TargetName};"
                                + $" it is unchanged. Player builds are unaffected.\n{e}"
                        );
                    }
                    // Outside the catch above, which would swallow its
                    // ExitGUIException. Ends the pass so the next one reads the
                    // state this one just wrote.
                    GUIUtility.ExitGUI();
                }
            }
            EditorGUILayout.LabelField(
                $"Applies to the active build target ({target.TargetName}) only. Each target "
                    + "keeps its own setting.",
                EditorStyles.wordWrappedMiniLabel
            );
            if (!magic)
            {
                DrawClojureCLRApiLevel(target);
            }
        }

        static void DrawClojureCLRApiLevel(NamedBuildTarget target)
        {
            ApiCompatibilityLevel level;
            try
            {
                level = PlayerSettings.GetApiCompatibilityLevel(target);
            }
            catch (Exception e)
            {
                EditorGUILayout.Space();
                EditorGUILayout.HelpBox(
                    $"API Compatibility Level could not be read ({e.GetType().Name}). ClojureCLR "
                        + $"needs '{LevelLabel(DotNetFramework)}'.",
                    MessageType.Warning
                );
                return;
            }
            if (level == DotNetFramework)
            {
                return;
            }
            EditorGUILayout.Space();
            EditorGUILayout.HelpBox(
                $"ClojureCLR needs API Compatibility Level '{LevelLabel(DotNetFramework)}'; it is "
                    + $"'{LevelLabel(level)}'. ClojureCLR initialises through the DLR, which "
                    + "references System.Configuration, System.Runtime.Remoting and System.Xaml -- "
                    + "assemblies the .NET Standard profile does not have.",
                MessageType.Warning
            );
            if (GUILayout.Button($"Set to {LevelLabel(DotNetFramework)}"))
            {
                SetApiLevelToDotNetFramework(target);
            }
        }

        static string LevelLabel(ApiCompatibilityLevel level) =>
            level switch
            {
                DotNetFramework => ".NET Framework",
                ApiCompatibilityLevel.NET_Standard => ".NET Standard 2.1",
                _ => level.ToString(),
            };

        static void SetApiLevelToDotNetFramework(NamedBuildTarget target)
        {
            try
            {
                PlayerSettings.SetApiCompatibilityLevel(target, DotNetFramework);
                // only preserved in the log file, cleared in the Editor console
                Debug.Log(
                    $"{Tag} API Compatibility Level set to '{LevelLabel(DotNetFramework)}' on"
                        + $" {target.TargetName}."
                );
            }
            catch (Exception e)
            {
                Debug.LogWarning(
                    $"{Tag} Could not set the API Compatibility Level on {target.TargetName}"
                        + $" ({e.GetType().Name}). Set it by hand in 'Project Settings > Player'."
                );
            }
        }
    }
}
