# Handoff

A companion app for [vPilot](https://vpilot.rosscarlson.dev/) that puts the online
VATSIM controller list and two-way chat on a second screen — built to run as an
Android EFB alongside your other cockpit apps (charts, performance tools, and the
like) in a home cockpit.

Unlike push-notification relay plugins, Handoff runs a local server inside the vPilot
plugin process and talks to a native Android client over your LAN. That gets you:

- **The full controller list**, live, with two-way private and radio chat you can
  actually reply from on the tablet — not just read.
- **Phase-of-flight-aware prioritization** of who you should be talking to next: the
  currently-tuned controller is pinned, then same-category peers, then the next
  station up the standard delivery→ground→tower→approach/departure→center chain —
  so the controller that actually matters right now is always the one that stands out.
- **A glanceable floating overlay** for incoming messages and highlighted controllers,
  so you don't have to keep the app in the foreground to notice something needs your
  attention.

## Structure

- `plugin/` — C# vPilot plugin (.NET Framework 4.8), implements `IPlugin`, tracks
  controller/chat state via `IBroker`, embeds a local HTTP/WebSocket server
- `android/` — native Kotlin Android app, WebSocket client + foreground service +
  floating overlay for glanceable alerts
- `docs/protocol.md` — the WebSocket API contract between plugin and client(s); treat
  this as the source of truth if you're building an alternate client (e.g. iOS)

## Screenshots

*(Coming soon — first-release screenshots are being captured from a real VATSIM
session.)*

## Installation

Two independent pieces to install: the vPilot plugin (Windows) and the Android app.
Both come from the same [GitHub Release](../../releases), tagged together.

### Android

**Recommended: [Obtainium](https://github.com/ImranR98/Obtainium).** Install
Obtainium once, then add this repo as a source (its GitHub URL) — Obtainium finds the
release APK automatically and checks for updates from then on. This avoids repeating
the "allow installs from unknown sources" prompt for every future update.

**Manual alternative:** download the `Handoff-v*.apk` asset directly from this repo's
[Releases page](../../releases) and sideload it. You'll need to allow installs from
unknown sources for whichever app you download it with.

Either way, verify the download against the matching `.sha256` file in the same
release if you want to confirm it wasn't corrupted or tampered with in transit.

### vPilot plugin

1. Download the `Handoff-Plugin-v*.zip` asset from the same [Release](../../releases).
2. Extract it into vPilot's `Plugins` folder. By default that's
   `%LOCALAPPDATA%\vPilot\Plugins`, but vPilot's installer lets you pick a different
   location — check `%LOCALAPPDATA%\vPilot` first, or look up
   `HKEY_CURRENT_USER\Software\vPilot\Install_Dir` in the registry if you're not sure
   where you installed it.
3. Restart vPilot.

Automatic in-app updates for the plugin are planned but not in this release yet —
for now, updating means repeating these steps with the newer release zip. See
`plugin/README.md` for the exact file layout if you're building from source instead.

## Status

Functional — both `plugin/` and `android/` implement the full controller list, chat,
and ranking flow described above. Still early: expect rough edges, and see
`CHANGELOG.md` for what's shipped so far.

## Contributing

Bug reports and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md)
for how to build both components and what to include in a PR.

## License

MIT — see [LICENSE](LICENSE).
