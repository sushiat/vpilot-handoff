using System;
using System.Collections.Generic;
using System.Linq;
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
        private readonly object _gate = new object();
        private readonly IBroker _broker;
        private readonly List<ChatMessage> _messages = new List<ChatMessage>();
        private readonly List<SelcalAlert> _selcalAlerts = new List<SelcalAlert>();

        /// <summary>
        /// Fires after any new message or SELCAL alert. Payload-free by design, same as
        /// ControllerStateModel.Changed — consumers re-read Messages/SelcalAlerts.
        /// </summary>
        public event EventHandler Changed;

        public ChatModel(IBroker broker)
        {
            _broker = broker ?? throw new ArgumentNullException(nameof(broker));

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
            AddMessage(new ChatMessage(ChatChannel.Private, ChatDirection.Incoming, e.From, e.Message, null, DateTimeOffset.Now));
        }

        private void OnRadioMessageReceived(object sender, RadioMessageReceivedEventArgs e)
        {
            AddMessage(new ChatMessage(ChatChannel.Radio, ChatDirection.Incoming, null, e.Message, e.Frequencies, DateTimeOffset.Now));
        }

        private void OnBroadcastMessageReceived(object sender, BroadcastMessageReceivedEventArgs e)
        {
            AddMessage(new ChatMessage(ChatChannel.Broadcast, ChatDirection.Incoming, e.From, e.Message, null, DateTimeOffset.Now));
        }

        private void OnSelcalAlertReceived(object sender, SelcalAlertReceivedEventArgs e)
        {
            lock (_gate) { _selcalAlerts.Add(new SelcalAlert(e.From, e.Frequencies, DateTimeOffset.Now)); }
            RaiseChanged();
        }

        private void AddMessage(ChatMessage message)
        {
            lock (_gate) { _messages.Add(message); }
            RaiseChanged();
        }

        private void RaiseChanged() => Changed?.Invoke(this, EventArgs.Empty);
    }
}
