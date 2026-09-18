using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using clojure.lang;

namespace Magic.Unity
{
    /// <summary>
    /// Runs nostrand tasks in the calling process instead of shelling out to
    /// <c>nos</c>, which boots the runtime on every invocation.
    /// </summary>
    /// <remarks>
    /// Unity-free by construction: every path is a constructor argument and
    /// reporting is an <see cref="Action{T}"/>, so a bare <c>csc</c> can build
    /// this and the probe pack can drive it. The Editor policy it cannot
    /// express — the reload lock, the asset-editing batch, the refresh — lives
    /// in Magic.Unity.Editor.Nostrand.Unity.
    ///
    /// One instance per AppDomain. The boot is domain state: a second
    /// instance finds the runtime and nostrand already loaded and reuses
    /// them. Its own roots still take effect, since every call replaces the
    /// load path with the instance's roots before running.
    /// </remarks>
    public sealed class NostrandHost
    {
        // Matches Magic.Unity.Clojure's editor bootstrap. CodeLoadOrder is a
        // process-global, so the two must agree or whichever boots second
        // silently changes how the first one's namespaces resolve.
        static readonly RuntimeBootstrapFlag.CodeSource[] EditorCodeLoadOrder =
        {
            RuntimeBootstrapFlag.CodeSource.InitType,
            RuntimeBootstrapFlag.CodeSource.FileSystem,
        };

        readonly string[] _runtimeDirectories;
        readonly string _projectDirectory;
        readonly string[] _loadPathRoots;
        readonly Action<string> _logger;

        bool _booted;

        /// <summary>
        /// Captures the paths. Nothing is loaded until <see cref="Prewarm"/> or
        /// the first call.
        /// </summary>
        /// <param name="runtimeDirectories">
        /// Directories holding the shipped .clj.dll set: for this package
        /// Runtime/magic (stdlib) and Editor/Compiler (the compiler and
        /// nostrand's own namespaces).
        /// </param>
        /// <param name="projectDirectory">
        /// The process working directory this host expects, asserted on every
        /// call. Unity's is the project root.
        /// </param>
        /// <param name="sourceRoots">
        /// Extra load path roots, after the project directory. The consumer's
        /// Clojure source lives under these; a task file at the project root is
        /// already reachable without them.
        /// </param>
        /// <param name="logger">
        /// Receives nostrand's stdout, one line per call, for the duration of a
        /// call. Null leaves Console.Out alone.
        /// </param>
        public NostrandHost(IEnumerable<string> runtimeDirectories,
                            string projectDirectory,
                            IEnumerable<string> sourceRoots,
                            Action<string> logger)
        {
            if (runtimeDirectories == null)
                throw new ArgumentNullException(nameof(runtimeDirectories));
            if (projectDirectory == null)
                throw new ArgumentNullException(nameof(projectDirectory));

            _runtimeDirectories = new List<string>(runtimeDirectories).ToArray();
            _projectDirectory = Path.GetFullPath(projectDirectory);
            _logger = logger;

            var roots = new List<string> { _projectDirectory };
            if (sourceRoots != null)
                foreach (var root in sourceRoots)
                    roots.Add(Path.GetFullPath(root));
            _loadPathRoots = roots.ToArray();
        }

        /// <summary>
        /// Whether this host has booted the runtime and loaded nostrand.
        /// </summary>
        public bool IsPrewarmed => _booted;

        /// <summary>
        /// Boot the runtime and load nostrand's own Clojure, once per domain.
        /// Called by every entry point; public so a host can pay it at a moment
        /// of its choosing rather than inside a build.
        /// </summary>
        public void Prewarm()
        {
            if (_booted)
                return;

            Nostrand.Runtime.Boot(_runtimeDirectories, EditorCodeLoadOrder);

            // The domain, not the instance, owns the loaded nostrand: a second
            // instance must not re-run its init types and re-def its atoms.
            if (Namespace.find(Symbol.intern("nostrand.tasks")) == null)
                Nostrand.Runtime.LoadNostrand();

            // Deliberately no RT.PostBootstrapInit(), which the CLI does call.
            // It ends in MaybeLoadCljScript("user.clj") via
            // clojure.lang.Compiler.load, which the fork answers with
            // NotSupportedException: one stray user.clj on a root would break
            // every boot.

            _booted = true;
        }

