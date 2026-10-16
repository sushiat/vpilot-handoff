using System.Collections.Generic;
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

        // Issue #131 -- diagnostic logging and sanitization for the empty-text report.

        [Fact]
        public void PrivateMessageReceived_LogsDiagnosticsWhenLoggerProvided()
        {
            var broker = new FakeBroker();
            var lines = new List<string>();
            _ = new ChatModel(broker, lines.Add);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));

            var line = Assert.Single(lines);
            Assert.Contains("from=EGLL_TWR", line);
            Assert.Contains("length=5", line);
            Assert.Contains("isNull=False", line);
            Assert.Contains("jsonRoundTrip=ok", line);
            Assert.Contains("text=\"hello\"", line);
        }

        [Fact]
        public void PrivateMessageReceived_NoLoggerProvided_DoesNotThrow()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));

            Assert.Equal("hello", Assert.Single(model.Messages).Text);
        }

        [Fact]
        public void PrivateMessageReceived_UnpairedHighSurrogate_ReplacedWithReplacementChar()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            // U+D83D is a high surrogate with no following low surrogate -- malformed UTF-16.
            var malformed = "before\uD83Dafter";

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", malformed));

            var text = Assert.Single(model.Messages).Text;
            Assert.Equal("before�after", text);
        }

        [Fact]
        public void PrivateMessageReceived_UnpairedLowSurrogate_ReplacedWithReplacementChar()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            // U+DE00 is a low surrogate with no preceding high surrogate -- malformed UTF-16.
            var malformed = "before\uDE00after";

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", malformed));

            var text = Assert.Single(model.Messages).Text;
            Assert.Equal("before�after", text);
        }

        [Fact]
        public void PrivateMessageReceived_ValidSurrogatePair_LeftIntact()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            var emoji = "before😀after"; // a valid surrogate pair (an emoji)

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", emoji));

            Assert.Equal(emoji, Assert.Single(model.Messages).Text);
        }

        [Fact]
        public void PrivateMessageReceived_EmbeddedNulCharacter_Stripped()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            var withNul = "before\0after";

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", withNul));

            Assert.Equal("beforeafter", Assert.Single(model.Messages).Text);
        }

        [Fact]
        public void PrivateMessageReceived_CleanText_Unchanged()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "clean message"));

            Assert.Equal("clean message", Assert.Single(model.Messages).Text);
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
        public void RadioMessageReceived_LogsDiagnosticsWhenLoggerProvided()
        {
            var broker = new FakeBroker();
            var lines = new List<string>();
            _ = new ChatModel(broker, lines.Add);

            broker.RaiseRadioMessageReceived(new RadioMessageReceivedEventArgs(new[] { 12345 }, "EGLL_TWR", "cleared for takeoff"));

            var line = Assert.Single(lines);
            Assert.Contains("RadioMessageReceived", line);
            Assert.Contains("from=EGLL_TWR", line);
            Assert.Contains("text=\"cleared for takeoff\"", line);
        }

        [Fact]
        public void RadioMessageReceived_UnpairedSurrogate_ReplacedWithReplacementChar()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            var malformed = "descend\uD83Dnow";

            broker.RaiseRadioMessageReceived(new RadioMessageReceivedEventArgs(new[] { 12345 }, "EGLL_TWR", malformed));

            Assert.Equal("descend�now", Assert.Single(model.Messages).Text);
        }

        [Fact]
        public void BroadcastMessageReceived_LogsDiagnosticsWhenLoggerProvided()
        {
            var broker = new FakeBroker();
            var lines = new List<string>();
            _ = new ChatModel(broker, lines.Add);

            broker.RaiseBroadcastMessageReceived(new BroadcastMessageReceivedEventArgs("VATSIM", "server restarting"));

            var line = Assert.Single(lines);
            Assert.Contains("BroadcastMessageReceived", line);
            Assert.Contains("from=VATSIM", line);
            Assert.Contains("text=\"server restarting\"", line);
        }

        [Fact]
        public void BroadcastMessageReceived_EmbeddedNulCharacter_Stripped()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            broker.RaiseBroadcastMessageReceived(new BroadcastMessageReceivedEventArgs("VATSIM", "server\0restarting"));

            Assert.Equal("serverrestarting", Assert.Single(model.Messages).Text);
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

        // Issue #151 -- per-message id and the per-tab read watermark.

        [Fact]
        public void Messages_AreAssignedMonotonicallyIncreasingIds()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "one"));
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "two"));
            model.SendRadioMessage("three");

            Assert.Equal(new[] { 1L, 2L, 3L }, model.Messages.Select(m => m.Id));
        }

        [Fact]
        public void SessionId_IsStableAcrossMultipleReads()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);

            Assert.Equal(model.SessionId, model.SessionId);
            Assert.NotEmpty(model.SessionId);
        }

        [Fact]
        public void SessionId_DiffersAcrossInstances()
        {
            var first = new ChatModel(new FakeBroker());
            var second = new ChatModel(new FakeBroker());

            Assert.NotEqual(first.SessionId, second.SessionId);
        }

        [Fact]
        public void MarkRead_SetsWatermarkForTab()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));

            model.MarkRead("EGLL_TWR", 1);

            Assert.Equal(1, model.ReadThrough["EGLL_TWR"]);
        }

        [Fact]
        public void MarkRead_NeverMovesWatermarkBackwards()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "one"));
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "two"));
            model.MarkRead("EGLL_TWR", 2);

            model.MarkRead("EGLL_TWR", 1);

            Assert.Equal(2, model.ReadThrough["EGLL_TWR"]);
        }

        [Fact]
        public void MarkRead_ClampsToNewestKnownMessageId()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));

            model.MarkRead("EGLL_TWR", 999);

            Assert.Equal(1, model.ReadThrough["EGLL_TWR"]);
        }

        [Fact]
        public void MarkRead_RaisesReadStateChangedOnlyWhenWatermarkAdvances()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));
            var raiseCount = 0;
            model.ReadStateChanged += (s, e) => raiseCount++;

            model.MarkRead("EGLL_TWR", 1);
            model.MarkRead("EGLL_TWR", 1); // no-op resend
            model.MarkRead("EGLL_TWR", 0); // backwards, also a no-op

            Assert.Equal(1, raiseCount);
        }

        [Fact]
        public void MarkRead_DoesNotAffectOtherTabs()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));
            model.SendRadioMessage("wilco");

            model.MarkRead("EGLL_TWR", 1);

            Assert.False(model.ReadThrough.ContainsKey("radio"));
        }

        [Fact]
        public void MarkRead_DifferingCase_AdvancesTheSameWatermark()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "one"));
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "two"));
            var raiseCount = 0;
            model.ReadStateChanged += (s, e) => raiseCount++;

            model.MarkRead("egll_twr", 1);
            model.MarkRead("EGLL_TWR", 2);
            model.MarkRead("Egll_Twr", 1); // backwards, no-op

            var readThrough = model.ReadThrough;
            Assert.Single(readThrough);
            Assert.Equal(2, readThrough["EGLL_TWR"]);
            Assert.Equal(2, readThrough["egll_twr"]);
            Assert.Equal(2, raiseCount);
        }

        [Fact]
        public void MarkRead_StoresPeerKeyWithCasingFromTheLog()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "hello"));

            model.MarkRead("egll_twr", 1);

            Assert.Equal("EGLL_TWR", Assert.Single(model.ReadThrough.Keys));
        }

        [Fact]
        public void MarkRead_RadioTabIsCaseInsensitiveAndCanonicalisedToLowercase()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            model.SendRadioMessage("wilco");

            model.MarkRead("RADIO", 1);

            Assert.Equal("radio", Assert.Single(model.ReadThrough.Keys));
            Assert.Equal(1, model.ReadThrough["Radio"]);
        }

        [Fact]
        public void MarkRead_BroadcastSenderIsNotATab_RadioWatermarkCoversBroadcasts()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            broker.RaiseBroadcastMessageReceived(new BroadcastMessageReceivedEventArgs("EGLL_TWR", "wx update"));

            model.MarkRead("radio", 1);

            Assert.Equal(1, model.ReadThrough["radio"]);
            Assert.False(model.ReadThrough.ContainsKey("EGLL_TWR"));
        }

        [Fact]
        public void IncomingPrivateFilter_ReturningTrue_SwallowsMessageWithoutConsumingAnId()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            model.IncomingPrivateFilter = (from, text) => text == "swallow me";

            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "swallow me"));
            broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "keep me"));

            var message = Assert.Single(model.Messages);
            Assert.Equal("keep me", message.Text);
            Assert.Equal(1, message.Id); // the swallowed message never took id 1
        }

        [Fact]
        public void IncomingPrivateFilter_DoesNotAffectRadioMessages()
        {
            var broker = new FakeBroker();
            var model = new ChatModel(broker);
            model.IncomingPrivateFilter = (from, text) => true;

            broker.RaiseRadioMessageReceived(new RadioMessageReceivedEventArgs(new[] { 23725 }, "EGLL_TWR", "contact me"));

            Assert.Single(model.Messages);
        }
    }
}
