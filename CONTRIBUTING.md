# Contributing

## Building

This is a monorepo with two independently buildable components:

- **Plugin** (`plugin/`) — C# / .NET Framework 4.8. See [plugin/README.md](plugin/README.md)
  for setup (you'll need vPilot's plugin API DLL, which isn't redistributed here) and
  the build/deploy steps.
- **Android app** (`android/`) — plain Gradle/Kotlin project, buildable with
  `./gradlew assembleDebug` from `android/`. No special setup needed beyond a recent
  JDK and the Android SDK.

CI (`.github/workflows/build.yml`) builds and tests both on every push/PR — that's the
baseline both need to pass. CodeQL also runs on every PR, both as classic code scanning
and the newer code quality analysis — any `ERROR`-severity finding from either blocks
the merge (see the "Protect master" ruleset).

## Workflow

- Branch off `master` per issue, e.g. `feature/issue-<n>-short-description`.
- Add a `CHANGELOG.md` entry under `## [Unreleased]` for any user-visible change,
  referencing the issue number.
- Open a PR against `master`. CI must pass before merge.

## Protocol changes

If you're changing the plugin↔Android WebSocket contract, update
[docs/protocol.md](docs/protocol.md) first — it's the source of truth for message
shapes, ahead of whichever client's source happens to exist first (this matters if a
future iOS client shows up).

## Reporting bugs / requesting features

Open a [GitHub issue](../../issues). For bugs, include your vPilot version, Android
version/device, and (if relevant) which controller/session state triggered it.
