using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Holds the pilot's own callsign/CID for the current VATSIM connection, sourced from
    /// IBroker.NetworkConnected -- the authoritative, live value (whatever was actually typed
    /// into vPilot's connect dialog), as opposed to FlightPlanModel's SimBrief-derived callsign,
    /// which is just whatever was typed when the OFP was generated and can drift from what's
    /// actually flying (a stale plan, a manually-changed connect callsign, a different aircraft).
    /// Connection-scoped: populated on NetworkConnected, cleared on NetworkDisconnected/
    /// SessionEnded, same lifecycle reasoning as RadioStateModel/VatsimDataFeedModel.
    /// </summary>
    public sealed class PilotSessionModel
    {
        private readonly object _gate = new object();
        private string _callsign;
        private string _cid;

        public event EventHandler Changed;

        public string Callsign { get { lock (_gate) { return _callsign; } } }
        public string Cid { get { lock (_gate) { return _cid; } } }

        public void OnNetworkConnected(string callsign, string cid)
        {
            lock (_gate)
            {
                _callsign = callsign;
                _cid = cid;
            }
            Changed?.Invoke(this, EventArgs.Empty);
        }

        public void OnDisconnected()
        {
            bool changed;
            lock (_gate)
            {
                changed = _callsign != null || _cid != null;
                _callsign = null;
                _cid = null;
            }
            if (changed) Changed?.Invoke(this, EventArgs.Empty);
        }
    }
}
