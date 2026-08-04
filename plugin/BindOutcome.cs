using System.Net.Sockets;

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

    /// <summary>Classifies a failed bind's SocketException -- shared so both listeners agree on
    /// what counts as a port conflict.</summary>
    public static class BindOutcomeClassifier
    {
        /// <summary>AddressAlreadyInUse is the textbook case, but Windows reports a taken port as
        /// AccessDenied (WSAEACCES) instead whenever the current holder bound with
        /// SO_EXCLUSIVEADDRESSUSE -- which System.Net.Sockets.TcpListener/UdpClient both default
        /// to. Confirmed against a real conflict (issue #98): a stray TcpListener holding 48765
        /// made Fleck's own bind fail with AccessDenied, not AddressAlreadyInUse. Both read as a
        /// genuine port conflict from here -- there's no way to tell "someone else's app has this
        /// port" apart from "a truly permission-denied bind" from the error code alone, and the
        /// former is overwhelmingly the realistic cause for a plugin binding a normal, non-admin,
        /// non-well-known port.</summary>
        public static BindOutcome Classify(SocketException ex) =>
            ex.SocketErrorCode == SocketError.AddressAlreadyInUse || ex.SocketErrorCode == SocketError.AccessDenied
                ? BindOutcome.PortConflict
                : BindOutcome.OtherError;
    }
}
