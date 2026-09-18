using System;
using System.Collections.Generic;
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
    /// writes to. Each host (the CLI, the Unity Editor) wraps these with its own
    /// policy. It lives in the library so anything outside Unity can exercise it.
    /// </remarks>
    public static class Runtime
    {
        /// <summary>
        /// Whether a Clojure runtime is already live in this AppDomain.
        /// </summary>
        /// <remarks>
        /// clojure.core/*load-paths* is defined by clojure/core.clj, not by
        /// RT's static initializer, so it is absent until core has loaded.
        /// Namespace.find and FindInternedVar are used rather than RT.var,
        /// which would intern the var and make the answer always yes.
        /// </remarks>
        public static bool IsBooted
        {
            get
            {
                var core = Namespace.find(Symbol.intern("clojure.core"));
                return core != null && core.FindInternedVar(Symbol.intern("*load-paths*")) != null;
            }
        }

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

            // RT.Initialize is not idempotent: it adds another AssemblyResolve
            // handler on every call (RT.cs:607). A consumer may already have
            // called Magic.Unity.Clojure.Boot, which runs it without the
            // bindRoots below, so a warm domain still needs the rest.
            if (!IsBooted)
            {
                RT.Initialize(doRuntimeBootstrap: true, doRuntimePostBoostrap: false);
                RT.TryLoadInitType("clojure/core");
            }

            // Likewise not idempotent: it re-invokes the namespace's init type,
            // re-running every top-level form.
            if (Namespace.find(Symbol.intern("magic.api")) == null)
                RT.TryLoadInitType("magic/api");

            // The fork's Compiler.eval throws NotSupportedException; these
            // slots are empty and MAGIC fills them. Safe to repeat.
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
            var load = RT.var("clojure.core", "*load-fn*");
            load.invoke("nostrand/core");
            load.invoke("nostrand/tasks");
        }

        /// <summary>
        /// Put source roots on the load path and resolve a deps file if there is one.
        /// Returns the basis, or null when no deps file was found.
        /// </summary>
        /// <param name="directories">The project root, plus any extra source roots.</param>
        /// <param name="replaceLoadPath">
        /// nostrand.core/set-load-path, which resets the -load-path atom, rather
        /// than load-path, which appends. A warm domain compiling a second
        /// project has to replace; the CLI appends, as it always has.
        /// </param>
        public static object EstablishProject(IEnumerable<string> directories, bool replaceLoadPath)
        {
            IPersistentVector roots = PersistentVector.EMPTY;
            foreach (var directory in directories)
                roots = roots.cons(directory);

            if (replaceLoadPath)
                RT.var("nostrand.core", "set-load-path").invoke(roots);
            else
                RT.var("nostrand.core", "load-path").applyTo(RT.seq(roots));

            // nostrand.deps.basis/project-deps-file resolves against the process
            // cwd, so probe there rather than under a root and report a file
            // the resolution would not read.
            if (File.Exists("deps.edn") || File.Exists("deps-clr.edn"))
                return RT.var("nostrand.core", "establish-deps-edn").invoke();
            return null;
        }

        /// <summary>
        /// Bind the vars a task runs under, popped by disposing the result.
        /// </summary>
        /// <remarks>
        /// The one definition of that set: a host that needs more binds it here
        /// through <paramref name="extra"/> rather than pushing a frame of its
        /// own, so the CLI and the Editor host cannot drift apart.
        ///
        /// A leaked frame corrupts *ns* and *warn-on-reflection* for the life of
        /// the AppDomain, which on Unity's main thread means until the Editor quits.
        /// </remarks>
        /// <param name="extra">
        /// Bindings to add, e.g. the Editor host's per-call *loaded-libs* and
        /// *out*. Null for the CLI, which needs only the three below.
        /// </param>
        public static IDisposable PushTaskBindings(Namespace ns, IPersistentMap extra = null)
        {
            return new TaskBindings(ns, extra);
        }

        sealed class TaskBindings : IDisposable
        {
            bool popped;

            public TaskBindings(Namespace ns, IPersistentMap extra)
            {
                IPersistentMap bindings = RT.mapUniqueKeys(
                    RT.CurrentNSVar, ns,
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

        /// <summary>
        /// Dispatch one command: a function name, or a file whose -main to call.
        /// False when neither resolved; the caller owns the message.
        /// </summary>
        public static bool Run(ISeq input)
        {
            var inputString = input.first().ToString();
            if (inputString.IndexOf("./", StringComparison.InvariantCulture) == 0)
                inputString = inputString.Substring(2);

            using (PushTaskBindings(Namespace.find(Symbol.intern("nostrand.core"))))
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
        }
    }
}
