namespace Handoff.Plugin
{
    /// <summary>
    /// Wire format for the local named-pipe protocol between the plugin (client) and
    /// Handoff.RadioHost (server). One flat envelope rather than a polymorphic hierarchy --
    /// the message set is small and fixed, so a discriminated Type field with optional fields
    /// is simpler than a JSON converter for a handful of subtypes.
    /// </summary>
    public sealed class RadioIpcMessage
    {
        public const string TypeRadioState = "radioState";
        public const string TypeOwnshipTelemetry = "ownshipTelemetry";
        public const string TypeSetCom1Frequency = "setCom1Frequency";
        public const string TypeSetCom2Frequency = "setCom2Frequency";
        public const string TypeSetCom1StandbyFrequency = "setCom1StandbyFrequency";
        public const string TypeSetCom2StandbyFrequency = "setCom2StandbyFrequency";
        // Combined active+standby write in one round trip -- used for a "transfer" (activate a
        // just-typed/selected frequency while preserving whatever was previously active into
        // standby, matching real flip-flop avionics) or a plain swap, without paying the latency
        // of two separate queued commands each blocking on their own settle-wait. See
        // RadioSimConnectClient.SetCom1ActiveAndStandbyFrequency.
        public const string TypeSetCom1ActiveAndStandbyFrequency = "setCom1ActiveAndStandbyFrequency";
        public const string TypeSetCom2ActiveAndStandbyFrequency = "setCom2ActiveAndStandbyFrequency";
        public const string TypeSetTransponderCode = "setTransponderCode";

        public string Type { get; set; }

        // TypeRadioState (host -> plugin)
        public int? Com1Frequency { get; set; }
        public int? Com2Frequency { get; set; }
        public int? Com1StandbyFrequency { get; set; }
        public int? Com2StandbyFrequency { get; set; }
        public bool? ModeCEnabled { get; set; }
        public int? TransponderCode { get; set; }

        // TypeOwnshipTelemetry (host -> plugin)
        public bool? OnGround { get; set; }
        public double? GroundSpeedKnots { get; set; }
        public double? AltitudeAboveGroundFeet { get; set; }
        public double? VerticalSpeedFpm { get; set; }
        public double? HeadingDegrees { get; set; }
        public double? Latitude { get; set; }
        public double? Longitude { get; set; }
        public double? PressureAltitudeFeet { get; set; }
        public double? SeaLevelPressureHpa { get; set; }

        // TypeSetCom1Frequency / TypeSetCom2Frequency / TypeSetCom1StandbyFrequency /
        // TypeSetCom2StandbyFrequency (plugin -> host): the single frequency to set.
        // TypeSetCom1ActiveAndStandbyFrequency / TypeSetCom2ActiveAndStandbyFrequency
        // (plugin -> host): Megahertz is the new active frequency, StandbyMegahertz the new
        // standby frequency, applied together.
        public double? Megahertz { get; set; }
        public double? StandbyMegahertz { get; set; }
    }
}
