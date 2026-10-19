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

            // Only the exe carries the SourceLink commit SHA; Nostrand.dll is
            // unstamped so bb check-drift can byte-diff it.
            Nostrand.VersionSource = typeof(Program).Assembly;

            Runtime.Boot(Path.GetDirectoryName(Assembly.Load("Clojure").Location));
            Runtime.LoadNostrand();

            Runtime.RegisterTasks("nostrand.cli");

            if (args.Length > 0)
            {
                AppDomain.CurrentDomain.AssemblyResolve += AssemblyResolver.Resolve;

                Runtime.EstablishProject(Directory.GetCurrentDirectory());

                RT.PostBootstrapInit();

                Runtime.Require("nostrand.main");
                RT.var("nostrand.main", "main").applyTo(RT.seq(args));
            }

            else
            {
                Terminal.Message("Nostrand", Nostrand.Version(), ConsoleColor.White);
                Terminal.Message("Clojure", RT.var("clojure.core", "clojure-version").invoke(), ConsoleColor.White);
            }
        }
    }
}
