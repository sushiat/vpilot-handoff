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

        /// <summary>
        /// Fires after any new message or SELCAL alert. Payload-free by design, same as
        /// ControllerStateModel.Changed — consumers re-read Messages/SelcalAlerts.
        /// </summary>
        public event EventHandler Changed;

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
            LogPrivateMessageDiagnostics(e.From, e.Message);
            var sanitized = SanitizeText(e.Message);
            if (!ReferenceEquals(sanitized, e.Message))
            {
                Log($"PrivateMessageReceived from={e.From} sanitization changed text: raw=\"{e.Message}\" sanitized=\"{sanitized}\"");
            }
            AddMessage(new ChatMessage(ChatChannel.Private, ChatDirection.Incoming, e.From, sanitized, null, DateTimeOffset.Now));
        }

        private void OnRadioMessageReceived(object sender, RadioMessageReceivedEventArgs e)
        {
            AddMessage(new ChatMessage(ChatChannel.Radio, ChatDirection.Incoming, null, SanitizeText(e.Message), e.Frequencies, DateTimeOffset.Now, e.From));
        }

        private void OnBroadcastMessageReceived(object sender, BroadcastMessageReceivedEventArgs e)
        {
            AddMessage(new ChatMessage(ChatChannel.Broadcast, ChatDirection.Incoming, e.From, SanitizeText(e.Message), null, DateTimeOffset.Now));
        }

        /// <summary>
        /// Issue #131 -- a private message once arrived on a client with a completely empty
        /// text body despite the sender confirming it wasn't sent that way. No repro since, so
        /// this logs enough to diagnose it if it recurs: raw text, length, and whether the text
        /// round-trips cleanly through the same JSON serializer used for the wire protocol.
        /// </summary>
        private void LogPrivateMessageDiagnostics(string from, string text)
        {
            if (_logDebug == null) return;

            string roundTrip;
            try
            {
                var json = JsonConvert.SerializeObject(text);
                roundTrip = JsonConvert.DeserializeObject<string>(json) == text ? "ok" : "mismatch";
            }
            catch (Exception ex)
            {
                roundTrip = "threw: " + ex.Message;
            }

            Log($"PrivateMessageReceived from={from} length={text?.Length ?? -1} isNull={text == null} jsonRoundTrip={roundTrip} text=\"{text}\"");
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
