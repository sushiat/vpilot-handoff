namespace Handoff.Plugin
{
    /// <summary>
    /// Converts SimConnect's standard-pressure "PRESSURE ALTITUDE" (29.92"/1013.25hPa
    /// referenced -- i.e. flight-level units) into a QNH-true AMSL altitude, using the sim's
    /// actual local sea-level pressure (not the pilot's altimeter Kohlsman subscale). Needed
    /// because VATGlasses sector bands near the ground are really QNH-altitude-referenced in
    /// the real world, even though VATGlasses expresses them in FL-unit numbers -- see issue #9
    /// phase 2's ControllerRankingModel/VatGlassesSectorLookup integration.
    /// </summary>
    public static class PressureAltitude
    {
        private const double StandardPressureHpa = 1013.25;

        // Standard ~30ft-per-hPa rule of thumb for converting a pressure-altitude delta to a
        // true-altitude delta near sea level.
        private const double FeetPerHpa = 30.0;

        public static double QnhTrueAltitudeFeet(double pressureAltitudeFeet, double seaLevelPressureHpa) =>
            pressureAltitudeFeet - (StandardPressureHpa - seaLevelPressureHpa) * FeetPerHpa;
    }
}
