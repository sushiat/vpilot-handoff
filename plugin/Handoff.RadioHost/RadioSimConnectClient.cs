using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using CTrue.FsConnect;
using Handoff.Plugin;
using Microsoft.FlightSimulator.SimConnect;

namespace Handoff.RadioHost
{
    /// <summary>
    /// Live SimConnect connection for ownship radio state -- COM1/COM2 tuned frequency
    /// (read + write) and Mode C transponder state (read-only). Runs as its own x64 process
    /// (Handoff.RadioHost) since CTrue.FsConnect's native simconnect.dll is x64-only and
    /// vPilot's own process is x86 -- see plugin/README.md for the full story.
    ///
    /// Threading: no window handle needed (confirmed via FsConnect's own docs). Runs its own
    /// background poll loop and invokes onStateChanged whenever a new reading comes in;
    /// Program.cs forwards that to whichever pipe client is currently connected, if any.
    /// </summary>
    internal sealed class RadioSimConnectClient
    {
        private const int PollIntervalMs = 1000;

        private enum Requests
        {
            RadioSimVars,
            Com1Write,
            Com2Write
        }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct RadioSimVars
        {
            public double Com1FrequencyMhz;
            public double Com2FrequencyMhz;
            public int TransponderState;
        }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct Com1FrequencyWrite
        {
            public double Com1FrequencyMhz;
        }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct Com2FrequencyWrite
        {
            public double Com2FrequencyMhz;
        }

        // TRANSPONDER STATE:1 enum value for altitude-reporting ("Alt"/Mode C) mode.
        private const int TransponderStateAlt = 4;

        private readonly FsConnect _fsConnect;
        private readonly Action<RadioState> _onStateChanged;
        private volatile bool _dataDefinitionsRegistered;

        public RadioSimConnectClient(Action<RadioState> onStateChanged)
        {
            _onStateChanged = onStateChanged ?? throw new ArgumentNullException(nameof(onStateChanged));

            _fsConnect = new FsConnect { SimConnectFileLocation = SimConnectFileLocation.Local };
            _fsConnect.FsDataReceived += OnFsDataReceived;

            new Thread(ReadFromSimConnect) { Name = "RadioSimConnectClient.ReadFromSimConnect", IsBackground = true }.Start();
        }

        public void SetCom1Frequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            _fsConnect.UpdateData(Requests.Com1Write, new Com1FrequencyWrite { Com1FrequencyMhz = megahertz });
        }

        public void SetCom2Frequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            _fsConnect.UpdateData(Requests.Com2Write, new Com2FrequencyWrite { Com2FrequencyMhz = megahertz });
        }

        private void OnFsDataReceived(object sender, FsDataReceivedEventArgs e)
        {
            foreach (var simConnectObject in e.Data)
            {
                if (simConnectObject is RadioSimVars radioSimVars)
                {
                    var next = new RadioState(
                        RadioFrequency.ToVatsimCompressed(radioSimVars.Com1FrequencyMhz),
                        RadioFrequency.ToVatsimCompressed(radioSimVars.Com2FrequencyMhz),
                        radioSimVars.TransponderState == TransponderStateAlt,
                        DateTimeOffset.Now);

                    _onStateChanged(next);
                }
            }
        }

        private void ReadFromSimConnect()
        {
            var veryFirstConnectError = true;
            while (true)
            {
                try
                {
                    if (!_fsConnect.Connected)
                    {
                        try
                        {
                            _fsConnect.Connect("Handoff", "localhost", 0, SimConnectProtocol.Ipv4);

                            _fsConnect.RegisterDataDefinition<RadioSimVars>(Requests.RadioSimVars, new List<SimVar>
                            {
                                new SimVar("COM ACTIVE FREQUENCY:1", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                                new SimVar("COM ACTIVE FREQUENCY:2", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                                new SimVar("TRANSPONDER STATE:1", "Enum", SIMCONNECT_DATATYPE.INT32)
                            });
                            _fsConnect.RegisterDataDefinition<Com1FrequencyWrite>(Requests.Com1Write, new List<SimVar>
                            {
                                new SimVar("COM ACTIVE FREQUENCY:1", "MHz", SIMCONNECT_DATATYPE.FLOAT64)
                            });
                            _fsConnect.RegisterDataDefinition<Com2FrequencyWrite>(Requests.Com2Write, new List<SimVar>
                            {
                                new SimVar("COM ACTIVE FREQUENCY:2", "MHz", SIMCONNECT_DATATYPE.FLOAT64)
                            });
                            _dataDefinitionsRegistered = true;

                            veryFirstConnectError = true;
                        }
                        catch (Exception ex)
                        {
                            // SimConnect throws when the sim isn't running -- expected while
                            // waiting for MSFS to start. Only log the first occurrence so this
                            // doesn't spam while idle.
                            if (veryFirstConnectError)
                            {
                                veryFirstConnectError = false;
                                Debug.WriteLine("RadioSimConnectClient: error connecting to sim: " + ex);
                            }
                        }
                    }

                    if (_fsConnect.Connected && _dataDefinitionsRegistered)
                    {
                        _fsConnect.RequestData(Requests.RadioSimVars, Requests.RadioSimVars);
                        Thread.Sleep(PollIntervalMs);
                    }
                    else
                    {
                        Thread.Sleep(TimeSpan.FromSeconds(30));
                    }
                }
                catch (Exception ex)
                {
                    Debug.WriteLine("RadioSimConnectClient: error in read loop: " + ex);
                    Thread.Sleep(TimeSpan.FromSeconds(5));
                }
            }
        }
    }
}
