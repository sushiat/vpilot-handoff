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

        /// <summary>Initial great-circle bearing (0-360, true north) from point 1 to point 2 -- used to tell whether ownship's heading is converging on a station (see ControllerRankingModel's "approaching" flag).</summary>
        public static double InitialBearingDegrees(double lat1, double lon1, double lat2, double lon2)
        {
            var lat1Rad = ToRadians(lat1);
            var lat2Rad = ToRadians(lat2);
            var deltaLon = ToRadians(lon2 - lon1);

            var y = Math.Sin(deltaLon) * Math.Cos(lat2Rad);
            var x = Math.Cos(lat1Rad) * Math.Sin(lat2Rad) - Math.Sin(lat1Rad) * Math.Cos(lat2Rad) * Math.Cos(deltaLon);
            var bearing = Math.Atan2(y, x) * 180.0 / Math.PI;
            return (bearing + 360.0) % 360.0;
        }

        /// <summary>Smallest absolute difference between two compass headings, 0-180 (handles the 0/360 wraparound).</summary>
        public static double AngularDifferenceDegrees(double a, double b)
        {
            var diff = Math.Abs(a - b) % 360.0;
            return diff > 180.0 ? 360.0 - diff : diff;
        }

        /// <summary>
        /// Signed along-track distance (nm) of `point`'s projection onto the great-circle course
        /// from `from` toward `courseTo` -- negative if the projection falls behind `from`.
        /// Standard spherical-trig cross-track/along-track formulas (Aviation Formulary). Used by
        /// ControllerRankingModel's abeam-point waypoint sequencing (issue #22) to test whether
        /// ownship is abeam-or-past a waypoint along a given leg course, independent of heading.
        /// </summary>
        public static double AlongTrackDistanceNm(double fromLat, double fromLon, double courseToLat, double courseToLon, double pointLat, double pointLon)
        {
            var angularDistanceToPoint = NauticalMiles(fromLat, fromLon, pointLat, pointLon) / EarthRadiusNauticalMiles;
            var bearingToPoint = ToRadians(InitialBearingDegrees(fromLat, fromLon, pointLat, pointLon));
            var bearingToCourse = ToRadians(InitialBearingDegrees(fromLat, fromLon, courseToLat, courseToLon));

            var crossTrack = Math.Asin(Math.Sin(angularDistanceToPoint) * Math.Sin(bearingToPoint - bearingToCourse));
            var alongTrack = Math.Acos(Math.Cos(angularDistanceToPoint) / Math.Cos(crossTrack));
            var sign = Math.Cos(bearingToPoint - bearingToCourse) >= 0 ? 1.0 : -1.0;

            return alongTrack * EarthRadiusNauticalMiles * sign;
        }

        private static double ToRadians(double degrees) => degrees * Math.PI / 180.0;
    }
}
