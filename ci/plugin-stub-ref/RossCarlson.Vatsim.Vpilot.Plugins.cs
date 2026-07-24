// Hand-reconstructed public API surface of RossCarlson.Vatsim.Vpilot.Plugins.dll,
// built from that assembly's own XML doc comments (RossCarlson.Vatsim.Vpilot.Plugins.xml).
// The real DLL is not redistributed here (see plugin/README.md); this stub exists only
// so CI can compile Handoff.Plugin.csproj without it. It carries no implementation —
// interfaces and empty method/ctor bodies only, mirroring what the real assembly's
// public metadata describes.
//
// Event delegate types (EventHandler<T>) follow standard .NET convention and were not
// verified against the real assembly's IL — confirm against the actual DLL before
// relying on exact event signatures when wiring up event handlers.

using System;
using System.Collections.Generic;

namespace RossCarlson.Vatsim.Vpilot.Plugins
{
    public interface IPlugin
    {
        string Name { get; }
        void Initialize(IBroker broker);
    }

    public interface IBroker
    {
        event EventHandler SessionEnded;
        event EventHandler<Events.NetworkConnectedEventArgs> NetworkConnected;
        event EventHandler NetworkDisconnected;
        event EventHandler<Events.PrivateMessageReceivedEventArgs> PrivateMessageReceived;
        event EventHandler<Events.RadioMessageReceivedEventArgs> RadioMessageReceived;
        event EventHandler<Events.BroadcastMessageReceivedEventArgs> BroadcastMessageReceived;
        event EventHandler<Events.MetarReceivedEventArgs> MetarReceived;
        event EventHandler<Events.AtisReceivedEventArgs> AtisReceived;
        event EventHandler<Events.ControllerAddedEventArgs> ControllerAdded;
        event EventHandler<Events.ControllerDeletedEventArgs> ControllerDeleted;
        event EventHandler<Events.ControllerFrequencyChangedEventArgs> ControllerFrequencyChanged;
        event EventHandler<Events.ControllerLocationChangedEventArgs> ControllerLocationChanged;
        event EventHandler<Events.SelcalAlertReceivedEventArgs> SelcalAlertReceived;
        event EventHandler<Events.AircraftAddedEventArgs> AircraftAdded;
        event EventHandler<Events.AircraftUpdatedEventArgs> AircraftUpdated;
        event EventHandler<Events.AircraftDeletedEventArgs> AircraftDeleted;

        void RequestConnect(string callsign, string typeCode, string selcalCode);
        void RequestConnectAsObserver(string callsign);
        void RequestDisconnect();
        void RequestMetar(string station);
        void RequestAtis(string callsign);
        void SendPrivateMessage(string to, string message);
        void SendRadioMessage(string message);
        void PostDebugMessage(string message);
        void SetModeC(bool modeC);
        void SquawkIdent();
        void SetPtt(bool pressed);
    }
}

namespace RossCarlson.Vatsim.Vpilot.Plugins.Events
{
    public class AircraftUpdatedEventArgs : EventArgs
    {
        public string Callsign { get; }
        public double Latitude { get; }
        public double Longitude { get; }
        public double Altitude { get; }
        public double PressureAltitude { get; }
        public double Pitch { get; }
        public double Bank { get; }
        public double Heading { get; }
        public double Speed { get; }

        public AircraftUpdatedEventArgs(string callsign, double lat, double lon, double alt, double pressureAlt, double pitch, double bank, double heading, double speed)
        {
            Callsign = callsign;
            Latitude = lat;
            Longitude = lon;
            Altitude = alt;
            PressureAltitude = pressureAlt;
            Pitch = pitch;
            Bank = bank;
            Heading = heading;
            Speed = speed;
        }
    }

    public class AircraftDeletedEventArgs : EventArgs
    {
        public string Callsign { get; }

        public AircraftDeletedEventArgs(string callsign)
        {
            Callsign = callsign;
        }
    }

    public class AircraftAddedEventArgs : EventArgs
    {
        public string Callsign { get; }
        public string TypeCode { get; }
        public double Latitude { get; }
        public double Longitude { get; }
        public double Altitude { get; }
        public double PressureAltitude { get; }
        public double Pitch { get; }
        public double Bank { get; }
        public double Heading { get; }
        public double Speed { get; }

