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

            Runtime.Boot(Path.GetDirectoryName(Assembly.Load("Clojure").Location));
            Runtime.LoadNostrand();

            Runtime.RegisterTasks("nostrand.cli");

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
                // Terminal.Message("Mono", GetRuntimeVersion(), ConsoleColor.White);
                Terminal.Message("Clojure", RT.var("clojure.core", "clojure-version").invoke(), ConsoleColor.White);
            }
        }
    }
}
