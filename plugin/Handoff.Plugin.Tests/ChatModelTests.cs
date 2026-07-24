using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class ChatModelTests
    {
        [Fact]
        public void PrivateMessageReceived_AppearsAsIncoming()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));

            var message = Assert.Single(model.Messages);
            Assert.Equal(ChatChannel.Private, message.Channel);
            Assert.Equal(ChatDirection.Incoming, message.Direction);
            Assert.Equal("EGLL_TWR", message.Peer);
            Assert.Equal("hello", message.Text);
        }

        [Fact]
        public void RadioMessageReceived_IncludesFrequencies()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            broker.RaiseRadioMessageReceived(new RadioMessageReceivedEventArgs(new[] { 12345 }, "EGLL_TWR", "cleared for takeoff"));

            var message = Assert.Single(model.Messages);
            Assert.Equal(ChatChannel.Radio, message.Channel);
            Assert.Equal(ChatDirection.Incoming, message.Direction);
            Assert.Equal(new[] { 12345 }, message.Frequencies);
        }

        [Fact]
        public void BroadcastMessageReceived_AppearsAsIncomingWithSenderAsPeer()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            broker.RaiseBroadcastMessageReceived(new BroadcastMessageReceivedEventArgs("VATSIM", "server restarting"));

            var message = Assert.Single(model.Messages);
            Assert.Equal(ChatChannel.Broadcast, message.Channel);
            Assert.Equal("VATSIM", message.Peer);
        }

        [Fact]
        public void SendPrivateMessage_CallsThroughAndAppendsOutgoingMessage()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            model.SendPrivateMessage("EGLL_TWR", "wilco");

            Assert.Equal(("EGLL_TWR", "wilco"), broker.SentPrivateMessages.Single());
            var message = Assert.Single(model.Messages);
            Assert.Equal(ChatDirection.Outgoing, message.Direction);
            Assert.Equal("EGLL_TWR", message.Peer);
            Assert.Equal("wilco", message.Text);
        }

        [Fact]
        public void SendRadioMessage_CallsThroughAndAppendsOutgoingMessage()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            model.SendRadioMessage("request pushback");

            Assert.Equal("request pushback", broker.SentRadioMessages.Single());
            var message = Assert.Single(model.Messages);
            Assert.Equal(ChatChannel.Radio, message.Channel);
            Assert.Equal(ChatDirection.Outgoing, message.Direction);
        }

        [Fact]
        public void SelcalAlertReceived_AppearsInSelcalAlerts()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_TWR"));

            var alert = Assert.Single(model.SelcalAlerts);
            Assert.Equal("EGLL_TWR", alert.From);
            Assert.Equal(new[] { 12345 }, alert.Frequencies);
        }

        [Fact]
        public void Changed_FiresOnIncomingMessageAndSelcalAlert()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            var raiseCount = 0;
            model.Changed += (s, e) => raiseCount++;

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));
            broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_TWR"));

            Assert.Equal(2, raiseCount);
        }
    }
}
