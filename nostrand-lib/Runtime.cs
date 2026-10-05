using System;
using System.Collections.Generic;
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
            // var loadFunction = RT.var("clojure.core", "*load-fn*");
            // loadFunction.invoke("nostrand/core");
            RT.var("clojure.core", "*load-fn*").invoke("nostrand/core");
            // loadFunction.invoke("nostrand/tasks");
            RT.var("clojure.core", "*load-fn*").invoke("nostrand/tasks");
        }

        public static void Require(string ns)
        {
            RT.var("clojure.core", "require").invoke(Symbol.intern(ns));
        }

        // Searched in order for an unqualified task name, before clojure.core.
        static readonly List<string> TaskNamespaces = new List<string> { "nostrand.tasks" };

        public static void RegisterTasks(string ns)
        {
            Require(ns);
            if (!TaskNamespaces.Contains(ns))
                TaskNamespaces.Add(ns);
        }

        public static object EstablishProject(string directory)
        {
            return RT.var("nostrand.core", "establish-project").invoke(directory);
        }

        public static Var FindFunction(string name)
        {
            try
            {
                if (name.Contains("/"))
                {
                    var taskName = name;
                    var taskParts = taskName.Split('/');
                    var taskNS = taskParts[0];
                    var taskVarName = taskParts[1];
                    RT.load(taskNS.Replace('.', '/'));
                    var v = Namespace.find(Symbol.intern(taskNS)).FindInternedVar(Symbol.intern(taskVarName));
                    return v;
                }
                else
                {
                    // namespace not given, check tasks
                    var sym = Symbol.intern(name);
                    foreach (var taskNS in TaskNamespaces)
                    {
                        var ns = Namespace.find(Symbol.intern(taskNS));
                        var taskVar = ns?.FindInternedVar(sym);
                        if (taskVar != null)
                            return taskVar;
                    }

                    return Namespace.find(Symbol.intern("clojure.core")).FindInternedVar(sym);
                }
            }
            catch (NullReferenceException)
            {

            }

            return null;
        }

        public static bool Run(ISeq input)
        {
            var inputString = input.first().ToString();
            if (inputString.IndexOf("./", StringComparison.InvariantCulture) == 0)
                inputString = inputString.Substring(2);

            Var.pushThreadBindings(RT.mapUniqueKeys(RT.CurrentNSVar, Namespace.find(Symbol.intern("nostrand.core"))));
            try
            {
                var fn = FindFunction(inputString);
                if (fn != null)
                {
                    //referAll.invoke(fn.Namespace, nostrandCore);
                    fn.applyTo(input.next());
                    return true;
                }

                if (File.Exists(inputString))
                {
                    try
                    {
                        IFn mainFn = FindFunction(Nostrand.FileToRelativePath(inputString) + "/-main");
                        if (mainFn != null)
                        {
                            mainFn.applyTo(input.next());
                        }
                        return true;
                    }
                    catch (FileNotFoundException)
                    {
                    }
                }

                return false;
            }
            finally
            {
                Var.popThreadBindings();
            }
        }
    }
}
