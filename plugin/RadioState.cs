using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Immutable snapshot of ownship radio state, as last read from SimConnect.
    /// </summary>
    public sealed class RadioState
    {
        // vPilot compressed-integer format, matching Controller.Frequency. Null until the
        // first SimConnect read completes.
        public int? Com1Frequency { get; }
        public int? Com2Frequency { get; }

        // TRANSPONDER STATE:1 == Alt (4).
        public bool ModeCEnabled { get; }

        public DateTimeOffset Timestamp { get; }

        public RadioState(int? com1Frequency, int? com2Frequency, bool modeCEnabled, DateTimeOffset timestamp)
        {
            Com1Frequency = com1Frequency;
            Com2Frequency = com2Frequency;
            ModeCEnabled = modeCEnabled;
            Timestamp = timestamp;
        }
    }
}
