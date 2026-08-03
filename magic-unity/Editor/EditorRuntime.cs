using System;
using System.Collections.Generic;
using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Magic.Unity
{
    /// <summary>
    /// Selects which Clojure runtime the Unity Editor loads.
    /// </summary>
    /// <remarks>
    /// The package ships both runtimes and each shipped DLL carries a define
    /// constraint that reads the <c>MAGIC_RUNTIME_IN_EDITOR</c> scripting
    /// define symbol:
    /// <code>
    ///   Runtime/Infrastructure/Export/  (MAGIC)  '!UNITY_EDITOR || MAGIC_RUNTIME_IN_EDITOR'
    ///   Runtime/Infrastructure/Stock/   (stock)  UNITY_EDITOR  and  '!MAGIC_RUNTIME_IN_EDITOR'
    /// </code>
    /// Symbol unset (the default) gives the Editor stock ClojureCLR; set gives
    /// it MAGIC. Player builds always get MAGIC either way: <c>UNITY_EDITOR</c>
    /// is absent there, which satisfies the MAGIC constraint on its own.
    ///
    /// The symbol is per build-target group and the Editor compiles with the
    /// <em>active</em> group's symbols, so setting it by hand means editing
    /// every group the project might switch to. This writes them all at once.
    /// </remarks>
    public static class EditorRuntime
    {
        internal const string Symbol = "MAGIC_RUNTIME_IN_EDITOR";

        const string ToggleMenu = "MAGIC/Editor Runtime/Use MAGIC in the Editor";

        // Stock ClojureCLR is net462 and its compile and interop paths always
        // run through the DLR, which references System.Configuration,
        // System.Runtime.Remoting and System.Xaml -- none of them in the .NET
        // Standard profile. So the default (stock) Editor state needs this
        // level, and it is harmless in the MAGIC state, which has no DLR.
        const ApiCompatibilityLevel DotNetFramework = ApiCompatibilityLevel.NET_Unity_4_8;

        /// <summary>Whether the Editor is set to load the MAGIC runtime.</summary>
        public static bool MagicEnabled => SymbolsOf(ActiveGroup).Contains(Symbol);

        /// <summary>Load MAGIC in the Editor (sets the symbol in every build-target group).</summary>
        public static void UseMagic() { Apply(true); }

        /// <summary>Load stock ClojureCLR in the Editor (clears the symbol everywhere).</summary>
        public static void UseStock() { Apply(false); }

        [MenuItem(ToggleMenu)]
        static void ToggleMenuItem() { Apply(!MagicEnabled); }

        [MenuItem(ToggleMenu, true)]
        static bool ToggleMenuValidate()
        {
            Menu.SetChecked(ToggleMenu, MagicEnabled);
            return true;
        }

        static BuildTargetGroup ActiveGroup => EditorUserBuildSettings.selectedBuildTargetGroup;

        static bool ApiLevelSupportsStock
        {
            get
            {
                try { return PlayerSettings.GetApiCompatibilityLevel(ActiveGroup) == DotNetFramework; }
                catch (Exception) { return true; }
            }
        }

        static void Apply(bool useMagic)
        {
            var changed = new List<string>();
            foreach (var group in SettableGroups())
            {
                var symbols = SymbolsOf(group);
                if (useMagic ? symbols.Add(Symbol) : symbols.Remove(Symbol))
                {
                    try
                    {
                        PlayerSettings.SetScriptingDefineSymbolsForGroup(group, string.Join(";", symbols));
                        changed.Add(group.ToString());
                    }
                    catch (Exception) { }
                }
            }
            Debug.Log($"[Magic.Unity/EditorRuntime] Editor runtime: {(useMagic ? "MAGIC" : "stock ClojureCLR")}"
                      + $" ({Symbol} {(useMagic ? "set" : "cleared")} in {changed.Count} build-target group(s):"
                      + $" {string.Join(", ", changed)}). Player builds are unaffected.");
            if (!useMagic)
            {
                WarnIfApiLevelUnsupported();
            }
        }

        // Stock cannot initialise at all under .NET Standard: RT.DoInit walks
        // straight into the DLR. Warn rather than change a project setting the
        // consumer owns -- and warn from load, since the default install lands
        // in exactly this state.
        [InitializeOnLoadMethod]
        static void WarnIfApiLevelUnsupported()
        {
            if (MagicEnabled || ApiLevelSupportsStock)
            {
                return;
            }
            Debug.LogWarning(
                "[Magic.Unity/EditorRuntime] The Editor is set to load stock ClojureCLR, which needs "
                + "API Compatibility Level '.NET Framework' (its DLR dependencies reference assemblies "
                + "the .NET Standard profile does not have). Set it in "
                + $"'Project Settings > Player', or switch the Editor to MAGIC from '{ToggleMenu}'. "
                + "Player builds are unaffected.");
        }

        static SortedSet<string> SymbolsOf(BuildTargetGroup group)
        {
            string defines;
            try { defines = PlayerSettings.GetScriptingDefineSymbolsForGroup(group) ?? ""; }
            catch (Exception) { defines = ""; }
            return new SortedSet<string>(
                defines.Split(';').Select(s => s.Trim()).Where(s => s.Length > 0),
                StringComparer.Ordinal);
        }

        // Enum.GetValues yields Unknown and long-removed platforms; writing to
        // those throws. Filter on [Obsolete] the way the build-target scan
        // does, and swallow whatever still refuses at the call site.
        static IEnumerable<BuildTargetGroup> SettableGroups()
        {
            foreach (BuildTargetGroup group in Enum.GetValues(typeof(BuildTargetGroup)))
            {
                if (group == BuildTargetGroup.Unknown)
                {
                    continue;
                }
                var field = typeof(BuildTargetGroup).GetField(group.ToString());
                if (field == null || field.GetCustomAttributes(typeof(ObsoleteAttribute), false).Any())
                {
                    continue;
                }
                yield return group;
            }
        }
    }
}
