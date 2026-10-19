using System;
using System.Reflection;
using clojure.lang;

namespace Nostrand
{

    public class Nostrand
    {
        // [DllImport("__Internal", EntryPoint = "mono_get_runtime_build_info")]
        // public extern static string GetRuntimeVersion();

        static string GetVersionString(Assembly asm)
        {
            return ((AssemblyInformationalVersionAttribute)(asm.GetCustomAttributes(typeof(AssemblyInformationalVersionAttribute), false)[0])).InformationalVersion;
        }

        /// <summary>
        /// The assembly Version() reports. Nostrand.dll carries no SourceLink
        /// commit SHA, so the CLI points this at its own exe, which does.
        /// </summary>
        public static Assembly VersionSource = typeof(Nostrand).Assembly;

        public static string Version()
        {
            var asm = VersionSource;
            if (Assembly.Load("System").GetName().Version.Major == 2)
                return asm.GetName().Version.ToString();
            return GetVersionString(asm);
        }

        public static string MagicRuntimeVersion()
        {
            return GetVersionString(typeof(Magic.Runtime).Assembly);
        }

        public static string ClojureRuntimeVersion()
        {
            return GetVersionString(typeof(clojure.lang.RT).Assembly);
        }
    }
}

