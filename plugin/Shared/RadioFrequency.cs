using System;

namespace Handoff.Plugin
{
    public static class RadioFrequency
    {
        private const double AirbandMinMhz = 118.000;
        private const double AirbandMaxMhz = 136.990;

        /// <summary>
        /// Converts a COM frequency read from SimConnect in MHz (e.g. 123.725) into vPilot's
        /// compressed-integer format (23725), the same format IBroker's Controller.Frequency
        /// already uses -- so the two are directly comparable without another conversion step.
        /// </summary>
        public static int ToVatsimCompressed(double megahertz) =>
            (int)Math.Round(megahertz * 1000) - 100000;

        /// <summary>
        /// Guards a frequency value against the civil VHF airband before it's sent onward as a
        /// write -- both by the plugin (fail fast before an IPC round trip) and, authoritatively,
        /// by the SimConnect host process before it touches SimConnect. Genuine system boundary:
        /// the value ultimately originates from outside the plugin (the tablet, eventually).
        /// </summary>
        public static void ValidateAirbandRange(double megahertz)
        {
            if (megahertz < AirbandMinMhz || megahertz > AirbandMaxMhz)
            {
                throw new ArgumentOutOfRangeException(nameof(megahertz), megahertz, $"Frequency must be between {AirbandMinMhz:F3} and {AirbandMaxMhz:F3} MHz.");
            }
        }
    }
}
