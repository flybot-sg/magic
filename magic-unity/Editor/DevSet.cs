#if MAGIC_RUNTIME_IN_EDITOR
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;
using Debug = UnityEngine.Debug;

namespace Magic.Unity
{
    /// <summary>
    /// Runs <c>nos dev</c>, which writes the Editor's dev set and roots file,
    /// then requests a script reload, since a domain reads both only when it boots.
    /// </summary>
    /// <remarks>
    /// Unity's bundled Mono runs <c>NostrandMain.exe</c>, so neither <c>nos</c> nor
    /// <c>mono</c> needs to be on the PATH of an Editor started from the Dock.
    /// </remarks>
    public static class DevSet
    {
        public const string MenuPath = "MAGIC/Build Dev Set";

        /// <summary>EditorPrefs key of the NostrandMain.exe override, per machine.</summary>
        public const string NostrandMainKey = "Magic.Unity.DevSet.NostrandMain";

        const string Tag = "[Magic.Unity/DevSet]";

        static Process _running;
        static readonly StringBuilder _output = new StringBuilder();

        [MenuItem(MenuPath)]
        public static void Build()
        {
            if (_running != null)
            {
                Debug.LogWarning($"{Tag} nos dev is already running.");
                return;
            }
            try
            {
                _running = Start();
            }
            catch (Exception e)
            {
                Debug.LogError($"{Tag} Could not start nos dev: {e.Message}");
                return;
            }
            Debug.Log($"{Tag} Running nos dev with {_running.StartInfo.Arguments}");
            EditorApplication.update += Poll;
        }

        static Process Start()
        {
            var mono = Path.Combine(EditorApplication.applicationContentsPath, "MonoBleedingEdge/bin/mono");
            var exe = NostrandMain();
            if (!File.Exists(exe))
                throw new FileNotFoundException(
                    $"no NostrandMain.exe at {exe}. Install nos, or set its path in 'Project Settings > MAGIC'.");
            var info = new ProcessStartInfo(mono, $"\"{exe}\" dev")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                WorkingDirectory = Path.GetDirectoryName(Application.dataPath),
            };
            lock (_output)
                _output.Clear();
            var proc = new Process { StartInfo = info };
            proc.OutputDataReceived += Collect;
            proc.ErrorDataReceived += Collect;
            proc.Start();
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            return proc;
        }

        static void Collect(object sender, DataReceivedEventArgs e)
        {
            if (e.Data == null)
                return;
            lock (_output)
                _output.AppendLine(e.Data);
        }

        static void Poll()
        {
            if (!_running.HasExited)
                return;
            EditorApplication.update -= Poll;
            _running.WaitForExit(); // flushes the async readers
            var exit = _running.ExitCode;
            _running.Dispose();
            _running = null;
            string output;
            lock (_output)
                output = _output.ToString();
            if (exit != 0)
            {
                Debug.LogError($"{Tag} nos dev exited {exit}; the dev set is unchanged or partial.\n{output}");
                return;
            }
            Debug.Log($"{Tag} nos dev done; reloading scripts.\n{output}");
            // Imports a dependency's C# DLL that nos dev copied into Assets/Plugins/CSharp.
            AssetDatabase.Refresh();
            EditorUtility.RequestScriptReload();
        }

        /// <summary>
        /// The configured NostrandMain.exe, or the one beside the target of
        /// <c>~/.local/bin/nos</c>, the link the installer writes.
        /// </summary>
        public static string NostrandMain()
        {
            var configured = EditorPrefs.GetString(NostrandMainKey, "");
            return configured.Length > 0 ? configured : DefaultNostrandMain();
        }

        static string DefaultNostrandMain()
        {
            var link = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local/bin/nos");
            var script = ReadLink(link) ?? link;
            return Path.Combine(Path.GetDirectoryName(Path.Combine(Path.GetDirectoryName(link), script)), "NostrandMain.exe");
        }

        // One level, as the nos script resolves itself. Mono has no LinkTarget.
        static string ReadLink(string path)
        {
            if (!File.Exists(path))
                return null;
            var info = new ProcessStartInfo("/usr/bin/readlink", $"\"{path}\"")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
            };
            using (var proc = Process.Start(info))
            {
                var target = proc.StandardOutput.ReadToEnd().Trim();
                proc.WaitForExit();
                return proc.ExitCode == 0 && target.Length > 0 ? target : null;
            }
        }

        internal static void DrawSettings()
        {
            EditorGUILayout.Space();
            EditorGUILayout.LabelField("Dev Set", EditorStyles.boldLabel);
            var configured = EditorPrefs.GetString(NostrandMainKey, "");
            var entered = EditorGUILayout.DelayedTextField(
                new GUIContent("NostrandMain.exe", $"Run by '{MenuPath}'. Empty for {DefaultNostrandMain()}."),
                configured);
            if (entered != configured)
                EditorPrefs.SetString(NostrandMainKey, entered.Trim());
            EditorGUILayout.LabelField(
                $"Using {NostrandMain()}. Stored for this machine, not the project.",
                EditorStyles.wordWrappedMiniLabel);
        }
    }
}
#endif
