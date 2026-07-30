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

        // COM TRANSMIT:1/2 -- audio panel transmitter-select state. Normally mutually exclusive
        // (real avionics only let one COM be the transmitter at a time), but that's aircraft
        // behavior, not something this class enforces or assumes -- it just forwards whatever the
        // sim reports. Both false at once is a normal state too (radio/avionics powered off).
        public bool Com1TransmitEnabled { get; }
        public bool Com2TransmitEnabled { get; }

        // COM RECEIVE:1/2 -- audio panel receive-select state, genuinely independent per COM
        // (both true at once == "listening on both", a normal state; both false at once == radio
        // powered off or intentionally muted).
        public bool Com1ReceiveEnabled { get; }
        public bool Com2ReceiveEnabled { get; }

        public DateTimeOffset Timestamp { get; }

        public RadioState(
            int? com1Frequency, int? com2Frequency, int? com1StandbyFrequency, int? com2StandbyFrequency,
            bool modeCEnabled, int? transponderCode,
            bool com1TransmitEnabled, bool com2TransmitEnabled, bool com1ReceiveEnabled, bool com2ReceiveEnabled,
            DateTimeOffset timestamp)
        {
            Com1Frequency = com1Frequency;
            Com2Frequency = com2Frequency;
            Com1StandbyFrequency = com1StandbyFrequency;
            Com2StandbyFrequency = com2StandbyFrequency;
            ModeCEnabled = modeCEnabled;
            TransponderCode = transponderCode;
            Com1TransmitEnabled = com1TransmitEnabled;
            Com2TransmitEnabled = com2TransmitEnabled;
            Com1ReceiveEnabled = com1ReceiveEnabled;
            Com2ReceiveEnabled = com2ReceiveEnabled;
            Timestamp = timestamp;
        }
    }
}
