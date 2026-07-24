# Handoff plugin

.NET Framework 4.8 vPilot plugin. Builds with plain `dotnet build` (SDK-style project,
`Microsoft.NETFramework.ReferenceAssemblies` — no full Visual Studio/MSBuild install
required).

## Setup

vPilot's plugin API DLL isn't redistributed here. Before building:

1. Locate `RossCarlson.Vatsim.Vpilot.Plugins.dll` (and its `.xml`) in your vPilot
   install directory.
2. Copy both into `plugin/lib/` (git-ignored).

## Build

```
dotnet build
```

## Deploy

Copy the built `Handoff.Plugin.dll` into `%LOCALAPPDATA%\vPilot\Plugins`. Check that
folder for any stray `RossCarlson.Vatsim.Vpilot.Plugins.dll`/`.xml` copies first — a
known FSLabs-installer bug drops these there and breaks plugin loading.
