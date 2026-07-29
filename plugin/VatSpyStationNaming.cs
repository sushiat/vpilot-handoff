using System;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Composes RankedController.StationName (e.g. "Bremen Radar" for EDWW_N_CTR) from vatspy
    /// place names plus a small suffix-by-tier-and-region table -- issue #11's explicit scoping
    /// ("a handful of suffix-by-tier-and-region rules," not per-airport hardcoding). DEL/GND/TWR/
    /// APP/DEP suffixes are fixed words; only CTR varies meaningfully by region (Center/Centre/
    /// Control/Radar/...), and that variation is already published in VATSpy.dat's `[Countries]`
    /// section's 3rd column -- keyed by 2-letter ICAO prefix, e.g. "LO"|"Radar" for Austria's
    /// German-speaking FIRs, blank meaning "use the default word." No hand-maintained table needed
    /// for that part; vatspy already carries it.
    /// </summary>
    public static class VatSpyStationNaming
    {
        private const string DefaultCtrSuffix = "Center";

        /// <summary>
        /// Null if the callsign's ICAO prefix isn't in vatspy's data or the tier has no defined
        /// suffix (matches RankedController.StationName's existing doc-comment contract).
        /// </summary>
        public static string ComposeDisplayName(string callsign, VatSpyDataModel vatSpyData)
        {
            if (string.IsNullOrEmpty(callsign) || vatSpyData == null) return null;

            var tier = callsign.ParseControllerTier();
            var icaoPrefix = callsign.Split('_')[0];

            string place;
            string suffix;

            if (tier == ControllerTier.Center)
            {
                // Prefer the longest (most specific) matching prefix -- a sub-divided FIR's own
                // sub-position prefix (e.g. "EDWW_ALR") is always longer than its parent FIR's
                // plain top-level prefix ("EDWW"), and both can legitimately StartsWith-match the
                // same online callsign.
                var boundary = vatSpyData.FirBoundaries
                    .Where(b => b.CallsignPrefixes.Any(p => p.Length > 0 && callsign.StartsWith(p, StringComparison.OrdinalIgnoreCase)))
                    .OrderByDescending(b => b.CallsignPrefixes.Where(p => callsign.StartsWith(p, StringComparison.OrdinalIgnoreCase)).Max(p => p.Length))
                    .FirstOrDefault();
                if (boundary == null) return null;

                place = ExtractPlaceName(boundary.Name);
                suffix = DefaultCtrSuffix;
                var countryPrefix = icaoPrefix.Length >= 2 ? icaoPrefix.Substring(0, 2) : icaoPrefix;
                if (vatSpyData.CtrSuffixByCountryPrefix.TryGetValue(countryPrefix, out var countrySuffix) && !string.IsNullOrEmpty(countrySuffix))
                {
                    suffix = countrySuffix;
                }
            }
            else
            {
                suffix = AirportTierSuffix(callsign, tier);
                if (suffix == null) return null;
                if (!vatSpyData.AirportsByIcao.TryGetValue(icaoPrefix, out var airport)) return null;
                place = airport.Name;
            }

            if (string.IsNullOrEmpty(place)) return null;
            return place.EndsWith(suffix, StringComparison.OrdinalIgnoreCase) ? place : place + " " + suffix;
        }

        private static string AirportTierSuffix(string callsign, ControllerTier tier)
        {
            switch (tier)
            {
                case ControllerTier.Delivery: return "Delivery";
                case ControllerTier.Ground: return "Ground";
                case ControllerTier.Tower: return "Tower";
                case ControllerTier.AppDep:
                    var token = callsign.Split('_').Last();
                    return string.Equals(token, "DEP", StringComparison.OrdinalIgnoreCase) ? "Departure" : "Approach";
                default: return null;
            }
        }

        /// <summary>
        /// VATSpy.dat FIR names for sub-divided sectors read like "Muenchen ACC (Zugspitze) -
        /// Muenchen" -- the parent place name is the segment after the last " - ". Falls back to
        /// the whole name unchanged when there's no dash (the common case: a plain top-level FIR
        /// row, e.g. "Bremen").
        /// </summary>
        private static string ExtractPlaceName(string firName)
        {
            var idx = firName.LastIndexOf(" - ", StringComparison.Ordinal);
            return idx >= 0 ? firName.Substring(idx + 3) : firName;
        }
    }
}
