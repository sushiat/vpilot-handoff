using System;
using System.Collections.Generic;
using System.Linq;

namespace Handoff.Plugin
{
    /// <summary>
    /// Tracks which controllers currently have an outstanding "contact me" request -- a standard
    /// VATSIM private message a controller sends when they want a pilot to switch frequency
    /// without a formal handoff (always from a controller, never a pilot, so the sender needs no
    /// cross-checking). Ranked immediately below the current controller regardless of tier -- see
    /// ControllerRankingModel.
    ///
    /// Expiry is lazy (checked against DateTimeOffset.Now on read) rather than timer-driven, same
    /// minimal-threading style as the rest of this codebase's models -- nothing here needs to fire
    /// on its own schedule, only when read or when a clearing event happens.
    /// </summary>
    public sealed class ContactMeModel
    {
        private static readonly TimeSpan ExpiryWindow = TimeSpan.FromMinutes(5);
        private const string ContactMePhrase = "contact me";

        private readonly object _gate = new object();
        private readonly Dictionary<string, DateTimeOffset> _expiresAt =
            new Dictionary<string, DateTimeOffset>(StringComparer.OrdinalIgnoreCase);
        private readonly Func<DateTimeOffset> _now;

        public event EventHandler Changed;

        public ContactMeModel(ChatModel chatModel, ControllerStateModel controllerState, Func<DateTimeOffset> now = null)
        {
            if (chatModel == null) throw new ArgumentNullException(nameof(chatModel));
            if (controllerState == null) throw new ArgumentNullException(nameof(controllerState));

            _now = now ?? (() => DateTimeOffset.Now);

            chatModel.Changed += (s, e) => OnChatChanged(chatModel);
            controllerState.Changed += (s, e) => OnControllersChanged(controllerState);
        }

        /// <summary>Point-in-time snapshot of callsigns with a currently-outstanding contact-me request.</summary>
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
        /// Clears any outstanding request for the given callsign -- called by
        /// ControllerRankingModel when it's the currently-tuned controller (one of the three
        /// clearing conditions per the issue design: tune-match, 5-minute expiry, or the
        /// controller going offline, the latter two handled internally by this class).
        /// </summary>
        public void Clear(string callsign)
        {
            if (string.IsNullOrEmpty(callsign)) return;
            bool removed;
            lock (_gate) { removed = _expiresAt.Remove(callsign); }
            if (removed) Changed?.Invoke(this, EventArgs.Empty);
        }

        private void OnChatChanged(ChatModel chatModel)
        {
            var lastMessage = chatModel.Messages.LastOrDefault();
            if (lastMessage == null) return;
            if (lastMessage.Channel != ChatChannel.Private) return;
            if (lastMessage.Direction != ChatDirection.Incoming) return;
            if (lastMessage.Peer == null) return;
            if (lastMessage.Text == null || lastMessage.Text.IndexOf(ContactMePhrase, StringComparison.OrdinalIgnoreCase) < 0) return;

            lock (_gate) { _expiresAt[lastMessage.Peer] = _now() + ExpiryWindow; }
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
