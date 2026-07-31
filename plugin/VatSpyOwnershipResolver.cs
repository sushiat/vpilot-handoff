using System;
using System.Collections.Generic;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Resolves a vatspy FIR boundary's callsign prefixes to whichever CTR controller(s) are
    /// actually online right now. Deliberately simpler than VatGlassesOwnershipResolver -- a
    /// vatspy boundary has no position-type/owner-chain concept to walk (it's inherently
    /// CTR/enroute-level data, see docs/controller-ranking.md), just a flat prefix list. See issue
    /// #11.
    /// </summary>
    public static class VatSpyOwnershipResolver
    {
        /// <summary>
        /// Every online CTR controller whose callsign prefix-matches any of the boundary's
        /// callsign prefixes -- not just the first, for the identical reason
        /// VatGlassesOwnershipResolver.ResolveOnlineControllers returns every match (see its own
        /// doc comment): several simultaneously-online CTR positions can share one FIR/prefix
        /// (e.g. a busy FIR split into sub-sectors), and guessing which one is "right" has already
        /// caused a real flight-test bug for VATGlasses (issue #17) -- callers feed every returned
        /// candidate into the same IsNext/IsLikelyNext tie-detection instead.
        /// </summary>
        public static IReadOnlyList<HandoffController> ResolveOnlineControllers(
            VatSpyFirBoundary boundary,
            IReadOnlyCollection<HandoffController> onlineControllers)
        {
            if (boundary == null) return Array.Empty<HandoffController>();

            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var result = new List<HandoffController>();

            foreach (var c in onlineControllers.Where(c =>
                c.Callsign.ParseControllerTier() == ControllerTier.Center &&
                boundary.CallsignPrefixes.Any(prefix => c.Callsign.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))))
            {
                if (seen.Add(c.Callsign)) result.Add(c);
            }

            return result;
        }
    }
}
