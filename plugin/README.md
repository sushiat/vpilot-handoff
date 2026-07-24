# Handoff plugin

.NET Framework 4.8 vPilot plugin. Builds with plain `dotnet build` (SDK-style project,
`Microsoft.NETFramework.ReferenceAssemblies` — no full Visual Studio/MSBuild install
required). Builds `x64`-only, required by the SimConnect native binary (see below) —
this assumes vPilot's own host process is x64, true for any current vPilot version but
not independently verified against this repo.

## Setup

vPilot's plugin API DLL isn't redistributed here. Before building:

1. Locate `RossCarlson.Vatsim.Vpilot.Plugins.dll` (and its `.xml`) in your vPilot
   install directory.
2. Copy both into `plugin/lib/` (git-ignored).

Ownship telemetry (radio frequency, transponder state) comes from a separate SimConnect
connection via the `CTrue.FsConnect` NuGet package, which bundles the native
`simconnect.dll` and managed `Microsoft.FlightSimulator.SimConnect.dll` itself — restored
automatically on `dotnet build`/`dotnet restore`, no manual setup step needed (unlike the
vPilot DLL above).

All of that package's runtime dependencies (managed and the native `simconnect.dll`) are
woven directly into `Handoff.Plugin.dll` at build time via Costura.Fody, similar in spirit
to a modern .NET single-file publish — vPilot's Plugins folder has no dependency-resolution
mechanism of its own, so a single deployable file avoids relying on one. `FodyWeavers.xml`
holds the weaving config; `costurax64/` (git-ignored, regenerated on every build from the
NuGet package cache) holds the native DLL as an embedded resource source.

## Build

```
dotnet build
```

## Deploy

Copy the built `Handoff.Plugin.dll` (dependencies are embedded, so this is the only file
needed) into `%LOCALAPPDATA%\vPilot\Plugins`.

Set a `VPILOT_PLUGINS_DIR` user environment variable pointing at that Plugins folder (a UNC
path if vPilot runs on a different machine than the dev box), then use the **plugin: deploy**
VS Code task (builds first, then copies). Restart VS Code after setting the env var for the
first time so its integrated terminal picks it up.

Check the Plugins folder for any stray `RossCarlson.Vatsim.Vpilot.Plugins.dll`/`.xml`
copies first — a known FSLabs-installer bug drops these there and breaks plugin loading.
