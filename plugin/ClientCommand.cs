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
        // Combined active+standby write in one round trip -- e.g. a "transfer" (activate a
        // just-tuned frequency while preserving whatever was previously active into standby,
        // matching real flip-flop avionics like the G3000 GTC's XFER key) or a plain swap.
        // Avoids the visible ~1s+ gap of sending two separate setComXFrequency/
        // setComXStandbyFrequency commands, each queued and settle-waited independently on
        // Handoff.RadioHost's side -- see RadioStateModel.SetCom1ActiveAndStandbyFrequency.
        public const string TypeSetCom1ActiveAndStandbyFrequency = "setCom1ActiveAndStandbyFrequency";
        public const string TypeSetCom2ActiveAndStandbyFrequency = "setCom2ActiveAndStandbyFrequency";
        public const string TypeSetTransponderCode = "setTransponderCode";
        public const string TypeSelectCom1Transmitter = "selectCom1Transmitter";
        public const string TypeSelectCom2Transmitter = "selectCom2Transmitter";
        public const string TypeSetCom1ReceiveEnabled = "setCom1ReceiveEnabled";
        public const string TypeSetCom2ReceiveEnabled = "setCom2ReceiveEnabled";
        public const string TypeSetSimbriefCredentials = "setSimbriefCredentials";
        public const string TypeRefreshFlightPlan = "refreshFlightPlan";
        public const string TypePinController = "pinController";
        public const string TypeClearPinnedController = "clearPinnedController";
        public const string TypeDismissSelcal = "dismissSelcal";
        public const string TypePing = "ping";
        public const string TypeAuthenticate = "authenticate";

        public string Type { get; set; }

        // sendPrivateMessage
        public string To { get; set; }

        // pinController / clearPinnedController -- sets/clears this specific callsign's pin.
        // Multiple controllers can be pinned at once; each is set/cleared independently, never
        // touching any other pinned callsign.
        // dismissSelcal -- clears that callsign's active SELCAL alert, same as tune-matching it
        // would; independent of the private "contact me" list.
        public string Callsign { get; set; }

        // sendPrivateMessage / sendRadioMessage
        public string Message { get; set; }

        // setCom1Frequency / setCom2Frequency / setCom1StandbyFrequency /
        // setCom2StandbyFrequency -- plain MHz, not the compressed-integer format used
        // everywhere else in the protocol (see docs/protocol.md for why).
        // setCom1ActiveAndStandbyFrequency / setCom2ActiveAndStandbyFrequency -- Megahertz is
        // the new active frequency; StandbyMegahertz (below) the new standby frequency.
        public double? Megahertz { get; set; }

        // setCom1ActiveAndStandbyFrequency / setCom2ActiveAndStandbyFrequency only.
        public double? StandbyMegahertz { get; set; }

        // setTransponderCode -- plain decimal squawk (e.g. 1200), not BCD.
        public int? TransponderCode { get; set; }

        // setCom1ReceiveEnabled / setCom2ReceiveEnabled -- selectCom1Transmitter /
        // selectCom2Transmitter carry no payload of their own.
        public bool? Enabled { get; set; }

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

        // authenticate -- exactly one of Token/PairingCode is normally set (Token for a
        // returning, already-paired client; PairingCode for a client that just read a code off
        // HandoffPairingWindow), or neither ("I have nothing yet, tell me what you need").
        // DeviceId is optional, sent alongside either -- see docs/protocol.md.
        public string Token { get; set; }
        public string PairingCode { get; set; }
        public string DeviceId { get; set; }
    }
}
