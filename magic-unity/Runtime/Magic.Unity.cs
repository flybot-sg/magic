using System;
using System.Reflection;
using clojure.lang;

namespace Magic.Unity
{
    /// <summary>
    /// MAGIC's Unity-specific Clojure integration
    /// </summary>
    public static class Clojure
    {
        static bool _booted = false;
        static Var RequireVar;

        /// <summary>
        /// Initialize the Clojure runtime.
        /// </summary>
        /// <remarks>
        /// This must be run before any Clojure functions can run. The
        /// Magic.Unity integration functions do this automatically.
        /// </remarks>
        public static void Boot()
        {
            if (!_booted)
            {
                _booted = true;
                BootMagicRuntime();
                RequireVar = RT.var("clojure.core", "require");
            }
        }

        // The bootstrap below uses API that only exists in MAGIC's Clojure
        // fork: RuntimeBootstrapFlag.CodeLoadOrder, RT.Initialize with the
        // doRuntimePostBoostrap parameter, and RT.TryLoadInitType. A consumer
        // may keep a stock ClojureCLR in Assets for in-editor runtime
        // compilation; Unity dedups managed plugins by file name, so this
        // file can end up compiled against stock Clojure.dll. Bind the
        // fork-only members via reflection: when the fork is present this
        // runs the exact same bootstrap as before, when it is absent (stock)
        // the bootstrap is skipped and stock self-initializes on first
        // RT.var.
        static void BootMagicRuntime()
        {
            var clojureAssembly = typeof(RT).Assembly;
            var bootstrapFlagType = clojureAssembly.GetType("clojure.lang.RuntimeBootstrapFlag");
            var codeLoadOrderField = bootstrapFlagType?.GetField("CodeLoadOrder", BindingFlags.Public | BindingFlags.Static);
            var codeSourceType = bootstrapFlagType?.GetNestedType("CodeSource");
            var initializeMethod = typeof(RT).GetMethod("Initialize", BindingFlags.Public | BindingFlags.Static, null, new[] { typeof(bool), typeof(bool) }, null);
            var tryLoadInitTypeMethod = typeof(RT).GetMethod("TryLoadInitType", BindingFlags.Public | BindingFlags.Static, null, new[] { typeof(string) }, null);
            if (codeLoadOrderField == null || codeSourceType == null || initializeMethod == null || tryLoadInitTypeMethod == null)
            {
                // Silent skip is correct for stock ClojureCLR (no RuntimeBootstrapFlag).
                // A non-null bootstrapFlagType means the fork is loaded but a member
                // moved, so warn rather than let the player die later with no pointer here.
                if (bootstrapFlagType != null)
                    UnityEngine.Debug.LogWarning(
                        "MAGIC runtime bootstrap skipped: the Magic.Unity scripts and the loaded "
                        + "Clojure.dll are from different MAGIC versions (missing fork member "
                        + "RuntimeBootstrapFlag.CodeLoadOrder, RT.Initialize(bool,bool), or "
                        + "RT.TryLoadInitType). Reimport the MAGIC Unity package so its scripts "
                        + "and Clojure.dll come from the same version.");
                return;
            }
#if UNITY_EDITOR
            SetCodeLoadOrder(codeLoadOrderField, codeSourceType, new[] { "InitType", "FileSystem" });
#elif ENABLE_IL2CPP
            SetCodeLoadOrder(codeLoadOrderField, codeSourceType, new[] { "InitType" });
#endif
            initializeMethod.Invoke(null, new object[] { true, false });
            tryLoadInitTypeMethod.Invoke(null, new object[] { "clojure/core" });
        }

        static void SetCodeLoadOrder(FieldInfo codeLoadOrderField, Type codeSourceType, string[] sourceNames)
        {
            var codeLoadOrder = Array.CreateInstance(codeSourceType, sourceNames.Length);
            for (var i = 0; i < sourceNames.Length; i++)
            {
                codeLoadOrder.SetValue(Enum.Parse(codeSourceType, sourceNames[i]), i);
            }
            codeLoadOrderField.SetValue(null, codeLoadOrder);
        }

        /// <summary>
        /// Lookup a Clojure var
        /// </summary>
        /// <param name="ns">The namespace of the var</param>
        /// <param name="name">The name of the var</param>
        /// <returns></returns>
        public static Var GetVar(string ns, string name)
        {
            Boot();
            return RT.var(ns, name);
        }

        /// <summary>
        /// Lookup a Clojure var and cast to a known type
        /// </summary>
        /// <remarks>
        /// Useful when the var is known to be a function with type hints.
        /// Casting to a Magic.Function type in that case avoids boxing.
        /// </remarks>
        /// <param name="ns">The namespace of the var</param>
        /// <param name="name">The name of the var</param>
        /// <returns></returns>
        public static T GetVar<T>(string ns, string name)
        {
            Boot();
            return (T)(RT.var(ns, name).deref());
        }

        /// <summary>
        /// Require a Clojure namespace
        /// </summary>
        /// <param name="ns">The name of the namespace</param>
        public static void Require(string ns)
        {
            Boot();
            RequireVar.invoke(Symbol.intern(ns));
        }
    }
}
