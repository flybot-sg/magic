using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using clojure.lang;
using Magic.Unity;
using NostrandLib = Nostrand.Nostrand;

// Probes 1-7 for magic-unity/Editor/NostrandHost/NostrandHost.cs, run outside
// Unity against the package's own shipped DLL set. Argument order:
//   <runtime/magic> <editor/compiler> <fixture> <out>
// Each probe prints one PASS/FAIL line; exit 0 = all passed.
public static class NostrandHostProbe
{
	static int failures;

	static void Check(string label, bool ok, string detail = null)
	{
		Console.WriteLine((ok ? "[PASS] " : "[FAIL] ") + label + (detail == null ? "" : " -- " + detail));
		if (!ok)
			failures++;
	}

	public static int Main(string[] args)
	{
		var magicDir = Path.GetFullPath(args[0]);
		var compilerDir = Path.GetFullPath(args[1]);
		var fixture = Path.GetFullPath(args[2]);
		var outDir = Path.GetFullPath(args[3]);

		// The host asserts the cwd is the project root, because that is the
		// directory magic.emission saves emitted assemblies relative to.
		Directory.SetCurrentDirectory(fixture);

		// The real stdout, captured before the host ever redirects Console.Out:
		// echoing through Console.WriteLine here would write back into the
		// host's own writer.
		var stdout = Console.Out;
		var logged = new List<string>();
		var host = new NostrandHost(new[] { magicDir, compilerDir },
		                            fixture,
		                            new[] { "src" },
		                            line => { logged.Add(line); stdout.WriteLine("       | " + line); });

		Probe1And2(host);
		Probe3(host);
		Probe4();
		Probe5(host);
		Probe6(host);
		Probe7(host, fixture, outDir, logged);

		Console.WriteLine();
		Console.WriteLine(failures == 0
			? "RESULT: NostrandHost probes green."
			: "RESULT: " + failures + " probe assertion(s) failed.");
		return failures == 0 ? 0 : 1;
	}

	// Cold boot of the shipped DLL set, nostrand's own namespaces included. A
	// nostrand.*.clj.dll missing from Editor/Compiler, or one compiled against
	// the CLI exe rather than Nostrand.dll, fails here.
	static void Probe1And2(NostrandHost host)
	{
		Console.WriteLine("=== Probe 1-2: cold boot + nostrand's own Clojure ===");
		var sw = Stopwatch.StartNew();
		host.Prewarm();
		sw.Stop();

		Check("host reports prewarmed", host.IsPrewarmed);
		var three = RT.var("magic.api", "eval").invoke(RT.readString("(+ 1 2)"));
		Check("compiler evaluates (+ 1 2)", Convert.ToInt64(three) == 3L, "got " + three);
		Check("nostrand.core loaded", Namespace.find(Symbol.intern("nostrand.core")) != null);
		Check("nostrand.tasks loaded", Namespace.find(Symbol.intern("nostrand.tasks")) != null);
		Check("compile-project interned",
			Namespace.find(Symbol.intern("nostrand.tasks"))
				.FindInternedVar(Symbol.intern("compile-project")) != null);
		// tasks.clj resolves repl-fn with requiring-resolve at call time, so a
		// host that never runs a repl task does not pay for nostrand.repl.
		Check("nostrand.repl absent until a repl task asks for it",
			Namespace.find(Symbol.intern("nostrand.repl")) == null);
		Console.WriteLine("       cold boot: " + sw.ElapsedMilliseconds + " ms (timing is printed, never asserted)");
		Console.WriteLine();
	}

	// A task dispatched against a consumer load path, requiring its namespaces
	// from source.
	static void Probe3(NostrandHost host)
	{
		Console.WriteLine("=== Probe 3: require-from-source at a consumer load path ===");
		Check("Run dispatched probe.a/g", host.Run(new[] { "probe.a/g" }));
		Check("probe.a loaded from source", Namespace.find(Symbol.intern("probe.a")) != null);
		Check("probe.b came in transitively", Namespace.find(Symbol.intern("probe.b")) != null);
		Check("an unknown bare task returns false", !host.Run(new[] { "no-such-task-anywhere" }));
		Console.WriteLine();
	}

	// The three shapes Nostrand.FindFunction resolves.
	static void Probe4()
	{
		Console.WriteLine("=== Probe 4: FindFunction dispatch, all three shapes ===");
		Check("qualified ns/var", NostrandLib.FindFunction("probe.a/g") != null);
		Check("bare name from nostrand.tasks", NostrandLib.FindFunction("compile-project") != null);
		Check("bare name from clojure.core", NostrandLib.FindFunction("inc") != null);
		Check("unresolvable name is null", NostrandLib.FindFunction("no-such-task-anywhere") == null);
		Console.WriteLine();
	}

