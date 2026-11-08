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
        // Issue #185 -- answers "squawk ident" via IBroker.SquawkIdent; no payload.
        public const string TypeSquawkIdent = "squawkIdent";
        public const string TypeSelectCom1Transmitter = "selectCom1Transmitter";
        public const string TypeSelectCom2Transmitter = "selectCom2Transmitter";
        public const string TypeSetCom1ReceiveEnabled = "setCom1ReceiveEnabled";
        public const string TypeSetCom2ReceiveEnabled = "setCom2ReceiveEnabled";
        public const string TypeSetSimbriefCredentials = "setSimbriefCredentials";
        public const string TypeRefreshFlightPlan = "refreshFlightPlan";
        // Issue #88 -- update-interval tier (Fast/Normal/Slow), persisted plugin-side, edited here.
        public const string TypeSetUpdateInterval = "setUpdateInterval";
        public const string TypePinController = "pinController";
        public const string TypeClearPinnedController = "clearPinnedController";
        public const string TypeDismissSelcal = "dismissSelcal";
        // Issue #151 -- advances a per-tab chat read watermark; see docs/protocol.md and
        // ChatModel.MarkRead.
        public const string TypeMarkChatRead = "markChatRead";
        public const string TypeConfirmDiversion = "confirmDiversion";
        public const string TypeDismissDiversion = "dismissDiversion";
        // Issue #171 -- connect to / disconnect from the VATSIM network; see docs/protocol.md.
        public const string TypeConnectNetwork = "connectNetwork";
        public const string TypeDisconnectNetwork = "disconnectNetwork";
        // Polls the public VATSIM data feed now instead of at its next 15s tick; no payload.
        public const string TypeRefreshVatsimFeed = "refreshVatsimFeed";
        public const string TypePing = "ping";
        public const string TypeAuthenticate = "authenticate";

        // Issue #65 -- debug mode: session-only diagnostic toggle, snapshot capture, optional
        // follow-up screenshot. See docs/protocol.md.
        public const string TypeSetDebugMode = "setDebugMode";
        public const string TypeSaveDebugSnapshot = "saveDebugSnapshot";
        public const string TypeAttachDebugSnapshotScreenshot = "attachDebugSnapshotScreenshot";
        // Issue #73b -- attaches a pilot-chosen name to an already-saved snapshot, after the fact.
        public const string TypeNameDebugSnapshot = "nameDebugSnapshot";

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

        // setUpdateInterval -- the update-interval tier as a lowercase wire string
        // ("fast"/"normal"/"slow"), persisted by the plugin (overwriting whatever was persisted
        // before) and applied live to the SimConnect polls and the WS broadcast cadence. An
        // unrecognized value is ignored, leaving the current tier untouched. See docs/protocol.md.
        public string Interval { get; set; }

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

        // markChatRead -- Tab is "radio" or a private peer's callsign; ThroughId is the highest
        // message id the client has seen as read for that tab. The plugin applies max(existing,
        // ThroughId), clamped to the newest known message id -- see ChatModel.MarkRead.
        public string Tab { get; set; }
        public long? ThroughId { get; set; }

        // setDebugMode -- Enabled (above) carries the payload.
        // saveDebugSnapshot -- client-generated GUID correlating the debugSnapshotSaved reply and
        // the later attachDebugSnapshotScreenshot; AppVersion is the Android versionName (the
        // plugin doesn't otherwise know it -- see docs/protocol.md).
        // attachDebugSnapshotScreenshot -- same SnapshotId, ScreenshotPngBase64 is the PNG bytes.
        // nameDebugSnapshot -- same SnapshotId, Name (below) is the pilot-typed label.
        public string SnapshotId { get; set; }
        public string AppVersion { get; set; }
        public string ScreenshotPngBase64 { get; set; }
        public string Name { get; set; }

        // connectNetwork / disconnectNetwork -- client-minted id the plugin reports the outcome
        // under via operationProgress, so the requesting client can tell its own operation apart
        // from any other broadcast one.
        public string OperationId { get; set; }

        // connectNetwork -- Callsign (above) is the callsign to connect as; AircraftType is the
        // ICAO type code (ignored for observers), SelcalCode optional, Observer connects without
        // an aircraft.
        public string AircraftType { get; set; }
        public string SelcalCode { get; set; }
        public bool? Observer { get; set; }
    }
}
