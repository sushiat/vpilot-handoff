# Handoff plugin

.NET Framework 4.8 vPilot plugin. Builds with plain `dotnet build` (SDK-style project,
`Microsoft.NETFramework.ReferenceAssemblies` — no full Visual Studio/MSBuild install
required).

## Setup

vPilot's plugin API DLL isn't redistributed here. Before building:

1. Locate `RossCarlson.Vatsim.Vpilot.Plugins.dll` (and its `.xml`) in your vPilot
   install directory.
2. Copy both into `plugin/lib/` (git-ignored).

## Two processes: the plugin, and the SimConnect helper

Ownship telemetry (radio frequency, transponder state) needs a separate SimConnect
connection — `IBroker` has no ownship telemetry at all. That connection lives in its own
process, `Handoff.RadioHost/` (built and deployed separately, see below), not inside the
plugin DLL itself. This is a direct consequence of a real, confirmed constraint:

- vPilot itself runs as an **x86 (32-bit)** process — confirmed by inspecting `vPilot.exe`'s
  PE header directly against a real install (`0x014C`), which was a genuine surprise since
  MSFS itself is 64-bit-only.
- The `CTrue.FsConnect` NuGet package (a modern, actively-maintained managed wrapper around
  SimConnect) bundles a native `simconnect.dll` that's **x64-only** — confirmed the same way
  (`0x8664`). An x64 native DLL cannot be loaded by an x86 process, full stop.
- vPilot ships and uses its own x86 SimConnect pair, but it's a 2007-era legacy FSX assembly
  (`Microsoft.FlightSimulator.SimConnect.dll` v10.0.61259.0) — a completely different,
  incompatible SDK generation from `CTrue.FsConnect`'s, and not something this project
  controls or should depend on (borrowed from vPilot's own install, could change or
  disappear across vPilot versions).
- The official MSFS 2024 SDK's own `SimConnect.dll` is *also* x64-only (yet a third,
  mutually-incompatible SDK generation) — Microsoft doesn't ship x86 native SimConnect
  binaries anymore at all, since the sim itself is 64-bit-only.

So there is no way to talk to SimConnect via a modern, actively-maintained wrapper from
*inside* vPilot's x86 process. `Handoff.RadioHost` is a small x64 console app that owns the
`CTrue.FsConnect` connection instead; the plugin talks to it over a local named pipe
(`RadioStateModel.cs` on the plugin side, `Program.cs` on the helper side; shared
wire-format/pure-logic code lives in `plugin/Shared/`).

Its lifecycle is tied to the VATSIM connection, not the plugin's own load lifetime (`IPlugin`
has no unload hook at all, so that's the only clean way to actually stop it): `HandoffPlugin`
calls `RadioStateModel.Start()` on `IBroker.NetworkConnected`, which spawns
`Handoff.RadioHost.exe` as a child process (with a check-before-spawn against its well-known
named pipe, so reconnecting doesn't stack up duplicates), and `Stop()` on
`NetworkDisconnected`/`SessionEnded`, which kills it by process name — matches the pattern
vPilot-Pushover uses. Radio state isn't needed before you're connected anyway.

## Build

```
dotnet build Handoff.Plugin.csproj
dotnet build Handoff.RadioHost/Handoff.RadioHost.csproj
```

(The **plugin: build** VS Code task builds both.)

## Deploy

Two things need copying into `%LOCALAPPDATA%\vPilot\Plugins`:

1. `Handoff.Plugin.dll` **and its dependency DLLs** (`Newtonsoft.Json.dll`, `Fleck.dll`) —
   no longer single-file since Costura.Fody was dropped (it existed only to bundle
   SimConnect's native DLL, which now lives entirely in `Handoff.RadioHost` instead). vPilot's
   plugin-folder scan is fine with extra non-plugin DLLs sitting alongside
   `Handoff.Plugin.dll` directly (it just skips ones with no `IPlugin` type, same as it
   already does for its own dependencies) — no subfolder needed here, unlike RadioHost below.
2. `Handoff.RadioHost`'s whole build output folder, into a `RadioHost\` subfolder — as a
   subfolder specifically, so vPilot's plugin-folder scan doesn't trip over
   `CTrue.FsConnect.dll`/`Newtonsoft.Json.dll`/etc. sitting there as stray non-plugin DLLs.

Set a `VPILOT_PLUGINS_DIR` user environment variable pointing at that Plugins folder (a UNC
path if vPilot runs on a different machine than the dev box), then use the **plugin: deploy**
VS Code task (builds first, then copies both). Restart VS Code after setting the env var for
the first time so its integrated terminal picks it up.

Check the Plugins folder for any stray `RossCarlson.Vatsim.Vpilot.Plugins.dll`/`.xml`
copies first — a known FSLabs-installer bug drops these there and breaks plugin loading.

## Debugging

vPilot doesn't show plugin `PostDebugMessage` output anywhere by default. Launch it with the
`/dbgwin` command-line switch to open a debug window that does. Never attach an actual
debugger to vPilot (or any VATSIM client) while it's connected to the network — offline only.
