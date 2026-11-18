#if UNITY_EDITOR && MAGIC_RUNTIME_IN_EDITOR
using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using clojure.lang;
using Debug = UnityEngine.Debug;

namespace Magic.Unity
{
    static class DepsRootInstaller
    {
        public static IReadOnlyList<string> Installed { get; private set; } = Array.Empty<string>();

        /// <summary>
        /// Puts the source paths of the project's deps file, resolved as nos does,
        /// ahead of <c>*load-paths*</c>. Needs clojure.core loaded.
        /// </summary>
        public static void Install()
        {
            RT.var("clojure.core", "require").invoke(Symbol.intern("magic.deps.load-path"));
            // Unity's working directory is the project root.
            var result = (ILookup)RT.var("magic.deps.load-path", "install-project-roots!")
                .invoke(Directory.GetCurrentDirectory());
            foreach (var missing in Strings(result.valAt(RT.keyword(null, "missing"))))
            {
                Debug.LogWarning($"Clojure source root not found, skipped: {missing}");
            }
            Installed = Strings(result.valAt(RT.keyword(null, "roots")));
        }

        static List<string> Strings(object coll)
        {
            return ((IEnumerable)coll).Cast<string>().ToList();
        }
    }
}
#endif
