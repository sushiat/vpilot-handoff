# Handoff

A companion app for [vPilot](https://vpilot.rosscarlson.dev/) that surfaces the online VATSIM controller list and two-way chat on a second screen (built for use as an Android EFB alongside other EFB apps, like charts or performance tools, in a home cockpit).

Unlike push-notification relay plugins, Handoff runs a local server inside the vPilot plugin process and talks to a native Android client over your LAN — full controller list, two-way private/radio chat, and phase-of-flight-aware prioritization of which controller you should be talking to next.

## Structure

- `plugin/` — C# vPilot plugin (.NET Framework 4.8), implements `IPlugin`, tracks controller/chat state via `IBroker`, embeds a local HTTP/WebSocket server
- `android/` — native Kotlin Android app, WebSocket client + foreground service + floating overlay for glanceable alerts
- `docs/protocol.md` — the WebSocket API contract between plugin and client(s); treat this as the source of truth if you're building an alternate client (e.g. iOS)

## Status

Early development. Not yet functional.

## License

MIT — see [LICENSE](LICENSE).
