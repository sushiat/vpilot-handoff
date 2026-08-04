namespace Handoff.Plugin
{
    /// <summary>
    /// Result of a listener's socket-bind attempt (issue #98) -- shared between
    /// HandoffWebSocketServer and HandoffDiscoveryListener so HandoffPlugin.Initialize can tell a
    /// port conflict (the one case worth surfacing to the pilot) apart from every other bind
    /// failure, which stays log-only as before.
    /// </summary>
    public enum BindOutcome
    {
        Success,
        PortConflict,
        OtherError
    }
}