        /// <summary>
        /// Run one nostrand command, the argv <c>nos</c> would have taken:
        /// <c>Run(new[] { "compile-project", ":out", "\"Assets/Plugins/Magic\"", ":clean?", "true" })</c>.
        /// A bare name resolves against nostrand.tasks then clojure.core; a
        /// <c>ns/name</c> loads that namespace off the load path first.
        /// False when the name resolved to neither a function nor a file. An
        /// unknown <c>ns/name</c> throws FileNotFoundException instead, from the
        /// RT.load inside Nostrand.FindFunction; the CLI has the same hole.
        /// </summary>
        /// <param name="resolveAssemblies">
        /// Install nostrand's AssemblyResolve hook for the duration of the call.
        /// Off by default: it is a process-global hook on the Editor domain and
        /// <c>resolve-assembly-load</c> force-loads the first name match under
        /// the assembly path, which is the project root when MONO_PATH is unset.
        /// A project that needs a DLL should <c>assembly-load-from</c> it.
        /// </param>
        public bool Run(string[] command, bool resolveAssemblies = false)
        {
            if (command == null)
                throw new ArgumentNullException(nameof(command));
            if (command.Length == 0)
                throw new ArgumentException("A nostrand command needs at least a task name.", nameof(command));

            return InCall(resolveAssemblies,
                          () => Nostrand.Runtime.Run(Nostrand.Nostrand.ReadArguments(command)));
        }

        /// <summary>
        /// Read and evaluate one form under the same project and bindings a task
        /// would run under. The bindings are what make a form containing
        /// <c>ns</c> or <c>in-ns</c> legal: without them it throws
        /// "Can't change/establish root binding of: *ns* with set".
        /// </summary>
        public object Eval(string source, bool resolveAssemblies = false)
        {
            if (source == null)
                throw new ArgumentNullException(nameof(source));

            return InCall(resolveAssemblies,
                          () => RT.var("clojure.core", "eval").invoke(RT.readString(source)));
        }

        /// <summary>
        /// Push the thread bindings a task runs under; dispose to pop them.
        /// <see cref="Run"/> and <see cref="Eval"/> do this for you. Call it
        /// directly only to hold one set of bindings across several calls into
        /// the runtime that this class does not wrap.
        /// </summary>
        /// <param name="output">
        /// Bound to clojure.core/*out*, or null to leave it alone. Redirecting
        /// Console.Out is not enough on its own: *out* is a var, bound once from
        /// Console.Out when the runtime booted, and println writes through it,
        /// so a task's output would go to the process stdout regardless.
        /// </param>
        public IDisposable BeginTaskScope(TextWriter output = null)
        {
            Prewarm();

            // Only what a long-lived domain needs on top of the set the library
            // binds for every task (*ns*, *warn-on-reflection*, *unchecked-math*).
            // A fresh ref per scope: load-lib skips a require for any lib already
            // in *loaded-libs* (core.clj:6159), so in a warm domain a dependency
            // edited since the last call is never re-read, the build compiles
            // against the in-memory version, never rewrites that dependency's
            // DLL, and reports success. Out of process this cannot happen because
            // every nos run starts empty; build.clj binds it for the bootstrap
            // for the same reason.
            IPersistentMap extra = RT.mapUniqueKeys(
                RT.var("clojure.core", "*loaded-libs*"),
                RT.var("clojure.core", "ref").invoke(RT.var("clojure.core", "sorted-set").invoke()));

            // Redirecting Console.Out is not enough on its own: *out* is a var,
            // bound once from Console.Out when the runtime booted, and println
            // writes through it.
            if (output != null)
                extra = (IPersistentMap)extra.assoc(RT.OutVar, output);

            return Nostrand.Runtime.PushTaskBindings(
                Namespace.find(Symbol.intern("nostrand.core")), extra);
        }

