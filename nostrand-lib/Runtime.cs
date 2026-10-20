using System;
using System.IO;
using System.Reflection;
using clojure.lang;

namespace Nostrand
{
    public static class Runtime
    {
        public static void Boot(string runtimeDirectory)
        {
            foreach (var cljDll in Directory.EnumerateFiles(runtimeDirectory, "*.clj.dll"))
                Assembly.LoadFile(cljDll);

            RT.Initialize(doRuntimePostBoostrap: false);
            RT.TryLoadInitType("clojure/core");
            RT.TryLoadInitType("magic/api");

            RT.var("clojure.core", "*load-fn*").bindRoot(RT.var("clojure.core", "-load"));
            RT.var("clojure.core", "*eval-form-fn*").bindRoot(RT.var("magic.api", "eval"));
            RT.var("clojure.core", "*load-file-fn*").bindRoot(RT.var("magic.api", "runtime-load-file"));
            RT.var("clojure.core", "*compile-file-fn*").bindRoot(RT.var("magic.api", "runtime-compile-file"));
            RT.var("clojure.core", "*macroexpand-1-fn*").bindRoot(RT.var("magic.api", "runtime-macroexpand-1"));
        }

        public static void LoadNostrand()
        {
            Require("nostrand.core");
            Require("nostrand.tasks");
            Require("nostrand.exec");
        }

        public static void Require(string ns)
        {
            if (string.IsNullOrEmpty(ns))
                throw new ArgumentException("must be a namespace name", nameof(ns));

            RT.var("clojure.core", "require").invoke(Symbol.intern(ns));
        }

        public static void RegisterTasks(string ns)
        {
            Require(ns);
            RT.var("nostrand.exec", "register-tasks").invoke(Symbol.intern(ns));
        }

        public static object EstablishProject(string directory)
        {
            return RT.var("nostrand.core", "establish-project").invoke(directory);
        }

        // Runs `nos task args`, returning the task's value or throwing its error.
        public static object Run(string[] command)
        {
            if (command == null || command.Length == 0)
                throw new ArgumentException("must name a task", nameof(command));

            return RT.var("nostrand.exec", "run").invoke(RT.seq(command));
        }
    }
}
