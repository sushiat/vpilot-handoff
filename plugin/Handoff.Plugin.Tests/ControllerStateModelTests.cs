using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class ControllerStateModelTests
    {
        [Fact]
        public void ControllerAdded_AddsToSnapshot()
        {
            var broker = new FakeBroker();
            var model = new ControllerStateModel(broker);

            broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 12345, 51.47, -0.45));

            var controller = Assert.Single(model.Controllers);
            Assert.Equal("EGLL_TWR", controller.Callsign);
            Assert.Equal(12345, controller.Frequency);
        }

        [Fact]
        public void ControllerDeleted_RemovesFromSnapshot()
        {
            var broker = new FakeBroker();
            var model = new ControllerStateModel(broker);
            broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 12345, 51.47, -0.45));

            broker.RaiseControllerDeleted(new ControllerDeletedEventArgs("EGLL_TWR"));

            Assert.Empty(model.Controllers);
        }

        [Fact]
        public void ControllerFrequencyChanged_UpdatesExistingEntry()
        {
            var broker = new FakeBroker();
            var model = new ControllerStateModel(broker);
            broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 12345, 51.47, -0.45));

            broker.RaiseControllerFrequencyChanged(new ControllerFrequencyChangedEventArgs("EGLL_TWR", 11800));

            Assert.Equal(11800, model.Controllers.Single().Frequency);
        }

        [Fact]
        public void ControllerLocationChanged_UpdatesExistingEntry()
        {
            var broker = new FakeBroker();
            var model = new ControllerStateModel(broker);
            broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 12345, 51.47, -0.45));

            broker.RaiseControllerLocationChanged(new ControllerLocationChangedEventArgs("EGLL_TWR", 52.0, -1.0));

            var controller = model.Controllers.Single();
            Assert.Equal(52.0, controller.Latitude);
            Assert.Equal(-1.0, controller.Longitude);
        }

        [Fact]
        public void FrequencyChanged_ForUnknownCallsign_IsIgnored()
        {
            var broker = new FakeBroker();
            var model = new ControllerStateModel(broker);

            broker.RaiseControllerFrequencyChanged(new ControllerFrequencyChangedEventArgs("GHOST", 11800));

            Assert.Empty(model.Controllers);
        }

        [Fact]
        public void Changed_FiresOnAdd()
        {
            var broker = new FakeBroker();
            var model = new ControllerStateModel(broker);
            var raised = false;
            model.Changed += (s, e) => raised = true;

            broker.RaiseControllerAdded(new ControllerAddedEventArgs("EGLL_TWR", 12345, 51.47, -0.45));

            Assert.True(raised);
        }
    }
}
