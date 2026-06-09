using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;

// One-time Editor bootstrap so opening the project = ready to press Play.
// On every domain reload it idempotently ensures:
//   1. Assets/Smoke.unity exists with a SmokeRunner GameObject + script attached.
//   2. That scene is the only entry in Build Settings.
//   3. Standalone scripting backend = IL2CPP (the whole point of this project).
//
// Also exposes "MAGIC -> Smoke -> Build & Run IL2CPP" as a one-click build.
[InitializeOnLoad]
static class SmokeBootstrap
{
    const string ScenePath = "Assets/Smoke.unity";
    const string BuildDir  = "Build/SmokeMac";
    const string AppName   = "Smoke.app";

    static SmokeBootstrap()
    {
        EditorApplication.delayCall += EnsureSceneAndSettings;
    }

    static void EnsureSceneAndSettings()
    {
        var changed = false;

        if (!File.Exists(ScenePath))
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            var go = new GameObject("SmokeRunner");
            go.AddComponent<SmokeTestRunner>();
            EditorSceneManager.SaveScene(scene, ScenePath);
            Debug.Log($"[SmokeBootstrap] Created {ScenePath}.");
            changed = true;
        }

        var scenes = EditorBuildSettings.scenes;
        if (scenes.Length != 1 || scenes[0].path != ScenePath || !scenes[0].enabled)
        {
            EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };
            Debug.Log("[SmokeBootstrap] Registered Smoke.unity in Build Settings.");
            changed = true;
        }

        if (PlayerSettings.GetScriptingBackend(NamedBuildTarget.Standalone) != ScriptingImplementation.IL2CPP)
        {
            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Standalone, ScriptingImplementation.IL2CPP);
            Debug.Log("[SmokeBootstrap] Set Scripting Backend = IL2CPP (Standalone).");
            changed = true;
        }

        // Match production config: managed stripping disabled on Standalone.
        // Default (Low) makes UnityLinker walk MAGIC's emitted override chains and
        // currently stack-overflows in MarkStep.MarkBaseMethods.
        if (PlayerSettings.GetManagedStrippingLevel(NamedBuildTarget.Standalone) != ManagedStrippingLevel.Disabled)
        {
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.Standalone, ManagedStrippingLevel.Disabled);
            Debug.Log("[SmokeBootstrap] Set Managed Stripping = Disabled (Standalone).");
            changed = true;
        }

        // Production explicitly sets Release on every supported target.
        if (PlayerSettings.GetIl2CppCompilerConfiguration(NamedBuildTarget.Standalone) != Il2CppCompilerConfiguration.Release)
        {
            PlayerSettings.SetIl2CppCompilerConfiguration(NamedBuildTarget.Standalone, Il2CppCompilerConfiguration.Release);
            Debug.Log("[SmokeBootstrap] Set IL2CPP Compiler Configuration = Release (Standalone).");
            changed = true;
        }

        if (changed) AssetDatabase.SaveAssets();
    }

    [MenuItem("MAGIC/Smoke/Build & Run IL2CPP")]
    static void BuildAndRunIL2CPP()
    {
        // If Editor scripts in magic-unity were edited since the last compile,
        // BuildPipeline.BuildPlayer would silently build against the stale
        // Magic.Unity.dll and our preprocessor changes wouldn't run.
        AssetDatabase.Refresh(ImportAssetOptions.ForceSynchronousImport);
        if (EditorApplication.isCompiling || EditorApplication.isUpdating)
        {
            Debug.LogError("[SmokeBootstrap] Editor is recompiling scripts. Wait for the bottom-right spinner to finish, then re-run Build & Run IL2CPP.");
            return;
        }

        Directory.CreateDirectory(BuildDir);
        var outPath = Path.Combine(BuildDir, AppName);
        var report = BuildPipeline.BuildPlayer(
            new[] { ScenePath },
            outPath,
            BuildTarget.StandaloneOSX,
            BuildOptions.AutoRunPlayer | BuildOptions.ShowBuiltPlayer);

        if (report.summary.result == BuildResult.Succeeded)
            Debug.Log($"[SmokeBootstrap] Build succeeded: {outPath}");
        else
            Debug.LogError($"[SmokeBootstrap] Build failed: {report.summary.result}");
    }
}
