using System;
using RossCarlson.Vatsim.Vpilot.Plugins;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;

namespace Handoff.Plugin.Tests
{
    /// <summary>
    /// Minimal fake IBroker for tests. Only the controller events are meaningfully
    /// wired (with Raise* helpers); every outbound method throws, since nothing under
    /// test in this PR calls them — ControllerStateModel only ever listens.
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

        public void RequestConnect(string callsign, string typeCode, string selcalCode) => throw new NotImplementedException();
        public void RequestConnectAsObserver(string callsign) => throw new NotImplementedException();
        public void RequestDisconnect() => throw new NotImplementedException();
        public void RequestMetar(string station) => throw new NotImplementedException();
        public void RequestAtis(string callsign) => throw new NotImplementedException();
        public void SendPrivateMessage(string to, string message) => throw new NotImplementedException();
        public void SendRadioMessage(string message) => throw new NotImplementedException();
        public void PostDebugMessage(string message) { }
        public void SetModeC(bool modeC) => throw new NotImplementedException();
        public void SquawkIdent() => throw new NotImplementedException();
        public void SetPtt(bool pressed) => throw new NotImplementedException();
    }
#pragma warning restore CS0067
}
