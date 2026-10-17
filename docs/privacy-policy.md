# Handoff privacy policy

Last updated: 2026-10-16

Handoff is a companion app for the vPilot flight-simulation client. It shows live VATSIM
air-traffic-controller information and lets you chat, over your own local network, through a plugin
you install on your own PC.

## What the app collects

**The developer does not collect, receive or store any personal data.** The app has no user
accounts, no analytics, no advertising and no crash-reporting service. Nothing about you or your use
of the app is sent to the developer.

## What stays on your device

The app stores, on your device only: the address of your plugin, a pairing credential for your
plugin, your SimBrief user id or username if you enter one (it is passed to your own plugin, which
does the SimBrief lookup), and display preferences such as theme. Uninstalling the app removes them.

## What the app sends

- **To your own PC (local network):** chat messages you type and commands you give (for example
  tuning a radio or pinning a controller), over a TLS-encrypted connection to your own plugin.
- **To third parties:**
  - `api.viffsys.com`: a public lookup of departure/CDM information. It sends your flight's
    callsign (as filed on VATSIM) to fetch your own departure slot status, and requests a public
    list of airports without sending anything about you.
  - The optional "VDGS" panel opens the website `vdgs.vatsimspain.es` inside the app. You log in
    there yourself with VATSIM; the app does not see or store those credentials, and that site's
    own privacy policy applies to it. The panel is a normal in-app browser, so that site's login
    cookie is kept on your device between openings; clearing the app's data removes it.
  - Like any internet request, these reveal your device's IP address to those servers.

## Permissions

The app asks for permissions only to do its job: network access, local-network access to reach your
plugin, notifications (controller requests and chat messages), a foreground service to keep the
connection alive in the background, and, optionally, "display over other apps" so the chat can float
over another app in split-screen. It does not read your contacts, location, camera, microphone or
files.

## Children

The app is not directed at children.

## Changes

If this policy changes, the new version will be published at the same address with an updated date.

## Contact

sushi@opensky.to
