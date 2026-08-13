# Unity integration

How to use MAGIC in a Unity project: compile your Clojure to `.clj.dll` with the `nos` CLI outside Unity, then load it at play time through the `magic-unity` UPM package. The package also rewrites MAGIC's IL during IL2CPP builds (iOS, Android, consoles).

This is the consumer-side guide. For the package's C# API and install reference, see [magic-unity/README.md](../magic-unity/README.md). For the `deps.edn` / `magic.edn` compile workflow in depth, see [Porting a Clojure library to MAGIC](./porting-libraries-to-magic.md).

## Steps

1. **Install `nos`** (build-time only; needs the `mono` runtime, no .NET SDK). See [Install](../README.md#install) in the root README.

2. **Add the package** to `Packages/manifest.json`, pinned to a tag from the [releases page](https://github.com/flybot-sg/magic/releases):

   ```json
   {
     "dependencies": {
       "sg.flybot.magic.unity": "https://github.com/flybot-sg/magic.git?path=magic-unity#<tag>"
     }
   }
   ```

3. **Add `deps.edn` and `magic.edn`** at your project root. `deps.edn` declares your source `:paths` and any `:deps`; `magic.edn` points the build at Unity's plugins folder:

   ```clojure
   ;; magic.edn
   {:build {:namespaces [my.game.core] :out "Assets/Plugins/Magic"}}
   ```

   A project with custom build/test steps can hand-write a `dotnet.clj` instead; see [the porting guide](./porting-libraries-to-magic.md).

4. **Compile before opening Unity:**

   ```bash
   nos build
   ```

   This drops your `.clj.dll` into `Assets/Plugins/Magic/`, where Unity loads them.

5. **Write a loader, then Play.** Unity doesn't know which DLLs are Clojure or which var is the entry point, so a `MonoBehaviour` has to require and invoke it (pattern: [`SmokeTestRunner.cs`](../unity-examples/magic-unity-smoke/Assets/Scripts/SmokeTestRunner.cs)):

   ```csharp
   using Magic.Unity;

   void Start() {
       Clojure.Require("my.game.core");
       Clojure.GetVar("my.game.core", "start!").invoke();
   }
   ```

   Editor Play mode needs MAGIC as the Editor runtime (see below); otherwise these calls drive ClojureCLR instead.

6. **Build a player to exercise the IL2CPP / AOT path.** Editor Play runs under Mono and can't surface IL2CPP-only regressions. The smoke example wires this to a one-click menu; see the [smoke README](../unity-examples/magic-unity-smoke/README.md#run). For CI without Unity, `nos test` runs the Mono-side tests headless but doesn't exercise IL2CPP.

## Choosing the Editor runtime

The package ships both MAGIC (the default for the player build) and ClojureCLR (the default for the Unity editor); set the Editor runtime via `Project Settings > MAGIC`, or from a script through `Magic.Unity.EditorRuntime` ([package README](../magic-unity/README.md#editor-api)). ClojureCLR is the default because it hot-reloads from source; MAGIC-in-Editor is for reproducing player behaviour before a build.

Note that:

- **API Compatibility Level must be `.NET Framework`** (`Project Settings > Player`) for ClojureCLR, as it needs assemblies the .NET Standard profile lacks.
- In the default state, `Clojure.Require`/`GetVar` drive ClojureCLR, not MAGIC. These are identical call sites for different runtime. Editor `Require` needs your sources on ClojureCLR's load path.

## Shipping your own compiled DLLs

Your `.clj.dll` / `.cljc.dll` / `.cljr.dll` need the same define constraint as the package's DLLs, or they stay Editor-eligible against a runtime that isn't loaded: Unity unloads most of them as broken assemblies (`Unloading broken assembly <name>, this assembly can cause crashes in the runtime`) and silently binds the rest to ClojureCLR, whose `Clojure.dll` satisfies the MAGIC reference by name. The package applies the constraint automatically on import under `Assets/**` and in embedded/local packages, and reloads scripts when it lands on a DLL the running Editor had already seen unconstrained, so the session recovers in place; the error lines from that first load stay in the Console.

Packages of your own that resolve into the immutable `Library/PackageCache` (registry, git, or tarball deps) need the constraint baked into the `.meta` at publish time.

Existing `Clojure.dll` in your project will conflict with the ones in this package, so they should be deleted or accompanied by additional new constraints.

## Examples

- [`unity-examples/magic-unity-smoke`](../unity-examples/magic-unity-smoke): standalone IL2CPP regression suite, with a `MAGIC -> Smoke -> Build & Run IL2CPP` menu. Run by hand on Unity 2022.3.62f3.
- [`unity-examples/magic-unity-coexist`](../unity-examples/magic-unity-coexist): headless regression for both Editor-runtime states, driven by `bb coexist-noise`.
