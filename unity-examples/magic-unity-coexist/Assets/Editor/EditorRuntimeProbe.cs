using Magic.Unity;
using UnityEditor;
using UnityEngine;

// Regression probe for the Project Settings > MAGIC runtime toggle
// (Magic.Unity.EditorRuntime), which the state runs bypass by writing the
// symbol straight into ProjectSettings.asset.
//
// Setting the symbol must append to the project's define list and clearing it
// must restore the list exactly; the seed is deliberately in an order no sort
// would produce.
//
// Run with -executeMethod EditorRuntimeProbe.Run; magic.coexist asserts the
// marker line and that no build-target group's defines changed.
public static class EditorRuntimeProbe
{
    const string Seed = "ZEBRA_FEATURE;ALPHA_FEATURE;MIDDLE_FEATURE";

    public static void Run()
    {
        // From the ACTIVE build target, as EditorRuntime derives it -- the
        // selected group can differ.
        var group = BuildPipeline.GetBuildTargetGroup(EditorUserBuildSettings.activeBuildTarget);
        var target = EditorRuntime.NamedTargetFor(EditorUserBuildSettings.activeBuildTarget);
        var original = PlayerSettings.GetScriptingDefineSymbols(target);
        try
        {
            PlayerSettings.SetScriptingDefineSymbols(target, Seed);

            EditorRuntime.UseMagic();
            var afterSet = PlayerSettings.GetScriptingDefineSymbols(target);
            var enabledAfterSet = EditorRuntime.IsMagicEnabled();

            EditorRuntime.UseClojureCLR();
            var afterClear = PlayerSettings.GetScriptingDefineSymbols(target);
            var enabledAfterClear = EditorRuntime.IsMagicEnabled();

            // Reported, not asserted: the driver derives expectations from seed=.
            Debug.Log(
                $"[EditorRuntimeProbe] group={group} seed={Seed}"
                    + $" after-set={afterSet} after-clear={afterClear}"
                    + $" enabled-after-set={enabledAfterSet.ToString().ToLowerInvariant()}"
                    + $" enabled-after-clear={enabledAfterClear.ToString().ToLowerInvariant()}"
            );
        }
        finally
        {
            PlayerSettings.SetScriptingDefineSymbols(target, original);
        }
    }
}
