using System;
using System.Collections.Generic;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Tracks which controllers have a currently-active SELCAL alert. Mostly mirrors
    /// ContactMeModel's design (lazy expiry, pruned when the controller goes offline), but with
    /// one deliberate difference: there's no tune-match auto-clear. Real SELCAL requires the
    /// pilot to already be tuned to the relevant frequency (just with the volume down, e.g. on an
    /// oceanic crossing) -- being tuned is the precondition for the controller's pulse to ever
    /// reach the aircraft at all, not evidence the pilot noticed it. So tune-match is trivially
    /// true here (you're by definition already on frequency) and carries zero "have they seen
    /// it" signal, unlike contact-me (a message that, once received, has definitionally been
    /// seen). Clearing instead requires an explicit dismissSelcal client command
    /// (docs/protocol.md), which the Android app sends from its own dismiss button -- unlike
    /// contact-me, that button is this model's only clear path other than expiry, so there's no
    /// separate "acknowledged" concept split across two systems. Ranked immediately below any
    /// outstanding contact-me request, regardless of tier -- see ControllerRankingModel.
    /// </summary>
    public sealed class SelcalActiveModel
    {
        private static readonly TimeSpan ExpiryWindow = TimeSpan.FromMinutes(5);

        private readonly object _gate = new object();
        private readonly Dictionary<string, DateTimeOffset> _expiresAt =
            new Dictionary<string, DateTimeOffset>(StringComparer.OrdinalIgnoreCase);
        private readonly Func<DateTimeOffset> _now;
        private int _processedAlertCount;

        public event EventHandler Changed;

        public SelcalActiveModel(ChatModel chatModel, ControllerStateModel controllerState, Func<DateTimeOffset> now = null)
        {
            if (chatModel == null) throw new ArgumentNullException(nameof(chatModel));
            if (controllerState == null) throw new ArgumentNullException(nameof(controllerState));

            _now = now ?? (() => DateTimeOffset.Now);

            chatModel.Changed += (s, e) => OnChatChanged(chatModel);
            controllerState.Changed += (s, e) => OnControllersChanged(controllerState);
        }

        /// <summary>Point-in-time snapshot of callsigns with a currently-active SELCAL alert.</summary>
        public IReadOnlyCollection<string> ActiveCallsigns
        {
            get
            {
                lock (_gate)
                {
                    PruneExpired();
                    return _expiresAt.Keys.ToList();
                }
            }
        }

        public bool IsActive(string callsign)
        {
            if (string.IsNullOrEmpty(callsign)) return false;
            lock (_gate)
            {
                PruneExpired();
                return _expiresAt.ContainsKey(callsign);
            }
        }

        /// <summary>
        /// Clears the active alert for the given callsign -- called by HandoffWebSocketServer on
        /// an incoming dismissSelcal command. Expiry and going-offline are handled internally.
        /// </summary>
        public void Clear(string callsign)
        {
            if (string.IsNullOrEmpty(callsign)) return;
            bool removed;
            lock (_gate) { removed = _expiresAt.Remove(callsign); }
            if (removed) Changed?.Invoke(this, EventArgs.Empty);
        }

        // ChatModel.Changed is shared by regular messages and SELCAL alerts alike, so this must
        // only react to alerts genuinely new since the last call -- otherwise every unrelated
        // chat message would re-extend an unrelated alert's expiry window by another 5 minutes.
        private void OnChatChanged(ChatModel chatModel)
        {
            var alerts = chatModel.SelcalAlerts;
            List<SelcalAlert> newAlerts;
            lock (_gate)
            {
                if (alerts.Count <= _processedAlertCount) return;
                newAlerts = alerts.Skip(_processedAlertCount).ToList();
                _processedAlertCount = alerts.Count;
                foreach (var alert in newAlerts) _expiresAt[alert.From] = _now() + ExpiryWindow;
            }
            Changed?.Invoke(this, EventArgs.Empty);
        }

        private void OnControllersChanged(ControllerStateModel controllerState)
        {
            var known = new HashSet<string>(controllerState.Controllers.Select(c => c.Callsign), StringComparer.OrdinalIgnoreCase);
            bool changed;
            lock (_gate)
            {
                var stale = _expiresAt.Keys.Where(k => !known.Contains(k)).ToList();
                foreach (var callsign in stale) _expiresAt.Remove(callsign);
                changed = stale.Count > 0;
            }
            if (changed) Changed?.Invoke(this, EventArgs.Empty);
        }

        private void PruneExpired()
        {
            var now = _now();
            var stale = _expiresAt.Where(kv => kv.Value <= now).Select(kv => kv.Key).ToList();
            foreach (var callsign in stale) _expiresAt.Remove(callsign);
        }
    }
}
