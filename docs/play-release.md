# Google Play release

Issue #153. How the Play channel differs from the sideload channel, how to sign, and drafts for
the Play Console paperwork. Anything marked **verify** is from memory of Google's process, not
checked against the live console: read the current wording before relying on it.

## Two channels, one codebase

| | `sideload` flavor | `play` flavor |
|---|---|---|
| Ships via | GitHub Releases, Obtainium, flightsim.to | Google Play |
| Artifact | `Handoff-vX.Y.Z.apk` | `Handoff-vX.Y.Z.aab` |
| Gradle | `assembleSideloadRelease` | `bundlePlayRelease` |
| Switches | every `ChannelFeatures` value `true` | see below |

Same `applicationId` (`at.sushi.handoff`), same `versionCode` (derived from `versionName`), same
signing key, so an install from either channel can update over the other. Both artifacts are built
by `.github/workflows/release.yml` on a `v*` tag and attached to the GitHub release. The AAB is
there so it can be downloaded and uploaded to Play Console by hand.

### The switches (`app/src/<flavor>/java/at/sushi/handoff/ChannelFeatures.kt`)

| Switch | sideload | play | Why |
|---|---|---|---|
| `FullDeviceCapture` | on | **off** | The opt-in "full-device screenshot" in the debug window uses MediaProjection and a `mediaProjection` foreground-service type. Play wants a separate declaration and a demo video for that, and it's only a debug nicety, so it stays out of the first submission. |
| `FloatingOverlay` | on | on | `SYSTEM_ALERT_WINDOW` powers the floating chat in split-screen mode and the debug window. Core to the tablet layout, so kept unless Google refuses it (see below). |
| `GithubUpdateChecks` | on | **off** | A Play-installed app shouldn't point users at another update channel. The Play build skips the GitHub check and just tells the user to update from Google Play. |

Each risky feature is also tied to lines in `app/src/play/AndroidManifest.xml`. **To turn a
feature back on for Play:** flip its switch in `app/src/play/.../ChannelFeatures.kt` and delete its
lines in that manifest. Nothing else.

### If Google refuses `SYSTEM_ALERT_WINDOW`

Planned as a follow-up (not built yet): set `FloatingOverlay = false` for `play`, remove the
permission in the Play manifest overlay, and make split mode use the same in-window chat that narrow
fullscreen already has (chat swaps in over the controller list instead of floating). The debug window
would become an in-window dialog or be hidden in the Play build. The sideload channel keeps the
floating overlay either way.

## Signing: reuse the existing release key

The release keystore already signs the sideload APK (CI secrets `RELEASE_KEYSTORE_*`). The plan is
to keep using it for Play as well, so both channels share one signature and update each other:

1. In Play Console, create the app and start the first release. **Verify:** choose Play App Signing
   and the option to **use an existing app signing key / "export and upload a key from a Java
   keystore"**; Google provides a tool (PEPK) that encrypts the key for upload.
2. Upload the key at that first release. This choice is made once, at the first upload.
3. From then on, upload the CI-built `Handoff-vX.Y.Z.aab` (signed with the same key as the upload
   key, or a separate upload key if you register one).

Trade-offs, stated plainly: Google then holds a copy of the app signing key; and reusing the same
key as the upload key means a leaked CI secret would also be a signing-key compromise (already the
case today for the sideload APK).

## Play Console declarations (drafts)

**Foreground service** (`dataSync`): the app keeps one WebSocket connection to the user's own
vPilot plugin on their local network so controller and chat updates arrive while the app is in the
background (the tablet runs other apps alongside). Without the foreground service Android would
suspend the connection and the pilot would miss incoming controller and chat messages mid-flight.
The notification is shown for the whole time the connection is open.

**Display over other apps** (`SYSTEM_ALERT_WINDOW`): used only to draw the app's own chat panel as a
floating window while the pilot runs the app in split-screen next to another app, and for an
optional debug window. No content from other apps is read. **Verify** whether Play Console asks for
a separate declaration for this permission; I did not find a Play policy page for it.

**Local network** (`ACCESS_LOCAL_NETWORK`, `NEARBY_WIFI_DEVICES`, with `neverForLocation`): the app
connects to a plugin on the user's own PC at an address they configure or pair with. Android 16/17
blocks local-network sockets without these permissions. No location is derived.

**Notifications** (`POST_NOTIFICATIONS`): incoming controller "contact me" requests, SELCAL alerts
and chat messages while the app isn't in front.

## Data safety (draft answers)

The developer collects no data, and the app has no accounts, analytics, advertising or crash
reporting SDKs.

- **Stored on device only:** the plugin's address, SimBrief user id/name (passed to the user's own
  plugin, which does the SimBrief lookup), a pairing credential for the plugin, and display
  preferences.
- **Sent over the local network, to the user's own plugin:** chat messages the user types,
  commands (tune a radio, pin a controller, etc.).
- **Network requests to third parties:** `api.viffsys.com` (public CDM/TOBT data; the departure
  slot lookup carries the flight's VATSIM callsign, the airport list nothing about the user), and
  for the sideload build `api.github.com` (release check, not in the Play
  build). The optional VDGS panel is an embedded WebView of `vdgs.vatsimspain.es`, a third-party
  site where the pilot logs in with VATSIM themselves; the app does not see those credentials, but
  the WebView keeps that site's login cookie on the device between openings.
- **Encryption in transit:** the plugin connection is TLS with a pinned certificate fingerprint
  (see `docs/protocol.md`); third-party requests are HTTPS.
- **Data deletion:** nothing is kept on any server of the developer's; uninstalling removes the
  local data.

**Verify** how the console asks about data that is merely sent to a third party's server as a
side effect of an HTTPS request (IP address), and answer per its wording.

## Review notes (template)

The app is a companion for the VATSIM vPilot client and does nothing without it, so reviewers need
help:

> Handoff is a companion for the vPilot flight-simulation client (VATSIM network). It connects to a
> Windows plugin the user installs alongside vPilot, so on its own it shows an empty controller
> list and a "not connected" state. A short screen recording of it working against a live session
> is attached: [link]. To explore the UI without the plugin: [describe, if a demo mode exists].
> The plugin and install steps are in the project repository: https://github.com/sushiat/vpilot-handoff

If there's no demo mode, consider adding one: a reviewer who only sees "not connected" is a
likely rejection.

## Closed testing checklist

New personal developer accounts need a closed test before production access. **Verify** the current
rule in Play Console; as of my last check it was a closed test with at least 12 testers opted in for
14 continuous days (organization accounts reportedly exempt).

- [ ] Recruit 12+ testers with Google accounts (flightsim community), collect their Google emails
- [ ] Create the closed testing track, add the tester list, upload the first AAB
- [ ] Share the opt-in link; keep testers opted in for the full 14 days
- [ ] Apply for production access once the console allows it
- [ ] Fill the store listing: icon (512 px), feature graphic, phone + tablet screenshots, short and
      full description (say plainly that the Windows plugin is required)
- [ ] Privacy policy URL (see `docs/privacy-policy.md`), Data safety form, content rating, target
      audience (not for children)
- [ ] Declare the foreground service, and any permission declarations the console asks for
