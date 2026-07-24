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

        public string Type { get; set; }

        // TypeRadioState (host -> plugin)
        public int? Com1Frequency { get; set; }
        public int? Com2Frequency { get; set; }
        public bool? ModeCEnabled { get; set; }

        // TypeSetCom1Frequency / TypeSetCom2Frequency (plugin -> host)
        public double? Megahertz { get; set; }
    }
}
