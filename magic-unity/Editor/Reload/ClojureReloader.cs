using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;

namespace Magic.Unity
{
    /// <summary>
    /// Effective only with a runtime that loads the code from the source
    /// (ClojureCLR).
    ///
    /// A file event marks a path as dirty on the background watcher thread. One
    /// single thread calls <see cref="Poll"/>, which reads the file, evaluates it,
    /// and retries a read that fails.
    ///
    /// The roots are captured at construction: to watch another set, dispose
    /// this one and build a new one.
    /// </summary>
    public sealed class ClojureReloader : IDisposable
    {
        struct Retry
        {
            public int Attempts;
            public long DueMs;
        }

        const int MaxReadAttempts = 2;

        const long RetryBackoffMs = 150;

        readonly DebouncedFileWatcher _watcher;
        readonly Action<string> _logger;
        readonly Action<string> _onChanged;
        bool _disposed;

        readonly Dictionary<string, Retry> _retries = new Dictionary<string, Retry>(StringComparer.Ordinal);

        readonly Stopwatch _clock = Stopwatch.StartNew();

        /// <summary>
        /// Monitors the source roots. Logs if no root is given, or if a root is
        /// absent. Only a null <paramref name="roots"/> throws.
        /// </summary>
        /// <param name="logger">The reporting mechanism. If null, exceptions are
        /// skipped too. This callback must not throw an exception.</param>
        /// <param name="onChanged">A synchronous callback after each reload.</param>
        public ClojureReloader(IEnumerable<string> roots, Action<string> logger, Action<string> onChanged = null)
        {
            if (roots == null)
            {
                throw new ArgumentNullException(nameof(roots));
            }
            _logger = logger;
            _onChanged = onChanged;
            var captured = roots.ToArray();
            if (captured.Length == 0)
            {
                _logger?.Invoke("No clj source root. Reload disabled.");
                return;
            }
            _watcher = new DebouncedFileWatcher(IsClojureSource, _logger);
            foreach (var root in captured)
            {
                bool watched;
                try
                {
                    watched = _watcher.AddRoot(root);
                }
                catch (Exception ex)
                {
                    _logger?.Invoke($"Failed to watch clj source root, skipped. {root}: {ex}");
                    continue;
                }
                if (watched)
                {
                    _logger?.Invoke($"Watching clj source root. {root}");
                }
            }
        }

        /// <summary>
        /// Call this method on each frame or tick of the main thread of the host.
        /// It evaluates each stable file change, then the read retries that are due.
        /// Does nothing when no root is watched or after Dispose. Handles
        /// IOException or UnauthorizedAccessException by retrying.
        /// Do not call `Poll` from the onChanged callback.
        /// </summary>
        public void Poll()
        {
            if (_disposed || _watcher == null) // a disposed watcher still hands out pending marks
            {
                return;
            }
            foreach (var path in _watcher.TakeSettled())
            {
                _retries.Remove(path); // otherwise, new content keeps the failure count of previous content
                Attempt(path);
            }
            foreach (var path in DueRetries())
            {
                if (_watcher.IsPending(path))
                {
                    _retries.Remove(path);
                    _logger?.Invoke($"Reload retry for {path} superseded by a pending file event.");
                    continue;
                }
                Attempt(path);
            }
        }

        /// <summary>
        /// Idempotent, and callable from the onChanged callback.
        /// </summary>
        public void Dispose()
        {
            _disposed = true;
            _watcher?.Dispose();
            _retries.Clear();
        }

        void Attempt(string path)
        {
            // Check here, since Dispose() can be called in the onChanged callback
            if (_disposed)
            {
                return;
            }
            // Compiler.load evaluates one form after the other, thus a save during
            // the evaluation would give mixed content.
            string snapshot;
            try
            {
                snapshot = File.ReadAllText(path);
            }
            catch (Exception ex) when (ex is FileNotFoundException || ex is DirectoryNotFoundException)
            {
                // These errors can be thrown if a file was deleted, and are inherited from IOException
                _retries.Remove(path);
                return;
            }
            catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
            {
                // On Windows a rename-replace operation throws
                // UnauthorizedAccessException for a short time, and not IOException.
                Defer(path, ex);
                return;
            }
            // The budget is only for the conflicts between a read and a save: code
            // that does not compile stays the same after each new read.
            _retries.Remove(path);
            try
            {
                clojure.lang.Compiler.load(new StringReader(snapshot),
                    path, Path.GetFileName(path), path);  // the reader conditionals need the name of the .cljc file
            }
            catch (Exception ex)
            {
                _logger?.Invoke($"Reload failed for {path}: {ex}");
                return;
            }
            _logger?.Invoke($"Reloaded {path}");
            try
            {
                _onChanged?.Invoke(path);
            }
            catch (Exception ex)
            {
                _logger?.Invoke($"Reload callback failed for {path}: {ex}");
            }
        }

        void Defer(string path, Exception ex)
        {
            _retries.TryGetValue(path, out var retry);
            retry.Attempts++;
            if (retry.Attempts >= MaxReadAttempts)
            {
                _retries.Remove(path);
                _logger?.Invoke(
                    $"Reload failed for {path} (unreadable after {MaxReadAttempts} attempts): {ex.Message}"
                );
                return;
            }
            retry.DueMs = _clock.ElapsedMilliseconds + RetryBackoffMs;
            _retries[path] = retry;
            _logger?.Invoke(
                $"Reload deferred (attempt {retry.Attempts}/{MaxReadAttempts}) for {path}: {ex.Message}"
            );
        }

        List<string> DueRetries()
        {
            var due = new List<string>();
            var now = _clock.ElapsedMilliseconds;
            foreach (var kv in _retries)
            {
                if (now >= kv.Value.DueMs)
                {
                    due.Add(kv.Key);
                }
            }
            return due;
        }

        static bool IsClojureSource(string path)
        {
            var name = Path.GetFileName(path);
            // Prefix checks - Emacs names its lock file `.#core.clj`
            if (name.Length == 0 || name[0] == '.' || name[0] == '#')
            {
                return false;
            }
            // Case-insensitive suffix checks
            var ext = Path.GetExtension(name);
            return ext.Equals(".clj", StringComparison.OrdinalIgnoreCase)
                || ext.Equals(".cljc", StringComparison.OrdinalIgnoreCase)
                || ext.Equals(".cljr", StringComparison.OrdinalIgnoreCase);
        }
    }
}
