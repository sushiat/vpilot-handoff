using System;
using System.Collections.Generic;
using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;

namespace Handoff.Plugin
{
    /// <summary>
    /// Unified live model of connected ATC stations plus every ranking-relevant piece of
    /// per-station metadata (contact-me, SELCAL, pin) -- issue #18 replaces the old
    /// ControllerStateModel/ContactMeModel/SelcalActiveModel/ControllerRankingModel._pinnedCallsign
    /// split with this single model, since all four were really just different views onto "state
    /// about one callsign."
    ///
    /// Disconnect is hide-then-expire, not instant removal: IBroker's ControllerDeleted just
    /// marks the record IsHidden (with a timestamp) rather than dropping it, so a brief reconnect
    /// (an FSD blip, not really a new session) doesn't wipe an outstanding contact-me/SELCAL/pin.
    /// A lazy expiry check (checked on read, same style as the old ContactMeModel.PruneExpired --
    /// no separate timer thread) actually drops a record once it's been hidden longer than
    /// HiddenExpiryWindow. Hidden records are excluded from <see cref="Controllers"/> (what
    /// ranking/the client ever sees) but stay in the internal dictionary until the expiry check
    /// drops them, so a reconnect within the window restores everything untouched.
    ///
    /// Threading: same rationale as the old ControllerStateModel -- vPilot raises IBroker events
    /// off its own thread(s), a lock around the backing dictionary keeps individual
    /// add/remove/update operations atomic and snapshot reads consistent. Every
    /// <see cref="HandoffController"/> instance is immutable once published, so callers reading
    /// <see cref="Controllers"/> need no further locking beyond the brief lock this getter itself
    /// takes to build the snapshot list.
    /// </summary>
    public sealed class HandoffControllerStateModel
    {
        private static readonly TimeSpan HiddenExpiryWindow = TimeSpan.FromMinutes(5);
        private static readonly TimeSpan ContactMeExpiryWindow = TimeSpan.FromMinutes(5);
        private static readonly TimeSpan SelcalExpiryWindow = TimeSpan.FromMinutes(5);
        private const string ContactMePhrase = "contact me";

        private readonly object _gate = new object();
        private readonly Dictionary<string, HandoffController> _controllers =
            new Dictionary<string, HandoffController>(StringComparer.OrdinalIgnoreCase);
        private readonly Func<DateTimeOffset> _now;
        private int _processedSelcalAlertCount;

        /// <summary>Fires after any add/hide/reconnect/frequency/location/contact-me/SELCAL/pin change.</summary>
        public event EventHandler Changed;

        public HandoffControllerStateModel(IBroker broker, ChatModel chatModel, Func<DateTimeOffset> now = null)
        {
            if (broker == null) throw new ArgumentNullException(nameof(broker));
            if (chatModel == null) throw new ArgumentNullException(nameof(chatModel));
            _now = now ?? (() => DateTimeOffset.Now);

            broker.ControllerAdded += OnControllerAdded;
            broker.ControllerDeleted += OnControllerDeleted;
            broker.ControllerFrequencyChanged += OnControllerFrequencyChanged;
            broker.ControllerLocationChanged += OnControllerLocationChanged;
            chatModel.Changed += (s, e) => OnChatChanged(chatModel);
        }

        /// <summary>Point-in-time snapshot of all currently-visible (not hidden) stations, expiry-pruned first.</summary>
        public IReadOnlyCollection<HandoffController> Controllers
        {
            get
            {
                lock (_gate)
                {
                    PruneExpired();
                    return _controllers.Values.Where(c => !c.IsHidden).ToList();
                }
            }
        }

        /// <summary>Marks the given callsign as pinned. Multiple controllers can be pinned at once -- this never touches any other callsign's pin, only ever the pilot's own explicit unpin (or the controller going offline past its hidden-expiry window) clears one.</summary>
        public void SetPinnedController(string callsign)
        {
            bool changed;
            lock (_gate)
            {
                changed = _controllers.TryGetValue(callsign, out var existing) && !existing.IsPinned;
                if (changed) _controllers[callsign] = existing.WithPinned(true);
            }
            if (changed) RaiseChanged();
        }

        /// <summary>Clears one specific callsign's pin -- never any other pinned controller's.</summary>
        public void ClearPinnedController(string callsign)
        {
            bool changed;
            lock (_gate)
            {
                changed = _controllers.TryGetValue(callsign, out var existing) && existing.IsPinned;
                if (changed) _controllers[callsign] = existing.WithPinned(false);
            }
            if (changed) RaiseChanged();
        }

        /// <summary>Clears an outstanding contact-me request -- called by ControllerRankingModel when the callsign becomes the currently-tuned controller.</summary>
        public void ClearContactMe(string callsign)
        {
            bool changed;
            lock (_gate)
            {
                changed = _controllers.TryGetValue(callsign, out var existing) && existing.ContactMeExpiresAtUtc.HasValue;
                if (changed) _controllers[callsign] = existing.WithContactMeExpiry(null);
            }
            if (changed) RaiseChanged();
        }

