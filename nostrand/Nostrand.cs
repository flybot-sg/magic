using System;
using System.Reflection;
using clojure.lang;

namespace Nostrand
{

    public class Nostrand
    {
        static string GetVersionString(Assembly asm)
        {
            return ((AssemblyInformationalVersionAttribute)(asm.GetCustomAttributes(typeof(AssemblyInformationalVersionAttribute), false)[0])).InformationalVersion;
        }

        public static string Version()
        {
            return GetVersionString(typeof(Nostrand).Assembly);
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

