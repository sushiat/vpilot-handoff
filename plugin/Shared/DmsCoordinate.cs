using System;
using System.Globalization;

namespace Handoff.Plugin
{
    /// <summary>
    /// Converts VATGlasses' fixed-width DMS coordinate strings to decimal degrees. Confirmed
    /// format (see VatGlassesDataClientTests' fixture, data/lo.json): latitude is 6 digits
    /// DDMMSS (e.g. "475026" = 47*50'26"), longitude is 7 digits DDDMMSS (e.g. "0124429" =
    /// 012*44'29"). An optional leading '-' marks southern latitude / western longitude --
    /// no confirmed negative sample seen yet, handled defensively; verify against a real
    /// southern-hemisphere region file if/when one surfaces a surprise here, same as issue #9
    /// phase 1's other "confirmed against a live file" schema notes.
    /// </summary>
    public static class DmsCoordinate
    {
        public static double ToDecimalDegrees(string dms)
        {
            if (string.IsNullOrEmpty(dms)) throw new FormatException("DMS coordinate is null or empty.");

            var negative = dms[0] == '-';
            var digits = negative ? dms.Substring(1) : dms;

            // Last 4 digits are always MMSS; whatever remains in front is the degree part
            // (2 digits for latitude, 3 for longitude) -- deriving it from length rather than
            // a separate isLongitude flag means this works the same regardless of which axis
            // it's called for, and rejects any string that isn't actually DMS-shaped.
            if (digits.Length < 5 || !IsAllDigits(digits))
            {
                throw new FormatException($"'{dms}' is not a valid DMS coordinate.");
            }

            var degreeDigits = digits.Length - 4;
            var degrees = int.Parse(digits.Substring(0, degreeDigits), CultureInfo.InvariantCulture);
            var minutes = int.Parse(digits.Substring(degreeDigits, 2), CultureInfo.InvariantCulture);
            var seconds = int.Parse(digits.Substring(degreeDigits + 2, 2), CultureInfo.InvariantCulture);

            var decimalDegrees = degrees + minutes / 60.0 + seconds / 3600.0;
            return negative ? -decimalDegrees : decimalDegrees;
        }

        private static bool IsAllDigits(string s)
        {
            foreach (var c in s)
            {
                if (c < '0' || c > '9') return false;
            }
            return true;
        }
    }
}
