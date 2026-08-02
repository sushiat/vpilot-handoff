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

## Install (end users)

Download `Handoff-Setup-vX.Y.Z.exe` from the [latest release](../../releases/latest) and run it —
in non-silent mode it shows the current version's changelog on one page, then installs on Install;
no options to pick, no admin prompt (`PrivilegesRequired=lowest` — the install target is a per-user
folder, same as the `HKCU\Software\vPilot\Install_Dir` registry key it reads to find that folder).
The plugin files themselves go into that Plugins folder; the installer's own bookkeeping (the
`unins000.exe`/`unins000.dat` uninstaller and the display icon) lives in a separate
`%LOCALAPPDATA%\Handoff` folder instead, so the Plugins folder stays clean of anything but the
plugin (issue #79). See `plugin/installer/Handoff-Setup.iss` for the full install logic (Pascal
Script: resolve `Install_Dir`, wait for vPilot to exit if it's running, copy files, write the
auto-update marker next to the plugin DLL).

## Auto-update (issue #34)

Once installed, the plugin checks this repo's GitHub releases for a newer version once at plugin
startup (`PluginUpdateModel.CheckAsync`, its own background thread off `HandoffPlugin.Initialize`
— same pattern as `VatGlassesDataModel`/`VatSpyDataModel`'s startup sync, not tied to VATSIM
connect). Checking at startup rather than on connect is deliberate: that's the moment a pilot
setting up the sim/tablet would actually want to notice and quit to update, not after they've
already committed to a VATSIM session. On finding a newer version, it downloads the same
`Handoff-Setup-*.exe` asset, verifies it against the sha256 GitHub's API already serves per-asset
(`assets[].digest` — no separate `.sha256` file to publish or trust out-of-band; this only catches
a corrupted/truncated download, not a compromised release, since both would come from the same
source), then asks the pilot to confirm via a small local Windows dialog
(`HandoffUpdatePromptWindow` — deliberately not round-tripped through the Android app, since the
check can run before the tablet is even connected/paired for the session). If accepted, it
launches the installer silently (`/VERYSILENT /SUPPRESSMSGBOXES /NORESTART`) and returns — the
installer itself now owns waiting for vPilot to exit and doing the actual file swap.

After a successful upgrade, the installer writes `Plugins\update-applied.json`; the plugin picks
that up on its next load (`PluginUpdateModel.CheckMarker`, called once from `Initialize`, not
network-tied) and reports it through the existing `operationProgress` protocol message (see
`docs/protocol.md`) so a reconnecting Android app sees a one-time "updated to X.Y.Z" notice, then
deletes the marker.

Progress/failure of an in-flight update is also reported through `operationProgress`
(`OperationIdPrefix = "pluginUpdate"`), same mechanism `VatGlassesDataModel`'s startup sync
already uses — no new protocol message type was needed.

## Deploy (dev iteration)

For iterating without building an installer every time, two things still need copying into
`%LOCALAPPDATA%\vPilot\Plugins` (or wherever `HKCU\Software\vPilot\Install_Dir` actually points —
see above):

