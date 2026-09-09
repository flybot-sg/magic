using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;

namespace Magic.Unity
{
    /// <summary>
    /// Turns the <see cref="FileSystemWatcher"/> event stream into a debounced list
    /// of changed paths, drained on the caller's thread via <see cref="TakeSettled"/>.
    /// One editor save commonly fires 2-3 FSW events (content + metadata writes) on
    /// arbitrary ThreadPool threads.
    ///
    /// Watcher threads produce, a single thread consumes.
    ///
    /// A watcher is always removed from the table before it is disposed: disposal
    /// itself trips Error, and a watcher still in the table would be re-armed.
    /// </summary>
    internal sealed class DebouncedFileWatcher : IDisposable
    {
        const int InternalBufferBytes = 64 * 1024;

        const int SettleMs = 200;

        readonly Func<string, bool> _accept;
        readonly Action<string> _log;

        readonly ConcurrentDictionary<string, long> _pending =
            new ConcurrentDictionary<string, long>(StringComparer.Ordinal);

        readonly Dictionary<string, FileSystemWatcher> _watchersByRoot =
            new Dictionary<string, FileSystemWatcher>(StringComparer.Ordinal);
        readonly object _watchersLock = new object();

        readonly Stopwatch _clock = Stopwatch.StartNew();

        volatile bool _disposed;

        /// <param name="accept">Must not be null.</param>
        /// <param name="log">May be null. Must not throw -- an exception escaping an FSW
        /// handler is process-fatal.</param>
        public DebouncedFileWatcher(Func<string, bool> accept, Action<string> log)
        {
            if (accept == null)
            {
                throw new ArgumentNullException(nameof(accept));
            }
            _accept = accept;
            _log = log;
        }

        /// <summary>
        /// Watch <paramref name="dir"/> recursively. Collapses existing overlapping roots.
        /// </summary>
        /// <returns>True if newly armed or already watched.</returns>
        public bool AddRoot(string dir)
        {
            if (string.IsNullOrEmpty(dir))
            {
                return false;
            }
            var full = Normalize(dir);
            lock (_watchersLock)
            {
                if (_disposed)
                {
                    throw new ObjectDisposedException(nameof(DebouncedFileWatcher));
                }
                if (!Directory.Exists(full))
                {
                    _log?.Invoke($"DebouncedFileWatcher: no such directory, not watching. {full}");
                    return false;
                }
                var covered = new List<string>();
                foreach (var existing in _watchersByRoot.Keys)
                {
                    if (Covers(existing, full))
                    {
                        return true;
                    }
                    if (Covers(full, existing))
                    {
                        covered.Add(existing);
                    }
                }
                foreach (var c in covered)
                {
                    var dead = _watchersByRoot[c];
                    _watchersByRoot.Remove(c);
                    dead.Dispose();
                }
                _watchersByRoot[full] = Arm(full);
                return true;
            }
        }

        FileSystemWatcher Arm(string dir)
        {
            var w = new FileSystemWatcher(dir)
            {
                // FileName is for the editors that save by writing a temp file and
                // renaming it over the target (Vim, Emacs)
                NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName,
                // Filter isn't passed to the OS -- all events are buffered anyway. So just
                // filter with _accept.
                Filter = "*",
                IncludeSubdirectories = true,
                // Windows: a burst (git checkout, format-on-save) overflows the buffer
                // and drops events.
                InternalBufferSize = InternalBufferBytes,
            };
            w.Changed += OnFsEvent;
            w.Created += OnFsEvent;
            w.Renamed += OnFsEvent; // for Emacs, a backup rename to `foo.clj~` should be rejected by `_accept`
            w.Error += OnError;
            w.EnableRaisingEvents = true;
            return w;
        }

        void OnFsEvent(object sender, FileSystemEventArgs e)
        {
            Mark(e.FullPath);
        }

        void Mark(string path)
        {
            if (_disposed || !_accept(path))
            {
                return;
            }
            _pending[path] = _clock.ElapsedMilliseconds; // last write wins
        }

        void OnError(object sender, ErrorEventArgs e)
        {
            // Raised when the internal buffer overflowed (a burst dropped events) or the
            // watch broke. The dropped changes are lost; a subsequent save re-marks them.
            var w = sender as FileSystemWatcher;
            if (_disposed || w == null)
            {
                return;
            }
            lock (_watchersLock)
            {
                if (RootOf(w) == null)
                {
                    return;
                }
            }
            _log?.Invoke($"DebouncedFileWatcher error, re-arming: {e.GetException()?.Message}");
            try
            {
                // The toggle follows FSW's own Restart(), see
                // https://github.com/microsoft/referencesource/blob/main/System/services/io/system/io/FileSystemWatcher.cs
                w.EnableRaisingEvents = false;
                w.EnableRaisingEvents = true;
            }
            catch (Exception ex)
            {
                _log?.Invoke(
                    $"DebouncedFileWatcher re-arm failed for {w.Path}, no longer watching: {ex.Message}"
                );
                lock (_watchersLock)
                {
                    var key = RootOf(w);
                    if (key != null)
                    {
                        _watchersByRoot.Remove(key);
                    }
                }
                w.Dispose();
            }
        }

        // Finds the table key by identity: w.Path may not echo the Normalize()d key verbatim.
        string RootOf(FileSystemWatcher w)
        {
            foreach (var kv in _watchersByRoot)
            {
                if (ReferenceEquals(kv.Value, w))
                {
                    return kv.Key;
                }
            }
            return null;
        }

        /// <summary>
        /// Stop tracking and return paths that have been quiet for the settle window.
        ///
        /// Order is unspecified: timestamps are last-touch, not save order.
        /// </summary>
        public List<string> TakeSettled()
        {
            var now = _clock.ElapsedMilliseconds;
            var result = new List<string>();
            foreach (var kv in _pending)
            {
                if (now - kv.Value < SettleMs)
                {
                    continue;
                }
                // Compare-and-remove: a watcher thread may have re-marked this path
                if (RemoveIf(kv.Key, kv.Value))
                {
                    result.Add(kv.Key);
                }
            }
            return result;
        }

        /// <summary>
        /// True while a mark is held that a future <see cref="TakeSettled"/> will
        /// return, settled or not.
        /// </summary>
        public bool IsPending(string path)
        {
            return _pending.ContainsKey(path);
        }

        // The TryRemove(key, value, out) to compare before removing is .NET 5+;
        // this is .NET Framework 4.x-compatible.
        bool RemoveIf(string path, long expected)
        {
            return ((ICollection<KeyValuePair<string, long>>)_pending).Remove(
                new KeyValuePair<string, long>(path, expected)
            );
        }

        static string Normalize(string dir)
        {
            var full = Path.GetFullPath(dir);
            return full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        }

        // Segment-aware: foo/bar covers foo/bar/baz, never foo/barbaz.
        static bool Covers(string ancestor, string descendant)
        {
            if (string.Equals(ancestor, descendant, StringComparison.Ordinal))
            {
                return true;
            }
            return descendant.StartsWith(ancestor + Path.DirectorySeparatorChar, StringComparison.Ordinal);
        }

        // Does not wait for a handler already running on a ThreadPool thread, so
        // events can still arrive after this returns.
        public void Dispose()
        {
            lock (_watchersLock)
            {
                if (_disposed)
                {
                    return;
                }
                _disposed = true;
                foreach (var w in _watchersByRoot.Values)
                {
                    w.Dispose();
                }
                _watchersByRoot.Clear();
            }
        }
    }
}
