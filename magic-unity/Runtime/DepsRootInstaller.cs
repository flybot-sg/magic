#if UNITY_EDITOR && MAGIC_RUNTIME_IN_EDITOR
using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using clojure.lang;
using UnityEditor;
using Debug = UnityEngine.Debug;

namespace Magic.Unity
{
    static class DepsRootInstaller
    {
        public static IReadOnlyList<string> Installed { get; private set; } = Array.Empty<string>();

        const string MissingDevCacheKey = "Magic.Unity.MissingDevCache:";

        /// <summary>Installs the deps file's source paths and magic.edn's dev profile. Needs clojure.core loaded.</summary>
        public static void Install()
        {
            RT.var("clojure.core", "require").invoke(Symbol.intern("magic.deps.load-path"));
            // Unity's working directory is the project root.
            var root = Directory.GetCurrentDirectory();
            var result = (ILookup)RT.var("magic.deps.load-path", "install-project-roots!")
                .invoke(root);
            foreach (var missing in Strings(result.valAt(RT.keyword(null, "missing"))))
            {
                Debug.LogWarning($"Clojure source root not found, skipped: {missing}");
            }
            Installed = Strings(result.valAt(RT.keyword(null, "roots")));
            // Prepended last so its DLLs win over a stray .clj.dll in a source root.
            InstallDevProfile(root);
        }

        static void InstallDevProfile(string root)
        {
            try
            {
                var result = (ILookup)RT.var("magic.deps.load-path", "install-dev-profile!")
                    .invoke(root);
                var missing = (string)result.valAt(RT.keyword(null, "out"));
                // SessionState outlives a domain reload, so this logs once per Editor session.
                if (RT.keyword(null, "missing").Equals(result.valAt(RT.keyword(null, "status")))
                    && !SessionState.GetBool(MissingDevCacheKey + missing, false))
                {
                    SessionState.SetBool(MissingDevCacheKey + missing, true);
                    Debug.LogWarning($"Clojure dev cache not found, compiling from source: {missing}. Run nos build :dev.");
                }
            }
            catch (Exception ex)
            {
                Debug.LogWarning($"Clojure dev cache and flags not loaded from magic.edn, compiling from source: {ex.Message}");
            }
        }

        static List<string> Strings(object coll)
        {
            return ((IEnumerable)coll).Cast<string>().ToList();
        }
    }
}
#endif
