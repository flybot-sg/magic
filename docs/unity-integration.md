# Unity integration

How to use MAGIC in a Unity project: compile your Clojure to `.clj.dll` with the `nos` CLI outside Unity, then load it at play time through the `magic-unity` UPM package. The package also rewrites MAGIC's IL during IL2CPP builds (iOS, Android, consoles).

This is the consumer-side guide. For what the package contains and how its internals work, see [magic-unity](../magic-unity). For the `deps.edn` / `magic.edn` compile workflow in depth, see [Porting a Clojure library to MAGIC](./porting-libraries-to-magic.md).

## Steps

1. **Install `nos`** (build-time only; needs the `mono` runtime, no .NET SDK). See [Install](../README.md#install) in the root README for the one-line installer.

2. **Add the package** to `Packages/manifest.json`, pinned to a tag from the [releases page](https://github.com/flybot-sg/magic/releases):

   ```json
   {
     "dependencies": {
       "sg.flybot.magic.unity": "https://github.com/flybot-sg/magic.git?path=magic-unity#<tag>"
     }
   }
   ```

   One package, for every project. Player builds always run MAGIC; in the Editor the package loads stock ClojureCLR unless you opt in (see [Choosing the Editor runtime](#choosing-the-editor-runtime)).

3. **Add `deps.edn` and `magic.edn`** at your project root. `deps.edn` declares your source `:paths` and any `:deps`; `magic.edn` points the build at Unity's plugins folder:

   ```clojure
   ;; magic.edn
   {:build {:namespaces [my.game.core] :out "Assets/Plugins/Magic"}}
   ```

   A project with custom build/test steps can still hand-write a `dotnet.clj` instead (see [the porting guide](./porting-libraries-to-magic.md)); `unity-examples/magic-unity-smoke` does, because its test runner is not `clojure.test`.

4. **Compile before opening Unity:**

   ```bash
   nos build
   ```

   This drops your `.clj.dll` into `Assets/Plugins/Magic/`, where Unity loads them.

5. **Run it — write a loader, then Play.** Nothing runs your `.clj.dll` automatically: Unity doesn't know which DLLs are Clojure or which var is the entry point. Put a `MonoBehaviour` on a GameObject that requires your namespace and invokes a var (pattern: [`SmokeTestRunner.cs`](../unity-examples/magic-unity-smoke/Assets/Scripts/SmokeTestRunner.cs)):

   ```csharp
   using Magic.Unity;

   void Start() {
       Clojure.Require("my.game.core");          // loads your compiled namespace
       Clojure.GetVar("my.game.core", "start!").invoke();
   }
   ```

   To run this in **Editor Play mode** you need MAGIC as the Editor runtime: `MAGIC > Editor Runtime > Use MAGIC in the Editor` (see [Choosing the Editor runtime](#choosing-the-editor-runtime)). Without it the same call sites drive stock ClojureCLR instead.

   The smoke example already ships this loader — open `Assets/Smoke.unity` and Play to see it.

6. **Build a player to exercise the IL2CPP / AOT path.** Editor Play runs under Mono and can't surface IL2CPP-only regressions — those appear only in a real Standalone/iOS player build, which is where MAGIC's static-MSIL design earns its keep. How you trigger that build is project-specific (a `BuildPipeline` editor script, your CI, or Unity's Build Settings). The smoke example wires it to a one-click menu and reports PASS/FAIL in the built player — see the [smoke README](../unity-examples/magic-unity-smoke/README.md#run) for that flow.

   For CI without Unity, `nos test` runs the Mono-side tests headless. This is a fast gate but does not exercise IL2CPP.

## Choosing the Editor runtime

The package ships **both** Clojure runtimes and a scripting define symbol, `MAGIC_RUNTIME_IN_EDITOR`, decides which one the **Editor** loads. Player builds always run MAGIC.

| | symbol unset (default) | symbol set |
|---|---|---|
| Editor loads | stock ClojureCLR 1.11.0 | the MAGIC runtime |
| MAGIC in Editor Play mode | not available | works |
| Editor REPL / hot-reload against stock | works | not available |
| Player builds (Mono / IL2CPP) | identical | identical |

Flip it from **`MAGIC > Editor Runtime > Use MAGIC in the Editor`**, which writes the symbol to every build-target group (the Editor compiles with the *active* group's symbols). From CI: `Magic.Unity.EditorRuntime.UseMagic()` / `UseStock()`.

- **Set it** if MAGIC runs in your Editor (Play mode, edit-mode tooling). The smoke example does.
- **Leave it unset** if your Editor runs stock ClojureCLR for REPL / hot-reload and MAGIC ships only in player builds.

Both states are silent — no `Assembly is incompatible with the editor` narration, no `Duplicate assembly 'Clojure.dll'` line — because the selection is expressed as a define constraint on every shipped DLL, which Unity evaluates before it loads anything. That is also what keeps the stock init-time probe for `clojure.core.clj` away from the MAGIC assemblies ([#25](https://github.com/flybot-sg/magic/issues/25)) with no Editor guard involved.

Two consequences worth knowing:

- **In the default state, `Clojure.Boot` / `Require` / `GetVar` drive stock, not MAGIC.** Identical call sites, different meanings: stock loads namespaces from `.clj` source through the DLR, MAGIC loads AOT-compiled `InitType`. Editor `Require` therefore needs your sources on stock's load path, and Editor behaviour can diverge from the player's.
- **Do not vendor your own `Clojure.dll` under `Assets`.** Unity dedups managed plugins by file name, and an unconstrained copy wins over the package's — which silently breaks the selection. Your own compiled `*.clj.dll` are fine: the package stamps the matching constraint onto them on import so they follow the Editor runtime.

Full rationale, including why the polarity cannot be inverted: [docs/dual-runtimes.md](./dual-runtimes.md).

The in-repo regression for both states is [`unity-examples/magic-unity-coexist`](../unity-examples/magic-unity-coexist), driven by `bb coexist-noise`.

**API Compatibility Level must be `.NET Framework`** (`Project Settings > Player`) whenever the Editor is on stock — that is, in the default state. Stock ClojureCLR is distributed as `net462`, and its DLR dependencies reference assemblies that do not exist in the .NET Standard 2.1 profile — `Microsoft.Scripting` references `System.Configuration`, `Microsoft.Dynamic` references `System.Runtime.Remoting` and `System.Xaml`. ClojureCLR's compile and interop paths always run through the DLR, so this is not avoidable, and there is no netstandard build to fall back on: the ClojureCLR nupkg's `netstandard2.1` assembly carries none of the AOT-compiled standard library, which is spliced into the `net462` one only. The package warns when it finds this wrong; the level itself is a project setting the consumer sets in `Project Settings > Player`.

It is harmless in the MAGIC-Editor state, which has no DLR: `magic-unity-smoke` runs `.NET Standard 2.1` and opts into MAGIC, `magic-unity-coexist` runs `.NET Framework` because it exercises both.

## Examples

- [`unity-examples/magic-unity-smoke`](../unity-examples/magic-unity-smoke): standalone IL2CPP regression suite. The reference pattern for `deps.edn` and a custom `dotnet.clj`, with a `MAGIC -> Smoke -> Build & Run IL2CPP` menu. Run by hand on Unity 2022.3.62f3.
- [`unity-examples/magic-unity-coexist`](../unity-examples/magic-unity-coexist): headless regression for both Editor-runtime states, driven by `bb coexist-noise`. Takes both runtimes from the package, as a consumer would, and installs it from a repacked tarball so the install is immutable (PackageCache).