	// Eval runs under the task bindings, a redefinition reaches an
	// already-loaded caller, and a second Prewarm is a no-op.
	static void Probe5(NostrandHost host)
	{
		Console.WriteLine("=== Probe 5: Eval under the task bindings, redefinition, boot idempotence ===");
		// Outside the bindings *ns* has only a root binding and in-ns sets it.
		// This is the failure Eval's frame exists to prevent.
		Check("a bare in-ns eval is illegal without the task bindings", Throws(() =>
			RT.var("magic.api", "eval").invoke(RT.readString("(in-ns 'probe.b)"))));

		// probe.a was loaded by Probe 3, not compiled by compile-project, so it
		// carries the default flags and not production-flags' direct linking.
		// With direct linking on, this call site would keep calling the old body
		// and the probe would assert something that holds only by accident.
		var before = RT.var("probe.a", "g").invoke();
		host.Eval("(do (in-ns 'probe.b) (clojure.core/defn f [] \"B-REDEF\"))");
		var after = RT.var("probe.a", "g").invoke();
		Check("Eval of an in-ns form succeeded and the call site saw it",
			(string)after == "B-REDEF", "before=" + before + " after=" + after);

		// The same eval is illegal again: a frame that did not pop would leave
		// *ns* and *warn-on-reflection* thread-bound for the life of the domain.
		Check("the frame popped", Throws(() =>
			RT.var("magic.api", "eval").invoke(RT.readString("(in-ns 'probe.b)"))));

		var sw = Stopwatch.StartNew();
		host.Prewarm();
		sw.Stop();
		Check("second Prewarm did not re-boot", sw.ElapsedMilliseconds < 200, sw.ElapsedMilliseconds + " ms");
		// Re-running magic/api's init type would re-run every top-level form;
		// Runtime.Boot guards on Namespace.find, and this is the observable side.
		Check("clojure.core survived the second boot",
			Convert.ToInt64(RT.var("magic.api", "eval").invoke(RT.readString("(+ 40 2)"))) == 42L);
		Console.WriteLine();
	}

	// nostrand.core/update-load-path used to concatenate onto the existing
	// *load-paths*, so each call left the previous call's roots in front of
	// the runtime's, still shadowing (#182). The host relies on set-load-path
	// deriving the var from the roots and a fixed base; this pins that.
	static void Probe6(NostrandHost host)
	{
		Console.WriteLine("=== Probe 6: *load-paths* is restored, not accumulated ===");
		var counts = new List<int>();
		for (var i = 0; i < 3; i++)
		{
			host.Run(new[] { "probe.a/g" });
			counts.Add(RT.count(RT.var("clojure.core", "*load-paths*").deref()));
		}
		Check("*load-paths* is the same length after every call",
			counts.Distinct().Count() == 1,
			"counts=" + string.Join(",", counts.Select(c => c.ToString()).ToArray()));
		Console.WriteLine();
	}

	// The regression the *loaded-libs* rebinding exists for: two compiles in one
	// process with a dependency edited in between. Without it, load-lib skips
	// the require for probe.b (already in the set from Probe 3), so the compile
	// never re-reads it, never writes its DLL, and reports success.
	static void Probe7(NostrandHost host, string fixture, string outDir, List<string> logged)
	{
		Console.WriteLine("=== Probe 7: two compiles in one process, dependency edited between ===");
		var bSource = Path.Combine(fixture, Path.Combine("src", Path.Combine("probe", "b.clj")));
		var bDll = Path.Combine(outDir, "probe.b.clj.dll");
		var original = File.ReadAllText(bSource);
		if (Directory.Exists(outDir))
			Directory.Delete(outDir, true);

		try
		{
			logged.Clear();
			Compile(host, outDir);
			Check("the task's stdout reached the logger",
				logged.Any(l => l.Contains("Compiling")), logged.Count + " line(s)");
			Check("first compile wrote probe.a's DLL", File.Exists(Path.Combine(outDir, "probe.a.clj.dll")));
			Check("first compile wrote probe.b's DLL", File.Exists(bDll));
			var firstB = File.Exists(bDll) ? File.ReadAllBytes(bDll) : new byte[0];

			File.WriteAllText(bSource, original.Replace("\"B-V1\"", "\"B-V2\""));
			Compile(host, outDir);
			var secondB = File.Exists(bDll) ? File.ReadAllBytes(bDll) : new byte[0];
			Check("second compile re-emitted the edited dependency",
				secondB.Length > 0 && !firstB.SequenceEqual(secondB));
		}
		finally
		{
			File.WriteAllText(bSource, original);
		}
		// Whether the new body actually reached both emitted DLLs is asserted by
		// run.sh, which loads them in a second mono process: the domain that just
		// compiled them holds the definitions in memory either way.
		Console.WriteLine();
	}

	static void Compile(NostrandHost host, string outDir)
	{
		Check("compile-project ran", host.Run(new[]
		{
			"compile-project",
			":namespaces", "[probe.a]",
			":out", "\"" + outDir + "\"",
			":clean?", "true",
		}));
	}

	static bool Throws(Action f)
	{
		try
		{
			f();
			return false;
		}
		catch (Exception)
		{
			return true;
		}
	}
}