        public AircraftAddedEventArgs(string callsign, string typeCode, double lat, double lon, double alt, double pressureAlt, double pitch, double bank, double heading, double speed)
        {
            Callsign = callsign;
            TypeCode = typeCode;
            Latitude = lat;
            Longitude = lon;
            Altitude = alt;
            PressureAltitude = pressureAlt;
            Pitch = pitch;
            Bank = bank;
            Heading = heading;
            Speed = speed;
        }
    }

    public class PrivateMessageReceivedEventArgs : EventArgs
    {
        public string From { get; }
        public string Message { get; }

        public PrivateMessageReceivedEventArgs(string from, string message)
        {
            From = from;
            Message = message;
        }
    }

    public class NetworkConnectedEventArgs : EventArgs
    {
        public string Cid { get; }
        public string Callsign { get; }
        public string TypeCode { get; }
        public string SelcalCode { get; }
        public bool ObserverMode { get; }

        public NetworkConnectedEventArgs(string cid, string callsign, string typeCode, string selcalCode, bool observerMode)
        {
            Cid = cid;
            Callsign = callsign;
            TypeCode = typeCode;
            SelcalCode = selcalCode;
            ObserverMode = observerMode;
        }
    }

    public class RadioMessageReceivedEventArgs : EventArgs
    {
        public int[] Frequencies { get; }
        public string From { get; }
        public string Message { get; }

        public RadioMessageReceivedEventArgs(int[] frequencies, string from, string message)
        {
            Frequencies = frequencies;
            From = from;
            Message = message;
        }
    }

    public class BroadcastMessageReceivedEventArgs : EventArgs
    {
        public string From { get; }
        public string Message { get; }

        public BroadcastMessageReceivedEventArgs(string from, string message)
        {
            From = from;
            Message = message;
        }
    }

    public class MetarReceivedEventArgs : EventArgs
    {
        public string Metar { get; }

        public MetarReceivedEventArgs(string metar)
        {
            Metar = metar;
        }
    }

    public class AtisReceivedEventArgs : EventArgs
    {
        public string From { get; }
        public List<string> Lines { get; }

        public AtisReceivedEventArgs(string from, List<string> lines)
        {
            From = from;
            Lines = lines;
        }
    }

    public class ControllerAddedEventArgs : EventArgs
    {
        public string Callsign { get; }
        public int Frequency { get; }
        public double Latitude { get; }
        public double Longitude { get; }

        public ControllerAddedEventArgs(string callsign, int frequency, double latitude, double longitude)
        {
            Callsign = callsign;
            Frequency = frequency;
            Latitude = latitude;
            Longitude = longitude;
        }
    }

    public class ControllerDeletedEventArgs : EventArgs
    {
        public string Callsign { get; }

        public ControllerDeletedEventArgs(string callsign)
        {
            Callsign = callsign;
        }
    }

    public class ControllerFrequencyChangedEventArgs : EventArgs
    {
        public string Callsign { get; }
        public int NewFrequency { get; }

        public ControllerFrequencyChangedEventArgs(string callsign, int newFrequency)
        {
            Callsign = callsign;
            NewFrequency = newFrequency;
        }
    }

    public class ControllerLocationChangedEventArgs : EventArgs
    {
        public string Callsign { get; }
        public double NewLatitude { get; }
        public double NewLongitude { get; }

        public ControllerLocationChangedEventArgs(string callsign, double newLatitude, double newLongitude)
        {
            Callsign = callsign;
            NewLatitude = newLatitude;
            NewLongitude = newLongitude;
        }
    }

    public class SelcalAlertReceivedEventArgs : EventArgs
    {
        public int[] Frequencies { get; }
        public string From { get; }

        public SelcalAlertReceivedEventArgs(int[] frequencies, string from)
        {
            Frequencies = frequencies;
            From = from;
        }
    }
}

namespace RossCarlson.Vatsim.Vpilot.Plugins.Exceptions
{
    public class AlreadyConnectedException : Exception
    {
    }

    public class SimNotReadyException : Exception
    {
    }

    public class NotTransmittingException : Exception
    {
    }

    public class NotConnectedException : Exception
    {
    }
}
