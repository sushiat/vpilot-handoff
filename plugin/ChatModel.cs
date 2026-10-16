using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Text;
using Newtonsoft.Json;
using RossCarlson.Vatsim.Vpilot.Plugins;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;

namespace Handoff.Plugin
{
    /// <summary>
    /// Live in-memory model of chat messages and SELCAL alerts, built from IBroker's
    /// message/alert events plus the plugin's own outgoing sends (IBroker has no "message
    /// sent" echo event, so outgoing messages are appended locally when Send* is called).
    ///
    /// Threading: same rationale as ControllerStateModel — vPilot raises events off its own
    /// thread(s), a plain lock around the backing lists keeps individual operations and
    /// snapshot reads consistent, nothing stronger is needed for a single local plugin.
    /// </summary>
    public sealed class ChatModel
    {
        // Issue #134: VATSIM text traffic is low, so a session realistically never approaches
        // this, but nothing was capping it -- an unbounded list re-serialized and rebroadcast in
        // full on every new message would otherwise grow for as long as the plugin runs. Applies
        // separately to _messages and _selcalAlerts (independent, unrelated streams).
        private const int MaxHistoryEntries = 200;

        /// <summary>U+FFFD, the standard Unicode substitute for a malformed character (see SanitizeText).</summary>
        private const char ReplacementChar = (char)0xFFFD;

        private readonly object _gate = new object();
        private readonly IBroker _broker;
        private readonly Action<string> _logDebug;
        private readonly List<ChatMessage> _messages = new List<ChatMessage>();
        private readonly List<SelcalAlert> _selcalAlerts = new List<SelcalAlert>();
        private long _nextMessageId = 1;
        // Issue #151 -- per-tab read watermark ("radio" or a private peer's callsign -> highest
        // message id read). In-memory only, same lifetime as _messages: the log itself isn't
        // persisted across a plugin restart, so persisting just the watermark would be meaningless.
        private readonly Dictionary<string, long> _readThrough = new Dictionary<string, long>();

        /// <summary>
        /// Issue #151 -- changes whenever this ChatModel instance is constructed (i.e. every plugin
        /// restart), so clients can tell "the log and its ids just reset" apart from "nothing new
        /// happened yet" and drop any cached local read-tracking assumptions.
        /// </summary>
        public string SessionId { get; } = Guid.NewGuid().ToString("N").Substring(0, 8);

        /// <summary>
        /// Fires after any new message or SELCAL alert. Payload-free by design, same as
        /// ControllerStateModel.Changed — consumers re-read Messages/SelcalAlerts.
        /// </summary>
        public event EventHandler Changed;

        /// <summary>
        /// Issue #151 -- fires only when a MarkRead call actually advances some tab's watermark.
        /// Deliberately separate from Changed: the watermark changes far more often than the log
        /// itself and shouldn't trigger a resend of up to 200 chat entries each time.
        /// </summary>
        public event EventHandler ReadStateChanged;

        /// <summary>
        /// Called with (from, sanitized text) for every incoming private message, before it's added
        /// to the log. Returning true swallows the message: it never reaches the log, so it gets no
        /// id and never counts as unread. HandoffControllerStateModel uses this to turn a known
        /// controller's "contact me" into a row signal instead of a chat message.
        /// </summary>
        public Func<string, string, bool> IncomingPrivateFilter { get; set; }

        public ChatModel(IBroker broker, Action<string> logDebug = null)
        {
            _broker = broker ?? throw new ArgumentNullException(nameof(broker));
            _logDebug = logDebug;

            broker.PrivateMessageReceived += OnPrivateMessageReceived;
            broker.RadioMessageReceived += OnRadioMessageReceived;
            broker.BroadcastMessageReceived += OnBroadcastMessageReceived;
            broker.SelcalAlertReceived += OnSelcalAlertReceived;
        }

        public IReadOnlyList<ChatMessage> Messages
        {
            get { lock (_gate) { return _messages.ToList(); } }
        }

        public IReadOnlyList<SelcalAlert> SelcalAlerts
        {
            get { lock (_gate) { return _selcalAlerts.ToList(); } }
        }

        public IReadOnlyDictionary<string, long> ReadThrough
        {
            get { lock (_gate) { return new Dictionary<string, long>(_readThrough); } }
        }

        /// <summary>
        /// Issue #151 -- applies max(existing watermark, throughId) for this tab ("radio" or a
        /// private peer's callsign). throughId is clamped to the newest message id ever assigned,
        /// so a stale/bogus client value can never claim more as read than actually exists. Raises
        /// ReadStateChanged only if the watermark genuinely advanced -- a no-op resend never
        /// triggers a rebroadcast.
        /// </summary>
        public void MarkRead(string tab, long throughId)
        {
            if (string.IsNullOrEmpty(tab)) return;

            bool advanced;
            lock (_gate)
            {
                var clamped = Math.Min(throughId, _nextMessageId - 1);
                var current = _readThrough.TryGetValue(tab, out var existing) ? existing : 0;
                var next = Math.Max(current, clamped);
                advanced = next > current;
                if (advanced) _readThrough[tab] = next;
            }

            if (advanced) ReadStateChanged?.Invoke(this, EventArgs.Empty);
        }

        public void SendPrivateMessage(string to, string message)
        {
            _broker.SendPrivateMessage(to, message);
            AddMessage(new ChatMessage(ChatChannel.Private, ChatDirection.Outgoing, to, message, null, DateTimeOffset.Now));
        }

