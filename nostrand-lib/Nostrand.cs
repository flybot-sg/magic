using System;
using System.IO;
using System.Reflection;
using clojure.lang;

namespace Nostrand
{

    public class Nostrand
    {
        public static ISeq ReadArguments(string[] args)
        {
            var forms = ReadForms(string.Join(" ", args));

            // A filesystem path is not a readable edn token. When the command line as
            // a whole does not read, retry argument by argument and hand the ones that
            // still do not read on verbatim as symbols.
            if (forms == null)
            {
                forms = PersistentVector.EMPTY;
                foreach (var arg in args)
                {
                    var argForms = ReadForms(arg);
                    if (argForms == null)
                        forms = (PersistentVector)forms.cons(Symbol.intern(arg));
                    else
                        foreach (var form in argForms)
                            forms = (PersistentVector)forms.cons(form);
                }
            }

            return forms.seq();
        }

        static readonly object EOFSentinel = new object();

        // Null when the input does not read as edn, so the caller can fall back.
        static PersistentVector ReadForms(string input)
        {
            var forms = PersistentVector.EMPTY;
            var pbtr = new PushbackTextReader(new StringReader(input));
            for (;;)
            {
                object form;
                try
                {
                    form = EdnReader.read(pbtr, false, EOFSentinel, false, null);
                }
                catch (Exception)
                {
                    return null;
                }
                if (form == EOFSentinel)
                    return forms;
                forms = (PersistentVector)forms.cons(form);
            }
        }

        // [DllImport("__Internal", EntryPoint = "mono_get_runtime_build_info")]
        // public extern static string GetRuntimeVersion();

        static string GetVersionString(Assembly asm)
        {
            return ((AssemblyInformationalVersionAttribute)(asm.GetCustomAttributes(typeof(AssemblyInformationalVersionAttribute), false)[0])).InformationalVersion;
        }

        public static string Version()
        {
            var asm = typeof(Nostrand).Assembly;
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

        static readonly string[] SourceExtensions = { ".cljr", ".cljc", ".clj" };

        public static string FileToRelativePath(string file)
        {
            foreach (var ext in SourceExtensions)
                if (file.EndsWith(ext, StringComparison.Ordinal))
                    return file.Substring(0, file.Length - ext.Length);
            return file;
        }

        /// <summary>
        /// The namespace a source file declares, by convention: separators become
        /// dots and the name is demunged, so sub/dir/cool_ns.clj is sub.dir.cool-ns.
        /// </summary>
        public static string FileToNamespace(string file)
        {
            return FileToRelativePath(file).Replace('/', '.').Replace('_', '-');
        }
    }
}

