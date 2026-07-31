using System;
using System.Collections.Generic;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Resolves a VATGlasses ownership chain (a sector's Owner list, or an airport's Topdown
    /// list) to whichever controller(s) in it are actually online right now. See issue #9 phase 2.
    /// </summary>
    public static class VatGlassesOwnershipResolver
    {
        /// <summary>
        /// Walks every position in the chain, matching each against online controllers by
        /// callsign-prefix + parsed tier (not frequency -- VATGlasses positions don't reliably
        /// carry the same compressed-frequency format IBroker/the data feed use, but callsign
        /// prefix + type is the same signal ControllerRankingModel.RouteMatched/
        /// ParseControllerTier already trust elsewhere) -- and returns every distinct online
        /// controller found, not just the first.
        ///
        /// This matters because a chain's positions are frequently NOT distinguishable from each
        /// other by prefix+tier alone: real VATGlasses data has entire groups of same-FIR CTR
        /// positions (e.g. Sweden Control's M2/M4/M5/M6/M7/M8/MY all share prefix "ESMM" and type
        /// "CTR") with no per-position callsign-suffix or frequency field reliable enough to tell
        /// them apart. When more than one such position is genuinely online at once (a busy FIR
        /// splitting into several simultaneously-staffed sub-sectors is normal, not an edge case),
        /// a single "first match wins" resolution can silently return the wrong one -- confirmed
        /// against a real flight-test bug (issue #17: ranked ESMM_5_CTR when ESMM_7_CTR was
        /// correct, with both actually online). Rather than trying to disambiguate further,
        /// callers treat every returned controller as an equally-plausible candidate and let the
        /// existing IsNext/IsLikelyNext tie-detection (docs/controller-ranking.md) surface all of
        /// them when there's more than one -- confident IsNext still falls out naturally once only
        /// one candidate resolves.
        /// </summary>
        public static IReadOnlyList<HandoffController> ResolveOnlineControllers(
            IReadOnlyList<string> chain,
            IReadOnlyDictionary<string, VatGlassesPosition> positions,
            IReadOnlyCollection<HandoffController> onlineControllers)
        {
            if (chain == null) return Array.Empty<HandoffController>();

            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var result = new List<HandoffController>();

            foreach (var positionId in chain.Where(positions.ContainsKey))
            {
                var position = positions[positionId];
                var expectedTier = ParsePositionTier(position.Type);
                if (!expectedTier.HasValue) continue;
                var tier = expectedTier.Value;

                foreach (var c in onlineControllers.Where(c =>
                    c.Callsign.ParseControllerTier() == tier &&
                    position.Prefixes.Any(prefix => c.Callsign.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))))
                {
                    if (seen.Add(c.Callsign)) result.Add(c);
                }
            }

            return result;
        }

        private static ControllerTier? ParsePositionTier(string type)
        {
            switch ((type ?? string.Empty).ToUpperInvariant())
            {
                case "DEL": return ControllerTier.Delivery;
                case "GND": return ControllerTier.Ground;
                case "TWR": return ControllerTier.Tower;
                case "APP":
                case "DEP": return ControllerTier.AppDep;
                case "CTR": return ControllerTier.Center;
                default: return null;
            }
        }
    }
}
