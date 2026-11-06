using System;
using System.Collections.Generic;
using System.IO;
using clojure.lang;

namespace Magic.Unity
{
    public static class Clojure
    {
        static Clojure()
        {
#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR // ClojureCLR Editor
            RT.Init();
#else
#if UNITY_EDITOR // MAGIC Editor
            RuntimeBootstrapFlag.CodeLoadOrder = new[] {
                RuntimeBootstrapFlag.CodeSource.InitType,
                RuntimeBootstrapFlag.CodeSource.FileSystem
            };
#elif ENABLE_IL2CPP // MAGIC IL2CPP Player
            RuntimeBootstrapFlag.CodeLoadOrder = new[] {
                RuntimeBootstrapFlag.CodeSource.InitType
            };
#endif
            RT.Initialize(doRuntimePostBoostrap: false);
#if UNITY_EDITOR // MAGIC Editor
            BootEditor();
#endif
#endif
        }

#if UNITY_EDITOR && MAGIC_RUNTIME_IN_EDITOR
        /// <summary>One absolute source root per line, written by nos with the dev set.</summary>
        public const string RootsFile = "Library/MagicDevRoots.txt";

        /// <summary>The test-flags DLLs nos builds for the Editor.</summary>
        public const string DevSetDirectory = "Library/MagicDev";

        // The Editor runs the compiler in memory over the dev set and the project's sources.
        // Unity has already loaded every Runtime/magic and Editor/Compiler DLL.
        static void BootEditor()
        {
            var rootsFile = Path.GetFullPath(RootsFile);
            if (!File.Exists(rootsFile))
                throw new InvalidOperationException(
                    "MAGIC Editor boot: no roots file at " + rootsFile + ", so none of the project's "
                    + "Clojure can load. Run 'MAGIC/Build Dev Set' (nos dev) to write it with " + DevSetDirectory + ".");

            RT.UseMagicCompiler();

            // Replace: with Runtime/magic off the path, a stdlib namespace resolves to
            // the init type Unity has loaded rather than a second LoadFrom of its file.
            var loadPaths = new List<object>();
            foreach (var line in File.ReadAllLines(rootsFile))
            {
                if (line.Trim().Length > 0)
                    loadPaths.Add(line.Trim());
            }
            loadPaths.Add(Path.GetFullPath(DevSetDirectory));
            RT.var("clojure.core", "*load-paths*").bindRoot(PersistentVector.create(loadPaths.ToArray()));

            // Last: core's helper files and magic.api's requires load from Runtime/magic.
            // FileSystem first, so (require 'x :reload) reads an edited source.
            RuntimeBootstrapFlag.CodeLoadOrder = new[] {
                RuntimeBootstrapFlag.CodeSource.FileSystem,
                RuntimeBootstrapFlag.CodeSource.InitType
            };
        }
#endif

        /// <summary>
        /// Boots the runtime. The other members boot it on first use via the static
        /// constructor, so this is needed before using clojure.lang directly.
        /// </summary>
        public static void Boot()
        {
        }

        public static Var Var(string ns, string name)
        {
            return RT.var(ns, name);
        }

        /// <summary>
        /// Returns the var's current value as T. A later redefinition (hot reload,
        /// REPL) does not reach the returned value. Casting a type-hinted fn to its
        /// Magic.Function type lets callers invoke it without boxing.
        /// </summary>
        public static T Deref<T>(string ns, string name)
        {
            return (T)(RT.var(ns, name).deref());
        }

        public static void Require(string ns)
        {
            RT.var("clojure.core", "require").invoke(Symbol.intern(null, ns));
        }
    }
}
