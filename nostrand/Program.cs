using System;
using System.IO;
using System.Reflection;
using clojure.lang;

namespace Nostrand
{

    public class Program
    {
        public static void Main(string[] args)
        {
            new Mono.Terminal.LineEditor("#force-mono.terminal-assembly-load#");

            // Only the exe is stamped with SourceRevisionId, so `nos version`
            // reports git describe rather than the library's bare version.edn number.
            Nostrand.VersionSource = typeof(Program).Assembly;

            Runtime.Boot(Path.GetDirectoryName(Assembly.Load("Clojure").Location));
            Runtime.LoadNostrand();

            if (args.Length > 0)
            {
                AppDomain.CurrentDomain.AssemblyResolve += AssemblyResolver.Resolve;

                Runtime.EstablishProject(Directory.GetCurrentDirectory());

                RT.PostBootstrapInit();

                var input = Nostrand.ReadArguments(args);
                if (!Runtime.Run(input))
                    Terminal.Message("Quiting", "could not find function or file named `" + args[0] + "'", ConsoleColor.Yellow);
            }

            else
            {
                Terminal.Message("Nostrand", Nostrand.Version(), ConsoleColor.White);
                Terminal.Message("Clojure", RT.var("clojure.core", "clojure-version").invoke(), ConsoleColor.White);
            }
        }
    }
}
