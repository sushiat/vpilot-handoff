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
        public const string TypeSetSimbriefCredentials = "setSimbriefCredentials";
        public const string TypeRefreshFlightPlan = "refreshFlightPlan";
        public const string TypePinController = "pinController";
        public const string TypeClearPinnedController = "clearPinnedController";
        public const string TypePing = "ping";

        public string Type { get; set; }

        // sendPrivateMessage
        public string To { get; set; }

        // pinController -- forces this callsign to rank 0 / isCurrent regardless of tuned
        // frequency, until clearPinnedController or the controller goes offline. Not used by
        // clearPinnedController, which carries no fields of its own.
        public string Callsign { get; set; }

        // sendPrivateMessage / sendRadioMessage
        public string Message { get; set; }

        // setCom1Frequency / setCom2Frequency / setCom1StandbyFrequency /
        // setCom2StandbyFrequency -- plain MHz, not the compressed-integer format used
        // everywhere else in the protocol (see docs/protocol.md for why).
        public double? Megahertz { get; set; }

        // setTransponderCode -- plain decimal squawk (e.g. 1200), not BCD.
        public int? TransponderCode { get; set; }

        // setSimbriefCredentials -- SimBrief user ID and/or username, persisted by the plugin
        // (overwriting whatever was persisted before) so future startups, and bare
        // refreshFlightPlan triggers, can fetch without the Android app needing to resend
        // them. ID takes priority over username at fetch time; username is a fallback if the
        // ID is blank or its fetch fails. refreshFlightPlan itself carries no fields -- it
        // just fetches with whatever is currently persisted.
        public string SimbriefUserId { get; set; }
        public string SimbriefUsername { get; set; }

        // ping -- client-supplied timestamp (epoch milliseconds), echoed back unchanged on the
        // pong reply so the client can measure round-trip latency itself; the plugin does not
        // interpret this value.
        public long? ClientTimestamp { get; set; }
    }
}
