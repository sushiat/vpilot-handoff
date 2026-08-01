using System;
using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using Xunit;

namespace Handoff.Plugin.Tests
{
    /// <summary>
    /// Issue #18's hide-then-expire disconnect handling: HandoffControllerStateModel replaces
    /// the old ControllerStateModel/ContactMeModel/SelcalActiveModel split. ControllerDeleted
    /// marks a record hidden (with a timestamp) rather than dropping it outright, so a brief
    /// reconnect within HiddenExpiryWindow (5 minutes) restores everything untouched; a
    /// disconnect that outlasts the window is dropped entirely and treated as brand new on any
    /// later reconnect.
    /// </summary>
    public class HandoffControllerStateModelTests
    {
        private readonly FakeBroker _broker = new FakeBroker();
        private readonly ChatModel _chat;

        public HandoffControllerStateModelTests()
        {
            _chat = new ChatModel(_broker);
        }

        private HandoffControllerStateModel CreateModel(Func<DateTimeOffset> now = null) =>
            new HandoffControllerStateModel(_broker, _chat, now);

        [Fact]
        public void HiddenController_IsExcludedFromControllers_Immediately()
        {
            var model = CreateModel();
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 23725, 0, 0));

            _broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_TWR"));

            Assert.Empty(model.Controllers);
        }

        [Fact]
        public void ReconnectWithinHiddenWindow_PreservesPinContactMeAndSelcal()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var model = CreateModel(() => now);
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 23725, 0, 0));
            model.SetPinnedController("EGLL_TWR");
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));
            _broker.RaiseSelcalAlertReceived(new SelcalAlertReceivedEventArgs(new[] { 23725 }, "EGLL_TWR"));

            _broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_TWR"));
            now = now.AddMinutes(2); // within the 5-minute window
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 23725, 0, 0));

            var restored = model.Controllers.Single();
            Assert.True(restored.IsPinned);
            Assert.NotNull(restored.ContactMeExpiresAtUtc);
            Assert.NotNull(restored.SelcalExpiresAtUtc);
            Assert.False(restored.IsHidden);
        }

        [Fact]
        public void ReconnectAfterHiddenWindowExpires_IsTreatedAsBrandNew()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var model = CreateModel(() => now);
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 23725, 0, 0));
            model.SetPinnedController("EGLL_TWR");
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));

            _broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_TWR"));
            now = now.AddMinutes(6); // past the 5-minute window

            // A read past the expiry window prunes the stale hidden record.
            Assert.Empty(model.Controllers);

            _broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 23725, 0, 0));

            var fresh = model.Controllers.Single();
            Assert.False(fresh.IsPinned);
            Assert.Null(fresh.ContactMeExpiresAtUtc);
        }

        [Fact]
        public void ExpiredHiddenRecord_IsDroppedEvenWithoutAReconnect()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var model = CreateModel(() => now);
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 23725, 0, 0));

            _broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_TWR"));
            now = now.AddMinutes(6);

            Assert.Empty(model.Controllers);
        }

        [Fact]
        public void ContactMeExpiry_LazilyClearsOnRead_PastItsOwnExpiryWindow()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var model = CreateModel(() => now);
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 23725, 0, 0));
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EGLL_TWR", "contact me"));
            Assert.NotNull(model.Controllers.Single().ContactMeExpiresAtUtc);

            now = now.AddMinutes(6);

            Assert.Null(model.Controllers.Single().ContactMeExpiresAtUtc);
        }

        [Fact]
        public void OffListContactMe_SynthesizesRow_WithParsedFrequencyAndContactMe()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var model = CreateModel(() => now);

            // Never seen via ControllerAdded -- a station on a frequency no data source exposes.
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EDDF_R_APP", "please contact me on 128.950"));

            var synth = model.Controllers.Single();
            Assert.Equal("EDDF_R_APP", synth.Callsign);
            Assert.True(synth.IsOffList);
            Assert.NotNull(synth.ContactMeExpiresAtUtc);
            Assert.Equal(28950, synth.Frequency); // 128.950 in vPilot's compressed-integer format
        }

        [Fact]
        public void OffListContactMe_WithNoFrequencyInText_UsesZeroSentinel()
        {
            var model = CreateModel();

            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EDDF_R_APP", "contact me"));

            var synth = model.Controllers.Single();
            Assert.True(synth.IsOffList);
            Assert.Equal(0, synth.Frequency);
        }

        [Fact]
        public void OffListContactMe_PromotedWhenRealControllerAdded_KeepsContactMeDropsSyntheticMarker()
        {
            var model = CreateModel();
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EDDF_R_APP", "contact me on 128.950"));

            // The station finally shows up on the network for real.
            _broker.RaiseControllerAdded(new ControllerAddedEventArgs("EDDF_R_APP", 12345, 50.0, 8.5));

            var promoted = model.Controllers.Single();
            Assert.False(promoted.IsOffList);
            Assert.Equal(12345, promoted.Frequency); // real telemetry, not the parsed 28950
            Assert.Equal(50.0, promoted.Latitude);
            Assert.NotNull(promoted.ContactMeExpiresAtUtc); // outstanding contact-me survives the promotion
        }

        [Fact]
        public void OffListContactMe_DroppedEntirelyOnExpiry_NotLeftAsInertRow()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var model = CreateModel(() => now);
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EDDF_R_APP", "contact me on 128.950"));
            Assert.Single(model.Controllers);

            now = now.AddMinutes(6); // past the contact-me expiry window

            // No ControllerDeleted ever arrives for a synthetic row, so this only prunes if the
            // off-list-specific removal path fires -- otherwise it would linger forever.
            Assert.Empty(model.Controllers);
        }

        [Fact]
        public void OffListContactMe_RepeatMessage_RenewsExpiryAndStaysOffList()
        {
            var now = new DateTimeOffset(2026, 7, 25, 12, 0, 0, TimeSpan.Zero);
            var model = CreateModel(() => now);
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EDDF_R_APP", "contact me"));

            now = now.AddMinutes(4); // still within the window
            _broker.RaisePrivateMessageReceived(new PrivateMessageReceivedEventArgs("EDDF_R_APP", "contact me"));

            var synth = model.Controllers.Single();
            Assert.True(synth.IsOffList);
            Assert.Equal(now + TimeSpan.FromMinutes(5), synth.ContactMeExpiresAtUtc);
        }
    }
}
