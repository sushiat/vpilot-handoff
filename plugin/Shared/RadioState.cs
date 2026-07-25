using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Immutable snapshot of ownship radio state, as last read from SimConnect (via the
    /// Handoff.RadioHost helper process).
    /// </summary>
    public sealed class RadioState
    {
        // vPilot compressed-integer format, matching Controller.Frequency. Null until the
        // first SimConnect read completes.
        public int? Com1Frequency { get; }
        public int? Com2Frequency { get; }
        public int? Com1StandbyFrequency { get; }
        public int? Com2StandbyFrequency { get; }

        // TRANSPONDER STATE:1 == Alt (4).
        public bool ModeCEnabled { get; }

        // Plain decimal squawk (e.g. 1200), not BCD -- that encoding is a SimConnect-boundary
        // detail, converted at the RadioSimConnectClient layer (see TransponderCode).
        public int? TransponderCode { get; }

        public DateTimeOffset Timestamp { get; }

        public RadioState(int? com1Frequency, int? com2Frequency, int? com1StandbyFrequency, int? com2StandbyFrequency, bool modeCEnabled, int? transponderCode, DateTimeOffset timestamp)
        {
            Com1Frequency = com1Frequency;
            Com2Frequency = com2Frequency;
            Com1StandbyFrequency = com1StandbyFrequency;
            Com2StandbyFrequency = com2StandbyFrequency;
            ModeCEnabled = modeCEnabled;
            TransponderCode = transponderCode;
            Timestamp = timestamp;
        }
    }
}
