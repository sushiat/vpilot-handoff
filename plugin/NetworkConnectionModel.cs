using System;
using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;
using RossCarlson.Vatsim.Vpilot.Plugins.Exceptions;

namespace Handoff.Plugin
{
    /// <summary>
    /// Issue #171 -- connects to / disconnects from the VATSIM network on a client's request,
    /// through IBroker.RequestConnect/RequestConnectAsObserver/RequestDisconnect. Those calls
    /// return nothing: the outcome arrives later as IBroker.NetworkConnected/NetworkDisconnected,
    /// which this model turns into the finish of the operationProgress operation the client
    /// started (docs/protocol.md's connectNetwork / disconnectNetwork).
    ///
    /// The operationId is minted by the client (like saveDebugSnapshot's snapshotId) because
    /// operationProgress is broadcast to every paired client, and the requesting client has to
    /// recognise the one operation that is its own. At most one connect and one disconnect are
    /// tracked at a time -- IBroker itself refuses a second connect while one is up.
    /// </summary>
    public sealed class NetworkConnectionModel
    {
        private readonly object _gate = new object();
        private readonly IBroker _broker;
        private readonly OperationProgressModel _operationProgress;
        private readonly Action<string> _logDebug;
        private string _pendingConnectOperationId;
        private string _pendingDisconnectOperationId;

        public NetworkConnectionModel(IBroker broker, OperationProgressModel operationProgress, Action<string> logDebug = null)
        {
            _broker = broker ?? throw new ArgumentNullException(nameof(broker));
            _operationProgress = operationProgress ?? throw new ArgumentNullException(nameof(operationProgress));
            _logDebug = logDebug;

            _broker.NetworkConnected += OnNetworkConnected;
            _broker.NetworkDisconnected += OnNetworkDisconnected;
            _broker.SessionEnded += OnNetworkDisconnected;
        }

        /// <summary>Starts a connect. Always resolves the operation eventually: immediately on a
        /// validation failure or an IBroker exception, otherwise from NetworkConnected (success) or
        /// NetworkDisconnected (the attempt failed or was dropped). If neither event ever arrives
        /// the client's own 60s operationProgress timeout (docs/protocol.md) is the backstop.</summary>
        public void Connect(string operationId, string callsign, string typeCode, string selcalCode, bool observer)
        {
            if (string.IsNullOrWhiteSpace(operationId))
            {
                Log("Ignoring connectNetwork with no operationId.");
                return;
            }

            callsign = callsign?.Trim().ToUpperInvariant();
            typeCode = typeCode?.Trim().ToUpperInvariant();
            // SELCAL is four letters; drop any dash/space a client left in ("AB-CD" -> "ABCD").
            selcalCode = selcalCode == null ? null : new string(selcalCode.ToUpperInvariant().Where(char.IsLetterOrDigit).ToArray());
            if (selcalCode == string.Empty) selcalCode = null;

            if (string.IsNullOrEmpty(callsign))
            {
                Fail(operationId, "A callsign is required to connect.");
                return;
            }

            if (!observer && string.IsNullOrEmpty(typeCode))
            {
                Fail(operationId, "An aircraft type is required to connect.");
                return;
            }

            lock (_gate) { _pendingConnectOperationId = operationId; }
            _operationProgress.Report(operationId, "Connecting to VATSIM...");

            try
            {
                if (observer) _broker.RequestConnectAsObserver(callsign);
                else _broker.RequestConnect(callsign, typeCode, selcalCode);
                Log("Requested connect as " + callsign + (observer ? " (observer)" : " (" + typeCode + ")"));
            }
            catch (AlreadyConnectedException)
            {
                // Not an error: the pilot's goal ("be on the network") already holds. Finishing
                // successfully lets the client's dialog close and show the live state.
                ClearPendingConnect(operationId);
                _operationProgress.Finish(operationId, "Already connected to VATSIM.", success: true);
            }
            catch (SimNotReadyException)
            {
                ClearPendingConnect(operationId);
                _operationProgress.Finish(operationId, "vPilot isn't connected to the simulator yet.", success: false);
            }
            catch (Exception ex)
            {
                Log("RequestConnect threw: " + ex);
                ClearPendingConnect(operationId);
                _operationProgress.Finish(operationId, "Couldn't connect: " + ex.Message, success: false);
            }
        }

        /// <summary>Starts a disconnect; resolved from NetworkDisconnected, or immediately when
        /// vPilot reports it isn't connected (nothing left to do, so that counts as success).</summary>
        public void Disconnect(string operationId)
        {
            if (string.IsNullOrWhiteSpace(operationId))
            {
                Log("Ignoring disconnectNetwork with no operationId.");
                return;
            }

            lock (_gate) { _pendingDisconnectOperationId = operationId; }
            _operationProgress.Report(operationId, "Disconnecting from VATSIM...");

            try
            {
                _broker.RequestDisconnect();
                Log("Requested disconnect.");
            }
            catch (NotConnectedException)
            {
                ClearPendingDisconnect(operationId);
                _operationProgress.Finish(operationId, "Not connected to VATSIM.", success: true);
            }
            catch (Exception ex)
            {
                Log("RequestDisconnect threw: " + ex);
                ClearPendingDisconnect(operationId);
                _operationProgress.Finish(operationId, "Couldn't disconnect: " + ex.Message, success: false);
            }
        }

        private void OnNetworkConnected(object sender, NetworkConnectedEventArgs e)
        {
            string operationId;
            lock (_gate)
            {
                operationId = _pendingConnectOperationId;
                _pendingConnectOperationId = null;
            }

            if (operationId != null)
                _operationProgress.Finish(operationId, "Connected to VATSIM as " + e.Callsign + ".", success: true);
        }

        private void OnNetworkDisconnected(object sender, EventArgs e)
        {
            string connectId, disconnectId;
            lock (_gate)
            {
                connectId = _pendingConnectOperationId;
                disconnectId = _pendingDisconnectOperationId;
                _pendingConnectOperationId = null;
                _pendingDisconnectOperationId = null;
            }

            // A disconnect while a connect is still pending means the attempt did not make it
            // onto the network (rejected, or dropped during the handshake).
            if (connectId != null)
                _operationProgress.Finish(connectId, "The connection to VATSIM was closed before it completed.", success: false);
            if (disconnectId != null)
                _operationProgress.Finish(disconnectId, "Disconnected from VATSIM.", success: true);
        }

        private void Fail(string operationId, string status)
        {
            _operationProgress.Finish(operationId, status, success: false);
        }

        private void ClearPendingConnect(string operationId)
        {
            lock (_gate) { if (_pendingConnectOperationId == operationId) _pendingConnectOperationId = null; }
        }

        private void ClearPendingDisconnect(string operationId)
        {
            lock (_gate) { if (_pendingDisconnectOperationId == operationId) _pendingDisconnectOperationId = null; }
        }

        private void Log(string message)
        {
            _logDebug?.Invoke("NetworkConnectionModel: " + message);
        }
    }
}
