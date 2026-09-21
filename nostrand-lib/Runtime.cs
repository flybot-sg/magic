using System;
using System.IO;
using System.Reflection;
using clojure.lang;

namespace Nostrand
{
    /// <summary>
    /// The nostrand engine: boot the runtime, load nostrand's own Clojure,
    /// establish a project, dispatch a task.
    /// </summary>
    /// <remarks>
    /// Host-neutral: nothing here decides cwd, exit, or which console a host
    /// writes to. The CLI wraps these with its own policy.
    /// </remarks>
    public static class Runtime
    {
        /// <summary>
        /// Load the shipped Clojure assemblies and initialize the runtime.
        /// </summary>
        /// <param name="runtimeDirectory">Directory holding the .clj.dll set.</param>
        public static void Boot(string runtimeDirectory)
        {
            foreach (var cljDll in Directory.EnumerateFiles(runtimeDirectory, "*.clj.dll"))
                Assembly.LoadFile(cljDll);

            RT.Initialize(doRuntimePostBoostrap: false);
            RT.TryLoadInitType("clojure/core");
            RT.TryLoadInitType("magic/api");

            // The fork's Compiler.eval throws NotSupportedException; these
            // slots are empty and MAGIC fills them.
            RT.var("clojure.core", "*load-fn*").bindRoot(RT.var("clojure.core", "-load"));
            RT.var("clojure.core", "*eval-form-fn*").bindRoot(RT.var("magic.api", "eval"));
            RT.var("clojure.core", "*load-file-fn*").bindRoot(RT.var("magic.api", "runtime-load-file"));
            RT.var("clojure.core", "*compile-file-fn*").bindRoot(RT.var("magic.api", "runtime-compile-file"));
            RT.var("clojure.core", "*macroexpand-1-fn*").bindRoot(RT.var("magic.api", "runtime-macroexpand-1"));
        }

        /// <summary>
        /// Load nostrand's own Clojure from the committed nostrand.*.clj.dll.
        /// </summary>
        public static void LoadNostrand()
        {
            var load = RT.var("clojure.core", "*load-fn*");
            load.invoke("nostrand/core");
            load.invoke("nostrand/tasks");
        }

        /// <summary>
        /// Put the project root on the load path and resolve a deps file if there is one.
        /// Returns the basis, or null when no deps file was found.
        /// </summary>
        public static object EstablishProject(string directory)
        {
            RT.var("nostrand.core", "load-path").invoke(directory);

            if (File.Exists("deps.edn") || File.Exists("deps-clr.edn"))
                return RT.var("nostrand.core", "establish-deps-edn").invoke();
            return null;
        }

        /// <summary>
        /// Dispatch one command: a function name, or a file whose -main to call.
        /// False when neither resolved; the caller owns the message.
        /// </summary>
        public static bool Run(ISeq input)
        {
            var inputString = input.first().ToString();
            if (inputString.IndexOf("./", StringComparison.InvariantCulture) == 0)
                inputString = inputString.Substring(2);

            Var.pushThreadBindings(RT.mapUniqueKeys(RT.CurrentNSVar, Namespace.find(Symbol.intern("nostrand.core"))));
            try
            {
                var fn = Nostrand.FindFunction(inputString);
                if (fn != null)
                {
                    fn.applyTo(input.next());
                    return true;
                }

                if (File.Exists(inputString))
                {
                    try
                    {
                        IFn mainFn = Nostrand.FindFunction(Nostrand.FileToRelativePath(inputString) + "/-main");
                        if (mainFn != null)
                            mainFn.applyTo(input.next());
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
