using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Converts a squawk code between plain decimal (1200) and the BCD16 encoding SimConnect's
    /// "TRANSPONDER CODE:1" var and the "XPNDR_SET" client event both use -- one hex nibble per
    /// digit (1200 -> 0x1200), the same packing scheme as vPilot's own frequency format, just
    /// four digits instead of five.
    /// </summary>
    public static class TransponderCode
    {
        public static int ToBcd(int squawk)
        {
            ValidateSquawkRange(squawk);
            var bcd = 0;
            var value = squawk;
            for (var nibble = 0; nibble < 4; nibble++)
            {
                bcd |= (value % 10) << (nibble * 4);
                value /= 10;
            }
            return bcd;
        }

        public static int FromBcd(int bcd)
        {
            var squawk = 0;
            var multiplier = 1;
            for (var nibble = 0; nibble < 4; nibble++)
            {
                var digit = (bcd >> (nibble * 4)) & 0xF;
                squawk += digit * multiplier;
                multiplier *= 10;
            }
            return squawk;
        }

        /// <summary>
        /// Genuine system boundary: the value ultimately originates from outside the plugin
        /// (the tablet, eventually), same reasoning as RadioFrequency.ValidateAirbandRange.
        /// </summary>
        public static void ValidateSquawkRange(int squawk)
        {
            if (squawk < 0 || squawk > 7777 || HasDigitAboveSeven(squawk))
            {
                throw new ArgumentOutOfRangeException(nameof(squawk), squawk, "Squawk code must be a 4-digit code with each digit between 0 and 7.");
            }
        }

        private static bool HasDigitAboveSeven(int squawk)
        {
            var value = squawk;
            while (value > 0)
            {
                if (value % 10 > 7) return true;
                value /= 10;
            }
            return false;
        }
    }
}
