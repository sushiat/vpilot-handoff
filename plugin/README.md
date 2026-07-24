# Handoff plugin

.NET Framework 4.8 vPilot plugin. Builds with plain `dotnet build` (SDK-style project,
`Microsoft.NETFramework.ReferenceAssemblies` — no full Visual Studio/MSBuild install
required). Builds `x64`-only, required by the SimConnect native binary (see below) --
this assumes vPilot's own host process is x64, true for any current vPilot version but
not independently verified against this repo.

## Setup

vPilot's plugin API DLL isn't redistributed here. Before building:

1. Locate `RossCarlson.Vatsim.Vpilot.Plugins.dll` (and its `.xml`) in your vPilot
   install directory.
2. Copy both into `plugin/lib/` (git-ignored).

Ownship telemetry (radio frequency, transponder state) comes from a separate SimConnect
connection via the `CTrue.FsConnect` NuGet package, which bundles the native
`simconnect.dll` and managed `Microsoft.FlightSimulator.SimConnect.dll` itself -- restored
automatically on `dotnet build`/`dotnet restore`, no manual setup step needed (unlike the
vPilot DLL above).

## Build

```
dotnet build
```

## Deploy

Copy the built `Handoff.Plugin.dll` into `%LOCALAPPDATA%\vPilot\Plugins`. Check that
folder for any stray `RossCarlson.Vatsim.Vpilot.Plugins.dll`/`.xml` copies first — a
known FSLabs-installer bug drops these there and breaks plugin loading.
