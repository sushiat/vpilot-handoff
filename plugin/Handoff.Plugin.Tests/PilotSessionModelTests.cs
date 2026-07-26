using Xunit;

namespace Handoff.Plugin.Tests
{
    public class PilotSessionModelTests
    {
        [Fact]
        public void BeforeAnyConnection_CallsignAndCidAreNull()
        {
            var model = new PilotSessionModel();

            Assert.Null(model.Callsign);
            Assert.Null(model.Cid);
        }

        [Fact]
        public void OnNetworkConnected_SetsCallsignAndCid()
        {
            var model = new PilotSessionModel();

            model.OnNetworkConnected("BAW123", "1234567");

            Assert.Equal("BAW123", model.Callsign);
            Assert.Equal("1234567", model.Cid);
        }

        [Fact]
        public void OnNetworkConnected_RaisesChanged()
        {
            var model = new PilotSessionModel();
            var raised = false;
            model.Changed += (s, e) => raised = true;

            model.OnNetworkConnected("BAW123", "1234567");

            Assert.True(raised);
        }

        [Fact]
        public void OnDisconnected_ClearsCallsignAndCid()
        {
            var model = new PilotSessionModel();
            model.OnNetworkConnected("BAW123", "1234567");

            model.OnDisconnected();

            Assert.Null(model.Callsign);
            Assert.Null(model.Cid);
        }

        [Fact]
        public void OnDisconnected_WhenAlreadyClear_DoesNotRaiseChanged()
        {
            var model = new PilotSessionModel();
            var raised = false;
            model.Changed += (s, e) => raised = true;

            model.OnDisconnected();

            Assert.False(raised);
        }
    }
}
