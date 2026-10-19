using System;
using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEngine;

namespace Magic.Unity
{
    /// <summary>
    /// A <see cref="NostrandHost"/> wired to this package's directories, with
    /// the Editor policy the host itself cannot express wrapped around every
    /// call.
    /// </summary>
    /// <remarks>
    /// Nothing in the package constructs one and there is no
    /// [InitializeOnLoad]: the consumer drives it behind
    /// <c>#if UNITY_EDITOR &amp;&amp; MAGIC_RUNTIME_IN_EDITOR</c>, the way
    /// CljSystem.Reload.cs drives ClojureReloader behind the inverse guard.
    /// Hold it in a static field — booting is once per domain, and the host expects one
    /// instance per domain.
    /// </remarks>
    public sealed class NostrandEditor
    {
        readonly NostrandHost _host;

        /// <param name="sourceRoots">
        /// Load path roots holding the project's Clojure, relative to the
        /// project root or absolute. The project root itself is always a root,
        /// so a task file beside Assets/ needs no entry here.
        /// </param>
        /// <param name="logger">Defaults to UnityEngine.Debug.Log.</param>
        public NostrandEditor(IEnumerable<string> sourceRoots = null, Action<string> logger = null)
        {
            // Application.dataPath rather than the working directory: the cwd is
            // what the host asserts against, so deriving it from the cwd would
            // make the assertion agree with whatever moved it.
            var projectDirectory = Path.GetDirectoryName(Application.dataPath);

            var roots = new List<string>();
            if (sourceRoots != null)
                foreach (var root in sourceRoots)
                    roots.Add(Path.IsPathRooted(root) ? root : Path.Combine(projectDirectory, root));

            _host = new NostrandHost(
                new[] { PackageDirectory("Runtime", "magic"), PackageDirectory("Editor", "Compiler") },
                projectDirectory,
                roots,
                logger ?? (message => Debug.Log(message)));
        }

        /// <summary>The wrapped host, for a caller that wants no Editor policy.</summary>
        public NostrandHost Host => _host;

        /// <summary>
        /// Boot the runtime and load nostrand once per domain, so the first real call
        /// does not. Takes no Editor lock: it writes nothing.
        /// </summary>
        public void Prewarm()
        {
            _host.Prewarm();
        }

        /// <summary>
        /// <see cref="NostrandHost.Run"/> with the Editor held still around it.
        /// </summary>
        public bool Run(string[] command, bool resolveAssemblies = false)
        {
            return Guarded(() => _host.Run(command, resolveAssemblies));
        }

        /// <summary>
        /// <see cref="NostrandHost.Eval"/> with the Editor held still around it.
        /// </summary>
        public object Eval(string source, bool resolveAssemblies = false)
        {
            return Guarded(() => _host.Eval(source, resolveAssemblies));
        }

        // A compile writes a directory of DLLs. Without the reload lock Unity
        // can start a domain reload partway through and tear the run down
        // mid-emit; without the asset-editing batch it imports each DLL as it
        // lands. The lock is the dangerous half: leaked, it wedges the Editor
        // until it is quit, so it is released even if the body throws. Refresh
        // comes after the unlock, since that is what picks the new DLLs up.
        static T Guarded<T>(Func<T> body)
        {
            var locked = false;
            var batched = false;
            try
            {
                EditorApplication.LockReloadAssemblies();
                locked = true;
                AssetDatabase.StartAssetEditing();
                batched = true;
                return body();
            }
            finally
            {
                if (batched)
                    AssetDatabase.StopAssetEditing();
                if (locked)
                    EditorApplication.UnlockReloadAssemblies();
                AssetDatabase.Refresh();
            }
        }

        static string PackageDirectory(string first, string second)
        {
            return Path.Combine(PackageRuntimePath.ResolvedPath, first, second);
        }
    }
}
