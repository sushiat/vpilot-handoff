using System.Runtime.CompilerServices;

// Lets Handoff.Plugin.Tests exercise internal-only helpers (e.g. PortOwnerLookup, issue #98)
// directly instead of only through their public callers.
[assembly: InternalsVisibleTo("Handoff.Plugin.Tests")]
