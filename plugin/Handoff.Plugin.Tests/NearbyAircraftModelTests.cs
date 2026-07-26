using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class NearbyAircraftModelTests
    {
        private readonly FakeBroker _broker = new FakeBroker();
        private readonly FakeRadioStateModel _radioState = new FakeRadioStateModel();

        [Fact]
        public void NoOwnshipPosition_ReturnsEmpty()
        {
            var model = new NearbyAircraftModel(_broker, _radioState);

            _broker.RaiseAircraftAdded(new AircraftAddedEventArgs("BAW123", "B738", 51.48, -0.45, 0, 0, 0, 0, 0, 0));

            Assert.Empty(model.Current);
        }

        [Fact]
        public void WithinRadius_IsIncludedAndSortedByDistance()
        {
            _radioState.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 51.4775, -0.4614, System.DateTimeOffset.Now);
            var model = new NearbyAircraftModel(_broker, _radioState);

            // Roughly 6nm away.
            _broker.RaiseAircraftAdded(new AircraftAddedEventArgs("BAW123", "B738", 51.55, -0.4614, 0, 0, 0, 0, 0, 0));
            // Very close, ~1nm.
            _broker.RaiseAircraftAdded(new AircraftAddedEventArgs("DLH456", "A320", 51.49, -0.4614, 0, 0, 0, 0, 0, 0));

            var result = model.Current;

            Assert.Equal(2, result.Count);
            Assert.Equal("DLH456", result[0].Callsign);
            Assert.Equal("A320", result[0].AircraftType);
            Assert.Equal("BAW123", result[1].Callsign);
            Assert.True(result[0].DistanceNm < result[1].DistanceNm);
        }

        [Fact]
        public void BeyondRadius_IsExcluded()
        {
            _radioState.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 51.4775, -0.4614, System.DateTimeOffset.Now);
            var model = new NearbyAircraftModel(_broker, _radioState);

            // Far more than 20nm away.
            _broker.RaiseAircraftAdded(new AircraftAddedEventArgs("BAW123", "B738", 52.5, -0.4614, 0, 0, 0, 0, 0, 0));

            Assert.Empty(model.Current);
        }

        [Fact]
        public void AircraftDeleted_IsRemoved()
        {
            _radioState.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 51.4775, -0.4614, System.DateTimeOffset.Now);
            var model = new NearbyAircraftModel(_broker, _radioState);

            _broker.RaiseAircraftAdded(new AircraftAddedEventArgs("BAW123", "B738", 51.49, -0.4614, 0, 0, 0, 0, 0, 0));
            Assert.Single(model.Current);

            _broker.RaiseAircraftDeleted(new AircraftDeletedEventArgs("BAW123"));

            Assert.Empty(model.Current);
        }

        [Fact]
        public void AircraftUpdated_MovesOutsideRadius_IsExcluded()
        {
            _radioState.Telemetry = new OwnshipTelemetry(true, 0, 0, 0, 0, 51.4775, -0.4614, System.DateTimeOffset.Now);
            var model = new NearbyAircraftModel(_broker, _radioState);

            _broker.RaiseAircraftAdded(new AircraftAddedEventArgs("BAW123", "B738", 51.49, -0.4614, 0, 0, 0, 0, 0, 0));
            Assert.Single(model.Current);

            _broker.RaiseAircraftUpdated(new AircraftUpdatedEventArgs("BAW123", 52.5, -0.4614, 0, 0, 0, 0, 0, 0));

            Assert.Empty(model.Current);
        }
    }
}
