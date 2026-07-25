using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Great-circle distance between two lat/lon points, used as the last-resort tiebreak when
    /// ranking controllers with no route match (see ControllerRankingModel).
    /// </summary>
    public static class GeoDistance
    {
        private const double EarthRadiusNauticalMiles = 3440.065;

        public static double NauticalMiles(double lat1, double lon1, double lat2, double lon2)
        {
            var dLat = ToRadians(lat2 - lat1);
            var dLon = ToRadians(lon2 - lon1);
            var a = Math.Sin(dLat / 2) * Math.Sin(dLat / 2) +
                    Math.Cos(ToRadians(lat1)) * Math.Cos(ToRadians(lat2)) *
                    Math.Sin(dLon / 2) * Math.Sin(dLon / 2);
            var c = 2 * Math.Atan2(Math.Sqrt(a), Math.Sqrt(1 - a));
            return EarthRadiusNauticalMiles * c;
        }

        private static double ToRadians(double degrees) => degrees * Math.PI / 180.0;
    }
}
