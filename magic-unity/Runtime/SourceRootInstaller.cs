#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using clojure.lang;
using Debug = UnityEngine.Debug;

namespace Magic.Unity
{
    static class SourceRootInstaller
    {
        const string EdnName = "reload.edn";

        const string LoadPathVar = "CLOJURE_LOAD_PATH";

        // The roots Install appended, kept in an environment variable because
        // CLOJURE_LOAD_PATH survives a domain reload where static fields do not.
        const string AppendedVar = "MAGIC_SOURCE_ROOTS_APPENDED";

        public static IReadOnlyList<string> Installed { get; private set; } = Array.Empty<string>();

        static List<string> Split(string variable)
        {
            return (Environment.GetEnvironmentVariable(variable) ?? string.Empty)
                .Split(Path.PathSeparator)
                .Where(p => !string.IsNullOrWhiteSpace(p)).ToList();
        }

        static void Set(string variable, IEnumerable<string> paths)
        {
            Environment.SetEnvironmentVariable(variable,
                string.Join(Path.PathSeparator.ToString(), paths));
        }

        /// <summary>
        /// Idempotent across domain reloads. Without a readable <see cref="EdnName"/>,
        /// removes the roots an earlier install appended.
        /// </summary>
        public static void Install()
        {
            List<string> roots;
            try
            {
                roots = ExistingRoots();
            }
            catch
            {
                Apply(new List<string>());
                throw;
            }
            Apply(roots);
        }

        static List<string> ExistingRoots()
        {
            var roots = new List<string>();
            foreach (var candidate in ReadRoots())
            {
                var root = Path.GetFullPath(candidate)
                    .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                if (!Directory.Exists(root))
                {
                    Debug.LogWarning($"Clojure source root not found, skipped: {root}");
                    continue;
                }
                if (roots.Contains(root))
                {
                    continue;
                }
                roots.Add(root);
            }
            return roots;
        }

        static void Apply(List<string> roots)
        {
            // Launchers (cljr, nostrand) pass the whole classpath in this variable,
            // so entries we did not append stay as they are, ahead of ours.
            var appended = Split(AppendedVar);
            var external = Split(LoadPathVar).Where(p => !appended.Contains(p)).ToList();
            var ours = roots.Where(r => !external.Contains(r)).ToList();
            Installed = roots;
            Set(AppendedVar, ours);
            Set(LoadPathVar, external.Concat(ours));
        }

        // At the project root (Unity's working directory) or one level below it.
        static string FindEdn()
        {
            if (File.Exists(EdnName))
            {
                return Path.GetFullPath(EdnName);
            }
            var found = Directory.EnumerateDirectories(".")
                .Select(d => Path.Combine(d, EdnName))
                .Where(File.Exists)
                .Select(Path.GetFullPath)
                .ToList();
            switch (found.Count)
            {
                case 0: return null;
                case 1: return found[0];
                default:
                    throw new InvalidOperationException(
                        $"Found more than one {EdnName}: {string.Join(", ", found)}");
            }
        }

        static List<string> ReadRoots()
        {
            var roots = new List<string>();
            var ednPath = FindEdn();
            if (ednPath == null)
            {
                return roots;
            }
            var cfg = EdnReader.readString(File.ReadAllText(ednPath), PersistentHashMap.EMPTY) as IPersistentMap;
            if (!(cfg?.valAt(RT.keyword(null, "source-roots")) is IEnumerable<object> sourceRoots))
            {
                throw Malformed(ednPath);
            }
            var baseDir = Path.GetDirectoryName(ednPath);
            foreach (var root in sourceRoots)
            {
                if (!(root is string path))
                {
                    throw Malformed(ednPath);
                }
                roots.Add(Path.Combine(baseDir, path));
            }
            return roots;
        }

        static FormatException Malformed(string ednPath)
        {
            return new FormatException(
                $"{ednPath} must be a map of :source-roots to a vector of strings.");
        }
    }
}
#endif
