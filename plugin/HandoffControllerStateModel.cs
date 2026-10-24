using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.RegularExpressions;
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
        // Issue #186 -- ControllerAdded events within this long after the model was created or the
        // network (re)connected are vPilot replaying the stations already online, not new openings.
        private static readonly TimeSpan InitialBurstWindow = TimeSpan.FromSeconds(15);
        private static readonly TimeSpan NewStationReopenAfter = TimeSpan.FromMinutes(2);
        private const string ContactMePhrase = "contact me";

        // Pulls a VHF airband frequency out of a free-text "contact me" (e.g. "please contact me on
        // 128.950") so an off-list synthetic entry can show it. Integer part 100-136, 1-3 decimals;
        // the caller range-checks 118-137 to reject stray matches like a time or a squawk. Free
        // text, not a fixed template -- widen this once real messages are sampled (see the plugin's
        // own note in OnChatChanged).
        private static readonly Regex ContactMeFrequencyRegex =
            new Regex(@"\b1(?:[0-2]\d|3[0-6])\.\d{1,3}\b", RegexOptions.Compiled | RegexOptions.CultureInvariant);

        private readonly object _gate = new object();
        private readonly Dictionary<string, HandoffController> _controllers =
            new Dictionary<string, HandoffController>(StringComparer.OrdinalIgnoreCase);
        private readonly Func<DateTimeOffset> _now;
        private int _processedSelcalAlertCount;
        private DateTimeOffset _burstStartedUtc;

        /// <summary>Fires after any add/hide/reconnect/frequency/location/contact-me/SELCAL/pin change.</summary>
        public event EventHandler Changed;

        public HandoffControllerStateModel(IBroker broker, ChatModel chatModel, Func<DateTimeOffset> now = null)
        {
            if (broker == null) throw new ArgumentNullException(nameof(broker));
            if (chatModel == null) throw new ArgumentNullException(nameof(chatModel));
            _now = now ?? (() => DateTimeOffset.Now);
            _burstStartedUtc = _now();

            broker.NetworkConnected += (s, e) => { lock (_gate) _burstStartedUtc = _now(); };

            broker.ControllerAdded += OnControllerAdded;
            broker.ControllerDeleted += OnControllerDeleted;
            broker.ControllerFrequencyChanged += OnControllerFrequencyChanged;
            broker.ControllerLocationChanged += OnControllerLocationChanged;
            chatModel.Changed += (s, e) => OnChatChanged(chatModel);
            chatModel.IncomingPrivateFilter = HandleIncomingPrivate;
        }

        /// <summary>
        /// A known controller's private "contact me" is turned into the row's contact-me signal
        /// (flag + the message text) and swallowed, so it never reaches the chat log -- the row
        /// renders it better than a chat message would, and there's nothing left to blink or stay
        /// unread once the pilot tunes. Returns true when swallowed. An off-list sender (no row yet)
        /// or any other private message returns false and goes to the chat log as before; the
        /// off-list case is then picked up by <see cref="OnChatChanged"/>.
        /// </summary>
        private bool HandleIncomingPrivate(string from, string text)
        {
            if (from == null || text == null) return false;
            if (text.IndexOf(ContactMePhrase, StringComparison.OrdinalIgnoreCase) < 0) return false;

            lock (_gate)
            {
                if (!_controllers.TryGetValue(from, out var controller) || controller.IsOffList) return false;
                _controllers[from] = controller.WithContactMeExpiry(_now() + ContactMeExpiryWindow, text);
            }
            RaiseChanged();
            return true;
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
                _controllers.TryGetValue(e.Callsign, out var existing);
                if (existing != null && existing.IsOffList)
                    // A real station finally appeared for a callsign we'd only seen as an off-list
                    // contact-me: adopt its real telemetry, keep the outstanding contact-me/pin.
                    _controllers[e.Callsign] = existing.Promoted(e.Frequency, e.Latitude, e.Longitude);
                else if (existing != null && existing.IsHidden)
                {
                    // A blip keeps the original stamp (no re-blink); gone for NewStationReopenAfter
                    // or longer, the pilot would be on unicom by then, so it counts as opening anew.
                    var now = _now();
                    var goneLong = existing.HiddenSinceUtc.HasValue && now - existing.HiddenSinceUtc.Value >= NewStationReopenAfter
                        && now - _burstStartedUtc >= InitialBurstWindow;
                    _controllers[e.Callsign] = existing.Reconnected(goneLong ? now : existing.OpenedAtUtc);
                }
                else
                {
                    var now = _now();
                    var isNewOpening = now - _burstStartedUtc >= InitialBurstWindow;
                    _controllers[e.Callsign] = new HandoffController(
                        e.Callsign, e.Frequency, e.Latitude, e.Longitude, openedAtUtc: isNewOpening ? now : (DateTimeOffset?)null);
                }
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

        // Handles what's still in the chat log after HandleIncomingPrivate: an off-list sender's
        // "contact me" (a known controller's is swallowed before it gets here), plus
        // SelcalActiveModel's "only process alerts new since last call" bookkeeping -- both folded
        // into one handler since they're both driven by the same ChatModel.Changed event.
        private void OnChatChanged(ChatModel chatModel)
        {
            var changed = false;
            lock (_gate)
            {
                var lastMessage = chatModel.Messages.LastOrDefault();
                if (lastMessage != null && lastMessage.Channel == ChatChannel.Private && lastMessage.Direction == ChatDirection.Incoming
                    && lastMessage.Peer != null && lastMessage.Text != null
                    && lastMessage.Text.IndexOf(ContactMePhrase, StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    if (_controllers.TryGetValue(lastMessage.Peer, out var contactMeTarget))
                    {
                        _controllers[lastMessage.Peer] = contactMeTarget.WithContactMeExpiry(_now() + ContactMeExpiryWindow);
                    }
                    else
                    {
                        // Off-list sender -- a "contact me" from a callsign that never came in via
                        // ControllerAdded (a station on a frequency none of our data sources expose).
                        // The old TryGetValue guard dropped these silently; instead synthesize a
                        // placeholder so the contact-me still reaches the pilot as a normal row. See
                        // HandoffController.IsOffList for why this lives entirely plugin-side.
                        _controllers[lastMessage.Peer] = HandoffController.OffList(
                            lastMessage.Peer, ParseContactMeFrequency(lastMessage.Text), _now() + ContactMeExpiryWindow);
                    }
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

            // Off-list synthetic entries never get a ControllerDeleted (nothing on the IBroker side
            // knows they exist), so the hidden-expiry prune above never touches them. Once the
            // contact-me that spawned one has lapsed -- cleared just above -- there's nothing left
            // for it to represent, so drop it outright rather than leave an inert frequency-only
            // row behind. A pin keeps it (the pilot explicitly bookmarked it); it goes on unpin.
            var syntheticExpired = _controllers
                .Where(kv => kv.Value.IsOffList && !kv.Value.ContactMeExpiresAtUtc.HasValue && !kv.Value.IsPinned)
                .Select(kv => kv.Key)
                .ToList();
            foreach (var callsign in syntheticExpired) _controllers.Remove(callsign);
        }

        /// <summary>Compressed-integer frequency (e.g. 28950 for 128.950) pulled from a free-text "contact me", or 0 when none is found -- the pilot then reads the original chat message for the frequency.</summary>
        private static int ParseContactMeFrequency(string text) => ParseContactMeFrequencies(text).FirstOrDefault();

        /// <summary>
        /// Every VHF airband frequency named in a free-text "contact me", as compressed integers
        /// (e.g. 28950 for 128.950), in order of appearance. A controller can name more than one
        /// (a primary and a backup) or one that differs from their listed frequency, so tuning any
        /// of them counts as answering the request -- see ControllerRankingModel.RecomputeLocked.
        /// </summary>
        public static IReadOnlyList<int> ParseContactMeFrequencies(string text)
        {
            var result = new List<int>();
            if (string.IsNullOrEmpty(text)) return result;

            foreach (Match match in ContactMeFrequencyRegex.Matches(text))
            {
                if (!double.TryParse(match.Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var megahertz)) continue;
                if (megahertz < 118.0 || megahertz >= 137.0) continue;
                result.Add((int)Math.Round((megahertz - 100.0) * 1000.0));
            }
            return result;
        }

        private void RaiseChanged() => Changed?.Invoke(this, EventArgs.Empty);
    }
}
