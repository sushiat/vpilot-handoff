using System;
using System.Collections.Generic;
using RossCarlson.Vatsim.Vpilot.Plugins;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;

namespace Handoff.Plugin.Tests
{
    /// <summary>
    /// Minimal fake IBroker for tests. Controller, chat/SELCAL and network events are meaningfully
    /// wired (with Raise* helpers); SendPrivateMessage/SendRadioMessage and the Request*
    /// connect/disconnect calls record as spies for ChatModel/NetworkConnectionModel tests. Everything else still throws, since nothing under test
    /// calls it.
    /// </summary>
#pragma warning disable CS0067 // interface-mandated events with no test use yet
    internal sealed class FakeBroker : IBroker
    {
        public event EventHandler SessionEnded;
        public event EventHandler<NetworkConnectedEventArgs> NetworkConnected;
        public event EventHandler NetworkDisconnected;
        public event EventHandler<PrivateMessageReceivedEventArgs> PrivateMessageReceived;
        public event EventHandler<RadioMessageReceivedEventArgs> RadioMessageReceived;
        public event EventHandler<BroadcastMessageReceivedEventArgs> BroadcastMessageReceived;
        public event EventHandler<MetarReceivedEventArgs> MetarReceived;
        public event EventHandler<AtisReceivedEventArgs> AtisReceived;
        public event EventHandler<ControllerAddedEventArgs> ControllerAdded;
        public event EventHandler<ControllerDeletedEventArgs> ControllerDeleted;
        public event EventHandler<ControllerFrequencyChangedEventArgs> ControllerFrequencyChanged;
        public event EventHandler<ControllerLocationChangedEventArgs> ControllerLocationChanged;
        public event EventHandler<SelcalAlertReceivedEventArgs> SelcalAlertReceived;
        public event EventHandler<AircraftAddedEventArgs> AircraftAdded;
        public event EventHandler<AircraftUpdatedEventArgs> AircraftUpdated;
        public event EventHandler<AircraftDeletedEventArgs> AircraftDeleted;

        public void RaiseControllerAdded(ControllerAddedEventArgs e) => ControllerAdded?.Invoke(this, e);
        public void RaiseControllerDeleted(ControllerDeletedEventArgs e) => ControllerDeleted?.Invoke(this, e);
        public void RaiseControllerFrequencyChanged(ControllerFrequencyChangedEventArgs e) => ControllerFrequencyChanged?.Invoke(this, e);
        public void RaiseControllerLocationChanged(ControllerLocationChangedEventArgs e) => ControllerLocationChanged?.Invoke(this, e);

        public void RaiseAircraftAdded(AircraftAddedEventArgs e) => AircraftAdded?.Invoke(this, e);
        public void RaiseAircraftUpdated(AircraftUpdatedEventArgs e) => AircraftUpdated?.Invoke(this, e);
        public void RaiseAircraftDeleted(AircraftDeletedEventArgs e) => AircraftDeleted?.Invoke(this, e);

        public void RaisePrivateMessageReceived(PrivateMessageReceivedEventArgs e) => PrivateMessageReceived?.Invoke(this, e);
        public void RaiseRadioMessageReceived(RadioMessageReceivedEventArgs e) => RadioMessageReceived?.Invoke(this, e);
        public void RaiseBroadcastMessageReceived(BroadcastMessageReceivedEventArgs e) => BroadcastMessageReceived?.Invoke(this, e);
        public void RaiseSelcalAlertReceived(SelcalAlertReceivedEventArgs e) => SelcalAlertReceived?.Invoke(this, e);

        public List<(string To, string Message)> SentPrivateMessages { get; } = new List<(string, string)>();
        public List<string> SentRadioMessages { get; } = new List<string>();

        public List<(string Callsign, string TypeCode, string SelcalCode)> ConnectRequests { get; } = new List<(string, string, string)>();
        public List<string> ObserverConnectRequests { get; } = new List<string>();
        public int DisconnectRequestCount { get; private set; }
        // When set, the matching Request* call throws it instead of recording -- lets tests drive
        // the AlreadyConnected/SimNotReady/NotConnected paths.
        public Exception RequestException { get; set; }

        public void RaiseNetworkConnected(NetworkConnectedEventArgs e) => NetworkConnected?.Invoke(this, e);
        public void RaiseNetworkDisconnected() => NetworkDisconnected?.Invoke(this, EventArgs.Empty);
        public void RaiseSessionEnded() => SessionEnded?.Invoke(this, EventArgs.Empty);

        public void RequestConnect(string callsign, string typeCode, string selcalCode)
        {
            if (RequestException != null) throw RequestException;
            ConnectRequests.Add((callsign, typeCode, selcalCode));
        }

        public void RequestConnectAsObserver(string callsign)
        {
            if (RequestException != null) throw RequestException;
            ObserverConnectRequests.Add(callsign);
        }

        public void RequestDisconnect()
        {
            if (RequestException != null) throw RequestException;
            DisconnectRequestCount++;
        }
        public void RequestMetar(string station) => throw new NotImplementedException();
        public void RequestAtis(string callsign) => throw new NotImplementedException();
        public void SendPrivateMessage(string to, string message) => SentPrivateMessages.Add((to, message));
        public void SendRadioMessage(string message) => SentRadioMessages.Add(message);
        public void PostDebugMessage(string message) { }
        public void SetModeC(bool modeC) => throw new NotImplementedException();
        public int SquawkIdentCount { get; private set; }
        public void SquawkIdent()
        {
            if (RequestException != null) throw RequestException;
            SquawkIdentCount++;
        }
        public void SetPtt(bool pressed) => throw new NotImplementedException();
    }
#pragma warning restore CS0067
}
