using System;
using System.IO;
using clojure.lang;
using NostrandRuntime = Nostrand.Runtime;

// Probe 7's second process: boot the shipped runtime with the emitted DLL
// directory added to the set, and call into it. Nothing here compiles, so what
// it reports came off disk -- the compiling domain holds the new definitions in
// memory whether or not they reached the DLLs.
//   <runtime/magic> <editor/compiler> <out> <ns/var>
public static class LoadEmittedDll
{
	public static int Main(string[] args)
	{
		var dirs = new[] { Path.GetFullPath(args[0]), Path.GetFullPath(args[1]), Path.GetFullPath(args[2]) };
		var parts = args[3].Split('/');

		NostrandRuntime.Boot(dirs, new[]
		{
			RuntimeBootstrapFlag.CodeSource.InitType,
			RuntimeBootstrapFlag.CodeSource.FileSystem,
		});
		RT.TryLoadInitType(parts[0].Replace('.', '/'));

		var ns = Namespace.find(Symbol.intern(parts[0]));
		var v = ns == null ? null : ns.FindInternedVar(Symbol.intern(parts[1]));
		if (v == null)
		{
			Console.WriteLine("MISSING " + args[3]);
			return 1;
		}
		Console.WriteLine(v.invoke());
		return 0;
	}
}
