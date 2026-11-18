using System;
using System.Collections.Generic;
using clojure.lang;
using Debug = UnityEngine.Debug;

namespace Magic.Unity
{
    public static class Clojure
    {
        static Clojure()
        {
#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR // ClojureCLR Editor
            try
            {
                SourceRootInstaller.Install(); // before RT.Init, which loads user.clj
            }
            catch (Exception ex)
            {
                Debug.LogException(ex);
            }
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
            RT.Initialize();
#if UNITY_EDITOR // MAGIC Editor: Editor/Compiler ships magic.api
            RT.UseMagicCompiler();
            try
            {
                DepsRootInstaller.Install(); // before RT.Init, which loads user.clj
            }
            catch (Exception ex)
            {
                Debug.LogException(ex);
            }
            RT.Init();
#endif
#endif
        }

        /// <summary>
        /// Boots the runtime. The other members boot it on first use via the static
        /// constructor, so this is needed before using clojure.lang directly.
        /// </summary>
        public static void Boot()
        {
        }

        /// <summary>
        /// The absolute source roots the Editor loads namespaces from, e.g. to watch
        /// with ClojureReloader: from reload.edn in a ClojureCLR Editor, from
        /// the project's deps file in a MAGIC Editor. Empty in a player.
        /// </summary>
        public static IReadOnlyList<string> SourceRoots
        {
            get
            {
#if UNITY_EDITOR && !MAGIC_RUNTIME_IN_EDITOR
                return SourceRootInstaller.Installed;
#elif UNITY_EDITOR
                return DepsRootInstaller.Installed;
#else
                return Array.Empty<string>();
#endif
            }
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
