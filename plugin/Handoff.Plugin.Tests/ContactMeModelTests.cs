using System;
using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class ContactMeModelTests
    {
        [Fact]
        public void IncomingPrivateMessage_ContainingContactMe_SetsActive()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "please contact me on 118.7"));

            Assert.True(model.IsActive("EGLL_TWR"));
            Assert.Contains("EGLL_TWR", model.ActiveCallsigns);
        }

        [Fact]
        public void IncomingPrivateMessage_ContainingContactMe_IsCaseInsensitive()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "CONTACT ME please"));

            Assert.True(model.IsActive("EGLL_TWR"));
        }

        [Fact]
        public void IncomingPrivateMessage_WithoutContactMePhrase_IsIgnored()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "cleared for takeoff"));

            Assert.False(model.IsActive("EGLL_TWR"));
            Assert.Empty(model.ActiveCallsigns);
        }

        [Fact]
        public void OutgoingMessage_IsNeverTreatedAsContactMe()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers);

            chat.SendPrivateMessage("EGLL_TWR", "contact me anytime");

            Assert.False(model.IsActive("EGLL_TWR"));
        }

        [Fact]
        public void Expiry_AfterFiveMinutes_BecomesInactive()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers, () => now);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));
            Assert.True(model.IsActive("EGLL_TWR"));

            now = now.AddMinutes(5).AddSeconds(1);

            Assert.False(model.IsActive("EGLL_TWR"));
        }

        [Fact]
        public void RepeatContactMe_ResetsTheExpiryWindow()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers, () => now);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));

            now = now.AddMinutes(4);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));

            now = now.AddMinutes(4);

            Assert.True(model.IsActive("EGLL_TWR"));
        }

        [Fact]
        public void Clear_RemovesActiveRequest()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));

            model.Clear("EGLL_TWR");

            Assert.False(model.IsActive("EGLL_TWR"));
        }

        [Fact]
        public void Clear_RaisesChanged()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));
            var raised = false;
            model.Changed += (s, e) => raised = true;

            model.Clear("EGLL_TWR");

            Assert.True(raised);
        }

        [Fact]
        public void ControllerDeleted_RemovesActiveRequest()
        {
            var broker = new FakeBroker();
            var chat = new ChatModel(broker);
            var controllers = new ControllerStateModel(broker);
            var model = new ContactMeModel(chat, controllers);
            broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 12345, 51.47, -0.45));
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));
            Assert.True(model.IsActive("EGLL_TWR"));

            broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_TWR"));

            Assert.False(model.IsActive("EGLL_TWR"));
        }
    }
}
