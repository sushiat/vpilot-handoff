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
        public const string TypeSetCom1Frequency = "setCom1Frequency";
        public const string TypeSetCom2Frequency = "setCom2Frequency";
        public const string TypeSetCom1StandbyFrequency = "setCom1StandbyFrequency";
        public const string TypeSetCom2StandbyFrequency = "setCom2StandbyFrequency";
        public const string TypeSetTransponderCode = "setTransponderCode";

        public string Type { get; set; }

        // TypeRadioState (host -> plugin)
        public int? Com1Frequency { get; set; }
        public int? Com2Frequency { get; set; }
        public int? Com1StandbyFrequency { get; set; }
        public int? Com2StandbyFrequency { get; set; }
        public bool? ModeCEnabled { get; set; }
        public int? TransponderCode { get; set; }

        // TypeSetCom1Frequency / TypeSetCom2Frequency / TypeSetCom1StandbyFrequency /
        // TypeSetCom2StandbyFrequency (plugin -> host)
        public double? Megahertz { get; set; }
    }
}
