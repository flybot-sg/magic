using System;
using System.IO;

// FileSystemWatcher probe: prints every event two watchers see for a directory.
//   [reloader-cfg] — the PRE-V2 reloader configuration (NotifyFilters.LastWrite,
//                    Filter "*.clj*", recursive). Kept deliberately to measure the FSW
//                    environment the fix overcame; the shipped reloader now uses
//                    LastWrite|FileName + Filter "*" via DebouncedFileWatcher.
//   [all-filters]  — every NotifyFilter + Filter "*", i.e. everything the backend can deliver.
// Usage: mono WatchProbe.exe <directory-to-watch>
class WatchProbe {

    static void Main(string[] args) {
        var dir = args.Length > 0 ? args[0] : ".";

        var reloaderCfg = new FileSystemWatcher(dir) {
            NotifyFilter = NotifyFilters.LastWrite,
            Filter = "*.clj*",
            IncludeSubdirectories = true,
        };
        Hook(reloaderCfg, "reloader-cfg");

        var allFilters = new FileSystemWatcher(dir) {
            NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName
                         | NotifyFilters.DirectoryName | NotifyFilters.Size
                         | NotifyFilters.CreationTime | NotifyFilters.Attributes
                         | NotifyFilters.LastAccess | NotifyFilters.Security,
            Filter = "*",
            IncludeSubdirectories = true,
        };
        Hook(allFilters, "all-filters ");

        reloaderCfg.EnableRaisingEvents = true;
        allFilters.EnableRaisingEvents = true;

        Console.WriteLine("mono FSW backend: " +
            (Environment.GetEnvironmentVariable("MONO_MANAGED_WATCHER") ?? "(default)"));
        Console.WriteLine($"main thread id: {System.Threading.Thread.CurrentThread.ManagedThreadId}");
        Console.WriteLine($"Watching {Path.GetFullPath(dir)} recursively.");
        Console.WriteLine("Save the file from Neovim now. Ctrl-C to quit.");
        System.Threading.Thread.Sleep(System.Threading.Timeout.Infinite);
    }

    static void Hook(FileSystemWatcher w, string tag) {
        w.Changed += (s, e) => Log(tag, "Changed", e.FullPath);
        w.Created += (s, e) => Log(tag, "Created", e.FullPath);
        w.Deleted += (s, e) => Log(tag, "Deleted", e.FullPath);
        w.Renamed += (s, e) => Log(tag, "Renamed", $"{e.OldFullPath} -> {e.FullPath}");
        w.Error += (s, e) => Console.WriteLine($"[{tag}] ERROR {e.GetException().Message}");
    }

    static void Log(string tag, string kind, string detail) {
        var tid = System.Threading.Thread.CurrentThread.ManagedThreadId;
        Console.WriteLine($"{DateTime.Now:HH:mm:ss.fff} [{tag}] tid={tid} {kind,-7} {detail}");
    }

}