        /// <summary>Issue #65 -- full raw pre-ranking state for the debug snapshot file, including currently-hidden (grace-window) stations so "why is X missing entirely" is distinguishable from "why is X ranked wrong."</summary>
        public ControllerStateDebugSnapshot BuildDebugSnapshot()
        {
            lock (_gate)
            {
                PruneExpired();
                var all = _controllers.Values.ToList();
                return new ControllerStateDebugSnapshot(
                    all,
                    all.Count(c => c.IsPinned),
                    all.Count(c => c.ContactMeExpiresAtUtc.HasValue),
                    all.Count(c => c.SelcalExpiresAtUtc.HasValue));
            }
        }

        /// <summary>Clears an active SELCAL alert -- called by HandoffWebSocketServer on an incoming dismissSelcal command.</summary>
        public void ClearSelcal(string callsign)
        {
            bool changed;
            lock (_gate)
            {
                changed = _controllers.TryGetValue(callsign, out var existing) && existing.SelcalExpiresAtUtc.HasValue;
                if (changed) _controllers[callsign] = existing.WithSelcalExpiry(null);
            }
            if (changed) RaiseChanged();
        }

        private void OnControllerAdded(object sender, ControllerAddedEventArgs e)
        {
            lock (_gate)
            {
                _controllers[e.Callsign] = _controllers.TryGetValue(e.Callsign, out var existing) && existing.IsHidden
                    ? existing.Reconnected()
                    : new HandoffController(e.Callsign, e.Frequency, e.Latitude, e.Longitude);
            }
            RaiseChanged();
        }

        private void OnControllerDeleted(object sender, ControllerDeletedEventArgs e)
        {
            lock (_gate)
            {
                if (_controllers.TryGetValue(e.Callsign, out var existing))
                    _controllers[e.Callsign] = existing.Hidden(_now());
            }
            RaiseChanged();
        }

        private void OnControllerFrequencyChanged(object sender, ControllerFrequencyChangedEventArgs e)
        {
            lock (_gate)
            {
                if (_controllers.TryGetValue(e.Callsign, out var existing))
                    _controllers[e.Callsign] = existing.WithFrequency(e.NewFrequency);
                // Unknown callsign: ignore rather than fabricate a partial entry, same as before.
            }
            RaiseChanged();
        }

        private void OnControllerLocationChanged(object sender, ControllerLocationChangedEventArgs e)
        {
            lock (_gate)
            {
                if (_controllers.TryGetValue(e.Callsign, out var existing))
                    _controllers[e.Callsign] = existing.WithLocation(e.NewLatitude, e.NewLongitude);
            }
            RaiseChanged();
        }

        // Mirrors the old ContactMeModel's chat-trigger condition exactly, plus SelcalActiveModel's
        // "only process alerts new since last call" bookkeeping -- both folded into one handler
        // since they're both driven by the same ChatModel.Changed event.
        private void OnChatChanged(ChatModel chatModel)
        {
            var changed = false;
            lock (_gate)
            {
                var lastMessage = chatModel.Messages.LastOrDefault();
                if (lastMessage != null && lastMessage.Channel == ChatChannel.Private && lastMessage.Direction == ChatDirection.Incoming
                    && lastMessage.Peer != null && lastMessage.Text != null
                    && lastMessage.Text.IndexOf(ContactMePhrase, StringComparison.OrdinalIgnoreCase) >= 0
                    && _controllers.TryGetValue(lastMessage.Peer, out var contactMeTarget))
                {
                    _controllers[lastMessage.Peer] = contactMeTarget.WithContactMeExpiry(_now() + ContactMeExpiryWindow);
                    changed = true;
                }

                var alerts = chatModel.SelcalAlerts;
                if (alerts.Count > _processedSelcalAlertCount)
                {
                    var newAlerts = alerts.Skip(_processedSelcalAlertCount).ToList();
                    _processedSelcalAlertCount = alerts.Count;
                    foreach (var alert in newAlerts.Where(a => _controllers.ContainsKey(a.From)))
                    {
                        _controllers[alert.From] = _controllers[alert.From].WithSelcalExpiry(_now() + SelcalExpiryWindow);
                        changed = true;
                    }
                }
            }
            if (changed) RaiseChanged();
        }

        /// <summary>Drops any station hidden longer than HiddenExpiryWindow, and lazily clears any expired contact-me/SELCAL on the rest -- called under _gate from Controllers' getter.</summary>
        private void PruneExpired()
        {
            var now = _now();

            var stale = _controllers
                .Where(kv => kv.Value.IsHidden && kv.Value.HiddenSinceUtc.HasValue && now - kv.Value.HiddenSinceUtc.Value >= HiddenExpiryWindow)
                .Select(kv => kv.Key)
                .ToList();
            foreach (var callsign in stale) _controllers.Remove(callsign);

            foreach (var key in _controllers.Keys.ToList())
            {
                var c = _controllers[key];
                var updated = c;
                if (updated.ContactMeExpiresAtUtc.HasValue && updated.ContactMeExpiresAtUtc.Value <= now)
                    updated = updated.WithContactMeExpiry(null);
                if (updated.SelcalExpiresAtUtc.HasValue && updated.SelcalExpiresAtUtc.Value <= now)
                    updated = updated.WithSelcalExpiry(null);
                if (!ReferenceEquals(updated, c)) _controllers[key] = updated;
            }
        }

        private void RaiseChanged() => Changed?.Invoke(this, EventArgs.Empty);
    }
}
