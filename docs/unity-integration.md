# Unity integration

How to use MAGIC in a Unity project: compile your Clojure to `.clj.dll` with the `nos` CLI outside Unity, then load it at play time through the `magic-unity` UPM package. The package also rewrites MAGIC's IL during IL2CPP builds (iOS, Android, consoles).

This is the consumer-side guide. For what the package contains and how its internals work, see [magic-unity](../magic-unity). For the `deps.edn` / `dotnet.clj` compile workflow in depth, see [Porting a Clojure library to MAGIC](./porting-libraries-to-magic.md).

## Steps

1. **Install `nos`** (build-time only; needs the `mono` runtime, no .NET SDK). See [Install](../README.md#install) in the root README for the one-line installer.

2. **Add the package** to `Packages/manifest.json`, pinned to a tag from the [releases page](https://github.com/flybot-sg/magic/releases). Pick one variant (see [Choosing a variant](#choosing-a-variant)):

   ```json
   {
     "dependencies": {
       "sg.flybot.magic.unity": "https://github.com/flybot-sg/magic.git?path=magic-unity#<tag>"
     }
   }
   ```

3. **Add `deps.edn` and `dotnet.clj`** at your project root. Copy the templates from [`unity-examples/magic-unity-smoke/`](../unity-examples/magic-unity-smoke/): `deps.edn` declares your source `:paths` and any `:deps`; `dotnet.clj` holds the build/test tasks. The `nostrand.tasks` helpers pin the shipped compiler flags and derive the namespace set from your paths, so `dotnet.clj` stays small.

4. **Compile before opening Unity:**

   ```bash
   nos dotnet/build
   ```

   This drops your `.clj.dll` into `Assets/Plugins/Magic/`, where Unity loads them.

5. **Open Unity and hit Play.** For CI, add a `nos dotnet/run-tests` task to run the Mono-side tests without Unity (see [`magic-unity-smoke/dotnet.clj`](../unity-examples/magic-unity-smoke/dotnet.clj)). IL2CPP-only regressions need a real Unity build.

## Choosing a variant

The package ships in two variants, identical except for whether the runtime `.clj.dll` plugins are visible to the Editor:

| | `sg.flybot.magic.unity` (default) | `sg.flybot.magic.unity.dual` |
|---|---|---|
| Runtime in the Editor | loadable | excluded (`!UNITY_EDITOR`) |
| MAGIC in Editor Play mode | works | not available (Editor uses stock ClojureCLR) |
| Alongside stock ClojureCLR | benign console noise, handled by the guard | silent (runtime absent from the Editor) |
| Player builds (Mono / IL2CPP) | identical | identical |

- **Default** if MAGIC runs in your Editor (Play mode, edit-mode tooling) and there is no stock ClojureCLR.
- **`.dual`** if your Editor runs stock ClojureCLR (REPL / hot-reload) and MAGIC ships only in player builds. Pin `?path=magic-unity-dual#<tag>`.

Full comparison and rationale: [magic-unity, Package variants](../magic-unity/README.md#package-variants).

## Coexistence with stock ClojureCLR

A project that keeps stock ClojureCLR in the Editor and ships MAGIC in players runs both runtimes. With the **default** variant the Editor prints one benign `Assembly is incompatible with the editor` line per runtime `.clj.dll` on each domain reload (Unity narrating the intended exclusion; player builds are unaffected). The **`.dual`** variant excludes the runtime from the Editor by construction, so those lines never appear. Either way the stock probe for `clojure.core.clj` is kept away from the fork assemblies ([#25](https://github.com/flybot-sg/magic/issues/25)).

The in-repo reproduction is [`unity-examples/magic-unity-coexist`](../unity-examples/magic-unity-coexist): `bb coexist-noise` checks that the dual variant is silent, `bb coexist-noise magic-only` reproduces the noise on the default variant.

## Examples

- [`unity-examples/magic-unity-smoke`](../unity-examples/magic-unity-smoke): standalone IL2CPP regression suite. The reference pattern for `deps.edn` / `dotnet.clj`, with a `MAGIC -> Smoke -> Build & Run IL2CPP` menu. Run by hand on Unity 2022.3.62f3.
- [`unity-examples/magic-unity-coexist`](../unity-examples/magic-unity-coexist): coexistence repro that vendors stock ClojureCLR, driven by `bb coexist-noise`.
