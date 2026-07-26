using System;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class SelcalActiveModelTests
    {
        [Fact]
        public void SelcalAlertReceived_SetsActive()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new SelcalActiveModel(chat, controllers);

            broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));

            Assert.True(model.IsActive("EGLL_CTR"));
            Assert.Contains("EGLL_CTR", model.ActiveCallsigns);
        }

        [Fact]
        public void UnrelatedChatMessage_DoesNotExtendOrCreateAnActiveAlert()
        {
            // Regression: ChatModel.Changed fires for regular messages too, not just SELCAL
            // alerts -- an unrelated message must not re-extend some other alert's expiry.
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new SelcalActiveModel(chat, controllers, () => now);
            broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));

            now = now.AddMinutes(4);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_CTR", "unrelated message"));
            now = now.AddMinutes(1).AddSeconds(1);

            Assert.False(model.IsActive("EGLL_CTR"));
        }

        [Fact]
        public void Expiry_AfterFiveMinutes_BecomesInactive()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new SelcalActiveModel(chat, controllers, () => now);

            broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));
            Assert.True(model.IsActive("EGLL_CTR"));

            now = now.AddMinutes(5).AddSeconds(1);

            Assert.False(model.IsActive("EGLL_CTR"));
        }

        [Fact]
        public void Clear_RemovesActiveAlert()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new SelcalActiveModel(chat, controllers);
            broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));

            model.Clear("EGLL_CTR");

            Assert.False(model.IsActive("EGLL_CTR"));
        }

        [Fact]
        public void Clear_RaisesChanged()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new SelcalActiveModel(chat, controllers);
            broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));
            var raised = false;
            model.Changed += (s, e) => raised = true;

            model.Clear("EGLL_CTR");

            Assert.True(raised);
        }

        [Fact]
        public void ControllerDeleted_RemovesActiveAlert()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new SelcalActiveModel(chat, controllers);
            broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_CTR", 12345, 51.47, -0.45));
            broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 12345 }, "EGLL_CTR"));
            Assert.True(model.IsActive("EGLL_CTR"));

            broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_CTR"));

            Assert.False(model.IsActive("EGLL_CTR"));
        }
    }
}
