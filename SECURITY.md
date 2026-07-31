# Security Policy

## Reporting a vulnerability

Please **don't** open a public issue for a security vulnerability. Instead, use
GitHub's private reporting: go to the [Security tab](../../security/advisories/new)
and click "Report a vulnerability". That opens a private conversation with the
maintainer only, so a fix can land before the details are public.

If you'd rather not use GitHub for this, email sushi@opensky.to instead.

## Scope

Handoff is a home-cockpit hobby project with no cloud backend or user accounts --
the plugin and Android app talk directly to each other over your own LAN, paired
with a token exchanged once on first connect (see `docs/protocol.md`). Relevant
things to report: anything that lets a device bypass pairing, read/send chat or
radio commands without authenticating, or otherwise reach the plugin's WebSocket
server without having gone through pairing first.

## Supported versions

Only the latest release is supported. This is a pre-1.0 project under active
development -- there's no backport/patch process for older versions.
