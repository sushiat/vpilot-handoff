using System;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// VATSIM's top-down ATC chain: DEL -&gt; GND -&gt; TWR -&gt; APP/DEP -&gt; CTR. APP and DEP share a
    /// single tier -- VATSIM's own data feed doesn't distinguish them either (both are facility
    /// code 5), only the callsign convention does, and they don't compete with each other for
    /// priority. Ordinal values double as the chain's sort order; <see cref="Other"/> (OBS/FSS/
    /// unrecognized suffixes) always sorts last.
    /// </summary>
    public enum ControllerTier
    {
        Delivery = 0,
        Ground = 1,
        Tower = 2,
        AppDep = 3,
        Center = 4,
        Other = 5
    }

    public static class ControllerTierExtensions
    {
        /// <summary>
        /// Classifies a callsign by its last underscore-delimited token (e.g. "LOWW_TWR" -&gt;
        /// Tower). Handles split frequencies at large airports (e.g. "LOWW_N_GND") the same way,
        /// since only the final token is examined.
        /// </summary>
        public static ControllerTier ParseControllerTier(this string callsign)
        {
            if (string.IsNullOrEmpty(callsign)) return ControllerTier.Other;

            var suffix = callsign.Split('_').Last();
            switch (suffix.ToUpperInvariant())
            {
                case "DEL": return ControllerTier.Delivery;
                case "GND": return ControllerTier.Ground;
                case "TWR": return ControllerTier.Tower;
                case "APP":
                case "DEP": return ControllerTier.AppDep;
                case "CTR": return ControllerTier.Center;
                default: return ControllerTier.Other;
            }
        }
    }
}
