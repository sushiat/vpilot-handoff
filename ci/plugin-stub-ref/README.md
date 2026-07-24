# stub-ref

CI-only stand-in for `RossCarlson.Vatsim.Vpilot.Plugins.dll` (not redistributed here,
see `../README.md`). Compiles a hand-reconstructed public API surface — interfaces and
event-arg classes only, no implementation — under the same assembly name, so
`Handoff.Plugin.csproj` has something to build against without the real DLL.

Built from `RossCarlson.Vatsim.Vpilot.Plugins.xml`'s doc comments (the real assembly's
own shipped XML docs). Event delegate types (`EventHandler<T>`) follow standard .NET
convention but weren't verified against the real DLL's IL — don't treat this as
authoritative for exact event signatures; check the real assembly (or its `.xml`) when
wiring up event handlers for real.

Not referenced by `Handoff.Plugin.csproj` directly — build this project first and copy
its output over `plugin/lib/RossCarlson.Vatsim.Vpilot.Plugins.dll` (see the `plugin`
job in `.github/workflows/build.yml`).
