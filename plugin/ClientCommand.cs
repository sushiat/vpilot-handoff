namespace Handoff.Plugin
{
    /// <summary>
    /// Wire format for client -&gt; server WebSocket messages (docs/protocol.md). One flat
    /// envelope rather than a polymorphic hierarchy -- same reasoning as RadioIpcMessage: a
    /// small, fixed message set makes a discriminated Type field with optional fields simpler
    /// than a JSON converter for a handful of subtypes.
    /// </summary>
    public sealed class ClientCommand
    {
        public const string TypeSendPrivateMessage = "sendPrivateMessage";
        public const string TypeSendRadioMessage = "sendRadioMessage";
        public const string TypeSetCom1Frequency = "setCom1Frequency";
        public const string TypeSetCom2Frequency = "setCom2Frequency";
        public const string TypeSetCom1StandbyFrequency = "setCom1StandbyFrequency";
        public const string TypeSetCom2StandbyFrequency = "setCom2StandbyFrequency";
        public const string TypeSetTransponderCode = "setTransponderCode";

        public string Type { get; set; }

        // sendPrivateMessage
        public string To { get; set; }

        // sendPrivateMessage / sendRadioMessage
        public string Message { get; set; }

        // setCom1Frequency / setCom2Frequency / setCom1StandbyFrequency /
        // setCom2StandbyFrequency -- plain MHz, not the compressed-integer format used
        // everywhere else in the protocol (see docs/protocol.md for why).
        public double? Megahertz { get; set; }

        // setTransponderCode -- plain decimal squawk (e.g. 1200), not BCD.
        public int? TransponderCode { get; set; }
    }
}
