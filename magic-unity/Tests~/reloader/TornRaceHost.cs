// Runs a Clojure script on the stock ClojureCLR runtime staged next to this
// executable (RT.DoInit Assembly.LoadFile()s the DLLs sitting beside it). Probe 2
// used to run on JVM Clojure; this host runs the same file on the runtime the
// Unity Editor actually loads, so the measurement is of that runtime's var
// semantics rather than of a proxy. Exit code is non-zero only if loading throws.
using System;
using clojure.lang;

class TornRaceHost
{
    static int Main(string[] args)
    {
        if (args.Length != 1)
        {
            Console.Error.WriteLine("usage: TornRaceHost.exe <script.clj>");
            return 2;
        }
        RT.Init();
        // The root of *ns* is clojure.core; clojure.main binds it to user before
        // running a script, and so does `clojure -M script.clj`. Without this the
        // script's top-level defs land in clojure.core while its load-string bodies
        // (in-ns 'user) into user, and the reader compares two unbound vars -- which
        // are never equal, so every sample reads as torn and the probe proves nothing.
        var ns = RT.var("clojure.core", "*ns*");
        Var.pushThreadBindings(RT.map(ns, Namespace.findOrCreate(Symbol.intern("user"))));
        try
        {
            RT.var("clojure.core", "load-file").invoke(args[0]);
        }
        finally
        {
            Var.popThreadBindings();
        }
        return 0;
    }
}
