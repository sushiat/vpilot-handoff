using System;
using System.Collections.Generic;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using RossCarlson.Vatsim.Vpilot.Plugins.Exceptions;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class NetworkConnectionModelTests
    {
        private static (FakeBroker Broker, NetworkConnectionModel Model, List<OperationProgressEventArgs> Events) Create()
        {
            var broker = new FakeBroker();
            var progress = new OperationProgressModel();
            var events = new List<OperationProgressEventArgs>();
            progress.Changed += (s, e) => events.Add(e);
            return (broker, new NetworkConnectionModel(broker, progress), events);
        }

        private static NetworkConnectedEventArgs Connected(string callsign) =>
            new NetworkConnectedEventArgs("1234567", callsign, "A320", null, false);

        [Fact]
        public void Connect_NormalizesAndForwardsToBroker_ThenReportsInProgress()
        {
            var (broker, model, events) = Create();

            model.Connect("op1", " eidgx ", "a320", " ab-cd ", observer: false);

            Assert.Equal(new[] { ("EIDGX", "A320", "AB-CD") }, broker.ConnectRequests);
            var last = events[events.Count - 1];
            Assert.Equal("op1", last.OperationId);
            Assert.False(last.Finished);
        }

        [Fact]
        public void Connect_BlankSelcal_IsPassedAsNull()
        {
            var (broker, model, _) = Create();

            model.Connect("op1", "EIDGX", "A320", "  ", observer: false);

            Assert.Null(broker.ConnectRequests[0].SelcalCode);
        }

        [Fact]
        public void Connect_AsObserver_UsesObserverCall_AndNeedsNoType()
        {
            var (broker, model, _) = Create();

            model.Connect("op1", "EIDGX_OBS", null, null, observer: true);

            Assert.Empty(broker.ConnectRequests);
            Assert.Equal(new[] { "EIDGX_OBS" }, broker.ObserverConnectRequests);
        }

        [Fact]
        public void Connect_NetworkConnected_FinishesSuccessfully()
        {
            var (broker, model, events) = Create();
            model.Connect("op1", "EIDGX", "A320", null, observer: false);

            broker.RaiseNetworkConnected(Connected("EIDGX"));

            var last = events[events.Count - 1];
            Assert.Equal("op1", last.OperationId);
            Assert.True(last.Finished);
            Assert.True(last.Success);
            Assert.Contains("EIDGX", last.Status);
        }

        [Fact]
        public void NetworkConnected_WithNothingPending_ReportsNothing()
        {
            var (broker, _, events) = Create();

            broker.RaiseNetworkConnected(Connected("EIDGX"));

            Assert.Empty(events);
        }

        [Fact]
        public void Connect_MissingCallsign_FailsWithoutCallingBroker()
        {
            var (broker, model, events) = Create();

            model.Connect("op1", "  ", "A320", null, observer: false);

            Assert.Empty(broker.ConnectRequests);
            var last = events[events.Count - 1];
            Assert.True(last.Finished);
            Assert.False(last.Success);
        }

        [Fact]
        public void Connect_MissingType_FailsUnlessObserver()
        {
            var (broker, model, events) = Create();

            model.Connect("op1", "EIDGX", "", null, observer: false);

            Assert.Empty(broker.ConnectRequests);
            Assert.False(events[events.Count - 1].Success);
        }

        [Fact]
        public void Connect_WithoutOperationId_IsIgnored()
        {
            var (broker, model, events) = Create();

            model.Connect(null, "EIDGX", "A320", null, observer: false);

            Assert.Empty(broker.ConnectRequests);
            Assert.Empty(events);
        }

        [Fact]
        public void Connect_AlreadyConnected_FinishesAsSuccess()
        {
            var (broker, model, events) = Create();
            broker.RequestException = new AlreadyConnectedException();

            model.Connect("op1", "EIDGX", "A320", null, observer: false);

            var last = events[events.Count - 1];
            Assert.True(last.Finished);
            Assert.True(last.Success);
            Assert.Contains("Already connected", last.Status);
        }

        [Fact]
        public void Connect_SimNotReady_FinishesAsFailureWithClearReason()
        {
            var (broker, model, events) = Create();
            broker.RequestException = new SimNotReadyException();

            model.Connect("op1", "EIDGX", "A320", null, observer: false);

            var last = events[events.Count - 1];
            Assert.True(last.Finished);
            Assert.False(last.Success);
            Assert.Contains("simulator", last.Status);
        }

        [Fact]
        public void Connect_ThenDisconnectedBeforeConnected_FailsTheConnect()
        {
            var (broker, model, events) = Create();
            model.Connect("op1", "EIDGX", "A320", null, observer: false);

            broker.RaiseNetworkDisconnected();

            var last = events[events.Count - 1];
            Assert.Equal("op1", last.OperationId);
            Assert.True(last.Finished);
            Assert.False(last.Success);
        }

        [Fact]
        public void Disconnect_NetworkDisconnected_FinishesSuccessfully()
        {
            var (broker, model, events) = Create();

            model.Disconnect("op2");
            broker.RaiseNetworkDisconnected();

            Assert.Equal(1, broker.DisconnectRequestCount);
            var last = events[events.Count - 1];
            Assert.Equal("op2", last.OperationId);
            Assert.True(last.Finished);
            Assert.True(last.Success);
        }

        [Fact]
        public void Disconnect_NotConnected_FinishesAsSuccess()
        {
            var (broker, model, events) = Create();
            broker.RequestException = new NotConnectedException();

            model.Disconnect("op2");

            var last = events[events.Count - 1];
            Assert.True(last.Finished);
            Assert.True(last.Success);
        }

        [Fact]
        public void Disconnect_UnexpectedFailure_FinishesAsFailure()
        {
            var (broker, model, events) = Create();
            broker.RequestException = new InvalidOperationException("boom");

            model.Disconnect("op2");

            var last = events[events.Count - 1];
            Assert.True(last.Finished);
            Assert.False(last.Success);
            Assert.Contains("boom", last.Status);
        }
    }
}
