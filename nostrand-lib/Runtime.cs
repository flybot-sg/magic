using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using clojure.lang;

namespace Nostrand
{
    public static class Runtime
    {
        /// <summary>
        /// Load the shipped Clojure assemblies and initialize the runtime.
        /// </summary>
        /// <param name="runtimeDirectories">
        /// Directories holding the .clj.dll set. Explicit rather than derived
        /// from Assembly.Load("Clojure").Location, which is exactly what a
        /// Unity package cannot do.
        /// </param>
        /// <param name="codeLoadOrder">
        /// RuntimeBootstrapFlag.CodeLoadOrder, or null to leave it alone. The
        /// CLI leaves it (FileSystem, InitType, EmbeddedResource); an Editor
        /// host wants (InitType, FileSystem), matching Magic.Unity.Clojure.
        /// </param>
        public static void Boot(IEnumerable<string> runtimeDirectories,
                                RuntimeBootstrapFlag.CodeSource[] codeLoadOrder = null)
        {
            foreach (var dir in runtimeDirectories)
                foreach (var cljDll in Directory.EnumerateFiles(dir, "*.clj.dll"))
                {
                    // Unity's UPM extractor writes an AppleDouble sidecar beside
                    // every file it unpacks on macOS -- 305 of them for this
                    // package, including a ._<name>.clj.dll for all 37 stdlib
                    // DLLs. They match the glob and are not assemblies, so
                    // LoadFile throws BadImageFormatException. It cannot be
                    // fixed at packing time: the tarball carries none and a
                    // plain `tar xzf` produces none, but Library/PackageCache
                    // is Unity's and is rebuilt on every resolve. Skipped by
                    // name rather than by catching the exception, so a DLL that
                    // is genuinely corrupt still fails loudly.
                    if (Path.GetFileName(cljDll).StartsWith("._", StringComparison.Ordinal))
                        continue;
                    Assembly.LoadFile(cljDll);
                }

            if (codeLoadOrder != null)
                RuntimeBootstrapFlag.CodeLoadOrder = codeLoadOrder;

            RT.Initialize(doRuntimePostBoostrap: false);
            RT.TryLoadInitType("clojure/core");
            RT.TryLoadInitType("magic/api");

            RT.var("clojure.core", "*load-fn*").bindRoot(RT.var("clojure.core", "-load"));
            RT.var("clojure.core", "*eval-form-fn*").bindRoot(RT.var("magic.api", "eval"));
            RT.var("clojure.core", "*load-file-fn*").bindRoot(RT.var("magic.api", "runtime-load-file"));
            RT.var("clojure.core", "*compile-file-fn*").bindRoot(RT.var("magic.api", "runtime-compile-file"));
            RT.var("clojure.core", "*macroexpand-1-fn*").bindRoot(RT.var("magic.api", "runtime-macroexpand-1"));
        }

        /// <summary>
        /// Load nostrand's own Clojure from the committed nostrand.*.clj.dll,
        /// which ship beside the compiler; the CLI's default load order finds
        /// them on the file system, a host booting with InitType first finds
        /// their init types among the assemblies Boot loaded.
        /// </summary>
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

        /// <summary>
        /// nostrand.core/establish-project: make root the project root, replacing
        /// any previous project's load and assembly paths, and resolve its deps
        /// file. Returns the basis, or null when root has no deps file.
        /// </summary>
        /// <param name="extraRoots">
        /// Source roots searched after root, e.g. an Editor host's consumer
        /// source directories. They do not join the assembly search.
        /// </param>
        public static object EstablishProject(string root, IEnumerable<string> extraRoots = null)
        {
            IPersistentVector extra = PersistentVector.EMPTY;
            if (extraRoots != null)
                foreach (var dir in extraRoots)
                    extra = extra.cons(dir);
            return RT.var("nostrand.core", "establish-project").invoke(root, extra);
        }

        /// <summary>
        /// Bind the vars a task runs under, popped by disposing the result.
        /// </summary>
        /// <remarks>
        /// For a host that evaluates more than <see cref="Run"/>, which binds its
        /// own frame through nostrand.exec/run. *ns* is user, as there, so a form
        /// the host evaluates lands where a task would.
        ///
        /// A leaked frame corrupts *ns* and *warn-on-reflection* for the life of
        /// the AppDomain, which on Unity's main thread means until the Editor quits.
        /// </remarks>
        /// <param name="extra">
        /// Bindings to add, e.g. the Editor host's per-call *loaded-libs* and
        /// *out*.
        /// </param>
        public static IDisposable PushTaskBindings(IPersistentMap extra = null)
        {
            return new TaskBindings(extra);
        }

        sealed class TaskBindings : IDisposable
        {
            bool popped;

            public TaskBindings(IPersistentMap extra)
            {
                IPersistentMap bindings = RT.mapUniqueKeys(
                    RT.CurrentNSVar, Namespace.findOrCreate(Symbol.intern("user")),
                    RT.WarnOnReflectionVar, RT.WarnOnReflectionVar.deref(),
                    RT.UncheckedMathVar, RT.UncheckedMathVar.deref());

                for (ISeq s = extra == null ? null : extra.seq(); s != null; s = s.next())
                {
                    var entry = (IMapEntry)s.first();
                    bindings = (IPersistentMap)bindings.assoc(entry.key(), entry.val());
                }

                Var.pushThreadBindings(bindings);
            }

            public void Dispose()
            {
                if (popped)
                    return;
                popped = true;
                Var.popThreadBindings();
            }
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