1. `Handoff.Plugin.dll` **and its dependency DLLs** (`Newtonsoft.Json.dll`, `Fleck.dll`,
   `BouncyCastle.Crypto.dll` — the last one backs `HandoffCertificateStore`'s TLS cert
   generation) — no longer single-file since Costura.Fody was dropped (it existed only to
   bundle SimConnect's native DLL, which now lives entirely in `Handoff.RadioHost` instead).
   vPilot's plugin-folder scan is fine with extra non-plugin DLLs sitting alongside
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

## Building the installer

Requires [Inno Setup](https://jrsoftware.org/isinfo.php) (`choco install innosetup` — same as
`build.yml`/`release.yml` use in CI). Build Release output first, then compile:

```
dotnet build Handoff.Plugin.csproj -c Release -o publish/plugin
dotnet build Handoff.RadioHost/Handoff.RadioHost.csproj -c Release -o publish/plugin/RadioHost
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" /DMyAppVersion=0.1.0 /DSourceDir=publish\plugin installer\Handoff-Setup.iss
```

`release.yml` also passes `/DChangelogFile=<path to a .txt/.rtf>` to fill the installer's changelog
page with the release's notes — in CI an RTF pandoc-renders from the extracted release notes so the
page shows real headings/bullets; a local build without it falls back to the plain-text
`installer\changelog-fallback.txt` (Inno's `InfoBeforeFile` auto-detects RTF vs plain text). The setup/uninstall icon comes from `Assets\handoff.ico`
(rasterized from `Assets\handoff.svg` via Inkscape — regenerate with `inkscape handoff.svg
--export-type=png -w N -h N -o icon_N.png` per size, then repack the `.ico`).

## VATGlasses sector-ranking replay tool (dev-only, not deployed)

`Handoff.ReplayTool/` validates `VatGlassesSectorLookup`'s geometry against real recorded VATSIM
flights, pulled from [vataware.net](https://vataware.net)'s free, no-auth flight history API
(see issue #9). Not part of the plugin/RadioHost deploy — a standalone console app for manual
sanity-checking.

```
dotnet build Handoff.ReplayTool/Handoff.ReplayTool.csproj

# Single flight
Handoff.ReplayTool/bin/Debug/net48/Handoff.ReplayTool.exe <vataware-flight-id> [--route]

# Batch: up to <count> random European airports, one completed flight from each,
# replayed and collated -- writes ReplayTests/<timestamp>/summary.txt (one line per
# flight) plus a full detail file per flight for review. Omitting --seed uses a fresh
# random seed each run (Environment.TickCount) -- pass --seed <n> for a reproducible run.
Handoff.ReplayTool/bin/Debug/net48/Handoff.ReplayTool.exe --random-test <count> [--seed <n>] [--out <dir>]
```

From the repo root, `run-replay-tests.bat` builds and runs the batch mode in one step,
defaulting to 100 flights: `run-replay-tests.bat [count] [--seed <n>] [--out <dir>]`. Output
always lands in `ReplayTests\` at the repo root (git-ignored) regardless of where it's invoked
from.

Find a single flight ID via `https://vataware.net/airports/<ICAO>` (send
`Accept: application/json`, e.g. via `curl`) — arrivals/departures list each flight's ULID.
`--route` uses the filed route's waypoints for lateral approach-prediction instead of
instantaneous heading (falls back to heading if the route can't be resolved -- waypoint lat/lon
resolution from the raw route string isn't implemented, only SimBrief's own `navlog.fix[]` gives
that directly; `--random-test` is heading-only for this same reason, batch mode has no SimBrief
credentials to fetch a real OFP from).

`--random-test` only picks flights that (a) departed within the *current AIRAC cycle* (a fixed,
globally-synchronized 28-day schedule published years in advance -- computed from one confirmed
real effective date, `AiracAnchorDate`, via simple modular arithmetic; see `CurrentAiracCycle`),
so the real-world airspace structure it flew through is reasonably likely to still match today's
cached VATGlasses data, and (b) have actually landed (`arrival_time` in the past) -- checked
directly on each candidate's timestamps rather than trusting vataware's `state` field or which
list (`recent_arrivals`/`recent_departures`) it came from, since both have been observed with
real quirks: `recent_arrivals` returned the exact same ~9-month-stale date across every airport
checked (a site-wide staleness bug, not chance), while `recent_departures` is reliably current
but mostly still-airborne.

Prints the sequence of sector containment/approach-prediction transitions for the flight, to be
cross-checked by eye against the live map at vatglasses.uk. Also self-checks each
approach-prediction against what ownship actually flew into next (no external ground truth
needed for this part — did the sector predicted as "approaching" become the next `IN:` sector?),
printing `[OK]`/`[MISS]` verdicts and a final tally; a `[MISS]` right after a wide gap between
position samples usually just means the predicted sector was briefly transited between samples,
not a real prediction failure — the gap duration is printed alongside each miss to help tell
the two apart. Deliberately geometry-only otherwise: VATSIM's public data feed (and vataware's
archive of it) carries no per-pilot tuned-COM-frequency history — that's only ever broadcast
live via the separate AFV transceivers feed, which nobody archives — so there's no ground truth
available to check ownership-resolution/ranking against (i.e. whether the sector that ends up
"approaching"/"IN:" would actually have anyone online on live VATSIM, only that the
sector/altitude-band geometry itself picks the polygon a human would expect).

## Debugging

vPilot doesn't show plugin `PostDebugMessage` output anywhere by default. Launch it with the
`/dbgwin` command-line switch to open a debug window that does. Never attach an actual
debugger to vPilot (or any VATSIM client) while it's connected to the network — offline only.