        T InCall<T>(bool resolveAssemblies, Func<T> body)
        {
            Prewarm();
            AssertWorkingDirectory();

            var previousOut = Console.Out;
            ResolveEventHandler resolver = null;
            IDisposable scope = null;
            try
            {
                if (resolveAssemblies)
                {
                    resolver = Nostrand.AssemblyResolver.Resolve;
                    AppDomain.CurrentDomain.AssemblyResolve += resolver;
                }
                // Both halves are needed: *out* carries println and the task's
                // own reporting, Console.Out carries anything the runtime or a
                // library writes directly.
                LineLogWriter log = null;
                if (_logger != null)
                {
                    log = new LineLogWriter(_logger);
                    Console.SetOut(log);
                }

                scope = BeginTaskScope(log);

                Nostrand.Runtime.EstablishProject(_loadPathRoots, replaceLoadPath: true);

                return body();
            }
            finally
            {
                // A leaked frame corrupts *ns* and *warn-on-reflection* for the
                // life of the domain, which on Unity's main thread means until
                // the Editor quits.
                if (scope != null)
                    scope.Dispose();
                Console.Out.Flush();
                Console.SetOut(previousOut);
                if (resolver != null)
                    AppDomain.CurrentDomain.AssemblyResolve -= resolver;
            }
        }

        // cwd is load-bearing well past config discovery: magic.emission/fresh-module
        // calls DefineDynamicAssembly with no directory, so Mono captures the cwd as
        // the save directory, and EmitAssembly/File.Move use bare file names
        // (api.clj:235,238). Unity's cwd is the project root and writable, so the
        // default is right; it is also a mutable process-global that a task file or
        // another Editor tool can move. Fail loudly rather than move it back.
        void AssertWorkingDirectory()
        {
            var cwd = Path.GetFullPath(Directory.GetCurrentDirectory());
            if (!SamePath(cwd, _projectDirectory))
                throw new InvalidOperationException(
                    "nostrand emits relative to the process working directory, which has moved. "
                    + "Expected " + _projectDirectory + ", found " + cwd
                    + ". Restore it before compiling; this host never sets it.");
        }

        // Ordinal-insensitive, matching the case-insensitive filesystems this
        // runs on, and separator-insensitive at the end.
        static bool SamePath(string a, string b)
        {
            return string.Equals(a.TrimEnd(Path.DirectorySeparatorChar),
                                 b.TrimEnd(Path.DirectorySeparatorChar),
                                 StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>
        /// Console.Out for the length of a call, one logger callback per line.
        /// </summary>
        sealed class LineLogWriter : TextWriter
        {
            readonly Action<string> _logger;
            readonly StringBuilder _line = new StringBuilder();
            bool _logging;

            public LineLogWriter(Action<string> logger)
            {
                _logger = logger;
            }

            public override Encoding Encoding => Encoding.UTF8;

            public override void Write(char value)
            {
                if (value == '\n')
                    Flush();
                else if (value != '\r')
                    _line.Append(value);
            }

            // Drops a blank line rather than logging an empty entry. The guard
            // is for a logger that itself writes to Console.Out, which is this
            // writer for the length of the call: without it that recurses until
            // the stack goes, which on Mono is a segfault rather than an
            // exception. Unity's Debug.Log does not, but a caller's own
            // Console.WriteLine logger is an easy mistake to make.
            public override void Flush()
            {
                if (_line.Length == 0 || _logging)
                    return;
                var text = _line.ToString();
                _line.Length = 0;
                _logging = true;
                try
                {
                    _logger(text);
                }
                finally
                {
                    _logging = false;
                }
            }
        }
    }
}
