using System;
using System.Collections.Generic;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Resolves a VATGlasses ownership chain (a sector's Owner list, or an airport's Topdown
    /// list) to whichever controller in it is actually online right now. See issue #9 phase 2.
    /// </summary>
    public static class VatGlassesOwnershipResolver
    {
        /// <summary>
        /// Walks chain in order; for each position ID, looks it up in positions, then finds an
        /// online controller whose callsign starts with one of the position's Prefixes
        /// (case-insensitive) and whose parsed tier (ParseControllerTier) matches the position's
        /// Type; returns the first such match, or null if nothing in the chain is online.
        /// Matching by callsign-prefix + parsed tier rather than frequency -- VATGlasses
        /// positions don't reliably carry the same compressed-frequency format IBroker/the data
        /// feed use, but callsign prefix + type is the same signal
        /// ControllerRankingModel.RouteMatched/ParseControllerTier already trust elsewhere.
        /// </summary>
        public static HandoffController ResolveOnlineController(
            IReadOnlyList<string> chain,
            IReadOnlyDictionary<string, VatGlassesPosition> positions,
            IReadOnlyCollection<HandoffController> onlineControllers)
        {
            if (chain == null) return null;

            foreach (var positionId in chain)
            {
                if (!positions.TryGetValue(positionId, out var position)) continue;

                var expectedTier = ParsePositionTier(position.Type);
                if (!expectedTier.HasValue) continue;

                var match = onlineControllers.FirstOrDefault(c =>
                    c.Callsign.ParseControllerTier() == expectedTier.Value &&
                    position.Prefixes.Any(prefix => c.Callsign.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)));

                if (match != null) return match;
            }

            return null;
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