        public void SendRadioMessage(string message)
        {
            _broker.SendRadioMessage(message);
            // Frequencies unknown here: IBroker doesn't report which frequency the message
            // actually transmitted on. Filling this in needs the tuned-frequency reading
            // from the SimConnect piece, not yet built.
            AddMessage(new ChatMessage(ChatChannel.Radio, ChatDirection.Outgoing, null, message, null, DateTimeOffset.Now));
        }

        private void OnPrivateMessageReceived(object sender, PrivateMessageReceivedEventArgs e)
        {
            var sanitized = LogAndSanitize("PrivateMessageReceived", e.From, e.Message);
            if (IncomingPrivateFilter?.Invoke(e.From, sanitized) == true)
            {
                Log($"PrivateMessageReceived from={e.From} swallowed by IncomingPrivateFilter");
                return;
            }
            AddMessage(new ChatMessage(ChatChannel.Private, ChatDirection.Incoming, e.From, sanitized, null, DateTimeOffset.Now));
        }

        private void OnRadioMessageReceived(object sender, RadioMessageReceivedEventArgs e)
        {
            var sanitized = LogAndSanitize("RadioMessageReceived", e.From, e.Message);
            AddMessage(new ChatMessage(ChatChannel.Radio, ChatDirection.Incoming, null, sanitized, e.Frequencies, DateTimeOffset.Now, e.From));
        }

        private void OnBroadcastMessageReceived(object sender, BroadcastMessageReceivedEventArgs e)
        {
            var sanitized = LogAndSanitize("BroadcastMessageReceived", e.From, e.Message);
            AddMessage(new ChatMessage(ChatChannel.Broadcast, ChatDirection.Incoming, e.From, sanitized, null, DateTimeOffset.Now));
        }

        /// <summary>
        /// Issue #131 -- a private message once arrived on a client with a completely empty
        /// text body despite the sender confirming it wasn't sent that way. No repro since, so
        /// this logs enough to diagnose it if it recurs: raw text, length, and whether the text
        /// round-trips cleanly through the same JSON serializer used for the wire protocol.
        /// Applied to all three incoming channels (not just private) -- the corruption theory
        /// (unpaired surrogate / stray control char in FSD-decoded text) applies equally to any
        /// of them, and radio/broadcast traffic is a far more reliable way to catch it live than
        /// waiting for a private message. Returns the sanitized text either way.
        /// </summary>
        private string LogAndSanitize(string eventName, string from, string text)
        {
            var sanitized = SanitizeText(text);

            if (_logDebug != null)
            {
                string roundTrip;
                try
                {
                    var json = JsonConvert.SerializeObject(text);
                    roundTrip = JsonConvert.DeserializeObject<string>(json) == text ? "ok" : "mismatch";
                }
                catch (JsonException ex)
                {
                    roundTrip = "threw: " + ex.Message;
                }

                Log($"{eventName} from={from} length={text?.Length ?? -1} isNull={text == null} jsonRoundTrip={roundTrip} text=\"{text}\"");

                if (!ReferenceEquals(sanitized, text))
                {
                    Log($"{eventName} from={from} sanitization changed text: raw=\"{text}\" sanitized=\"{sanitized}\"");
                }
            }

            return sanitized;
        }

        /// <summary>
        /// Issue #131 hardening -- regardless of what actually caused the empty-text report,
        /// neither an unpaired UTF-16 surrogate (can come out of a truncated multi-byte FSD
        /// message) nor an embedded control character like NUL (a classic cross-language
        /// truncation trigger) should be able to reach the JSON wire payload. Returns the
        /// original reference unchanged when nothing needed fixing.
        /// </summary>
        private static string SanitizeText(string text)
        {
            if (string.IsNullOrEmpty(text)) return text;

            StringBuilder sb = null;
            for (var i = 0; i < text.Length; i++)
            {
                var c = text[i];

                if (char.IsHighSurrogate(c))
                {
                    var hasLow = i + 1 < text.Length && char.IsLowSurrogate(text[i + 1]);
                    if (hasLow)
                    {
                        sb?.Append(c);
                        sb?.Append(text[i + 1]);
                        i++;
                        continue;
                    }

                    if (sb == null) sb = new StringBuilder(text.Substring(0, i));
                    sb.Append(ReplacementChar);
                    continue;
                }

                if (char.IsLowSurrogate(c))
                {
                    if (sb == null) sb = new StringBuilder(text.Substring(0, i));
                    sb.Append(ReplacementChar);
                    continue;
                }

                if (char.IsControl(c) && c != '\t' && c != '\n' && c != '\r')
                {
                    if (sb == null) sb = new StringBuilder(text.Substring(0, i));
                    continue;
                }

                sb?.Append(c);
            }

            return sb?.ToString() ?? text;
        }

        private void OnSelcalAlertReceived(object sender, SelcalAlertReceivedEventArgs e)
        {
            lock (_gate)
            {
                _selcalAlerts.Add(new SelcalAlert(e.From, e.Frequencies, DateTimeOffset.Now));
                TrimToCapacity(_selcalAlerts);
            }
            RaiseChanged();
        }

        private void AddMessage(ChatMessage message)
        {
            lock (_gate)
            {
                message.Id = _nextMessageId++;
                _messages.Add(message);
                TrimToCapacity(_messages);
            }
            RaiseChanged();
        }

        /// <summary>Assumes the caller already holds _gate.</summary>
        private static void TrimToCapacity<T>(List<T> list)
        {
            if (list.Count > MaxHistoryEntries) list.RemoveRange(0, list.Count - MaxHistoryEntries);
        }

        private void RaiseChanged() => Changed?.Invoke(this, EventArgs.Empty);

        private void Log(string message)
        {
            var line = "ChatModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
