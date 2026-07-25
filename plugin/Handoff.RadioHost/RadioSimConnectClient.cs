using System;
using System.Collections.Generic;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using CTrue.FsConnect;
using Handoff.Plugin;
using Microsoft.FlightSimulator.SimConnect;

namespace Handoff.RadioHost
{
    /// <summary>
    /// Live SimConnect connection for ownship radio state -- COM1/COM2 active + standby
    /// frequency (read + write) and transponder code + Mode C state (read + write code). Runs
    /// as its own x64 process (Handoff.RadioHost) since CTrue.FsConnect's native simconnect.dll
    /// is x64-only and vPilot's own process is x86 -- see plugin/README.md for the full story.
    ///
    /// Writes go through absolute SimConnect client events -- COM_RADIO_SET_HZ / COM2_RADIO_SET_HZ
    /// (active), COM_STBY_RADIO_SET_HZ / COM2_STBY_RADIO_SET_HZ (standby), XPNDR_SET (BCD16,
    /// transponder). Direct SetDataOnSimObject writes on COM ACTIVE/STANDBY FREQUENCY reliably
    /// throw SIMCONNECT_EXCEPTION_DATA_ERROR -- confirmed live, those vars are genuinely
    /// read-only by design, not a bug on our end.
    ///
    /// A long night's earlier finding that these same absolute events "did nothing" turned out
    /// to be a completely unrelated bug, not aircraft behavior: client event/notification
    /// group/data definition IDs are NOT scoped per SimConnect connection -- they collide
    /// across every concurrently connected client. This class and SimConnectTestTool both
    /// defaulted their ID enums to start at 0, so running the test tool alongside this class's
    /// own long-running connection silently corrupted both (confirmed live via OnRecvException:
    /// EVENT_ID_DUPLICATE / UNRECOGNIZED_ID, previously invisible since nothing surfaced
    /// rejected calls at all). IdBase below gives this class's IDs a large, arbitrary offset,
    /// distinct from SimConnectTestTool's own offset, so the two can never collide with each
    /// other or anything else that happens to default to low numbers too.
    ///
    /// Threading: no window handle needed (confirmed via FsConnect's own docs). Runs its own
    /// background poll loop and invokes onStateChanged whenever a new reading comes in;
    /// Program.cs forwards that to whichever pipe client is currently connected, if any.
    /// </summary>
    internal sealed class RadioSimConnectClient
    {
        private const int PollIntervalMs = 1000;

        // Exceeds PollIntervalMs so a fresh reading is available when verifying a write took
        // effect.
        private const int SettleWaitMs = 1100;

        // Large, arbitrary offset -- see class doc comment. Distinct from SimConnectTestTool's
        // own offset (50000).
        private const int IdBase = 90000;

        private enum Requests
        {
            RadioSimVars = IdBase
        }

        // MapClientEventToSimEvent/TransmitClientEvent/SetNotificationGroupPriority overload
        // resolution needs matching Enum types for both the event and group arguments -- a
        // plain int group ID doesn't resolve against the (Enum, Enum, ...) overloads.
        private enum Groups
        {
            Radio = IdBase + 100
        }

        private enum Events
        {
            SetCom1FrequencyHz = IdBase + 200,
            SetCom2FrequencyHz,
            SetCom1StandbyFrequencyHz,
            SetCom2StandbyFrequencyHz,
            SetTransponderCode
        }

        // SIMCONNECT_GROUP_PRIORITY_HIGHEST. Used with SIMCONNECT_EVENT_FLAG.GROUPID_IS_PRIORITY
        // below -- CTrue.FsConnect's own TransmitClientEvent wrapper hardcodes
        // SIMCONNECT_EVENT_FLAG.DEFAULT with no way to pick a different flag, so this bypasses
        // it via reflection onto the same underlying connection.
        private enum Priority
        {
            Highest = 1
        }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct RadioSimVars
        {
            public double Com1FrequencyMhz;
            public double Com2FrequencyMhz;
            public double Com1StandbyFrequencyMhz;
            public double Com2StandbyFrequencyMhz;
            public int TransponderState;
            public int TransponderCodeBcd;
        }

        // TRANSPONDER STATE:1 enum value for altitude-reporting ("Alt"/Mode C) mode.
        private const int TransponderStateAlt = 4;

        private static readonly FieldInfo RawSimConnectField =
            typeof(FsConnect).GetField("_simConnect", BindingFlags.NonPublic | BindingFlags.Instance);

        private readonly FsConnect _fsConnect;
        private readonly Action<RadioState> _onStateChanged;
        private volatile bool _dataDefinitionsRegistered;
        private bool _loggedFirstState;
        private SimConnect _rawSimConnect;

        // Live raw readings, updated on every SimConnect poll -- used to verify a write
        // actually took effect.
        // Not volatile -- C# disallows volatile on 8-byte types. Torn reads aren't a concern in
        // practice here (x64 process, and worst case is one stale poll-interval's read).
        private double _lastCom1FrequencyMhz;
        private double _lastCom2FrequencyMhz;
        private double _lastCom1StandbyFrequencyMhz;
        private double _lastCom2StandbyFrequencyMhz;
        private int _lastTransponderCodeBcd;

        public RadioSimConnectClient(Action<RadioState> onStateChanged)
        {
            _onStateChanged = onStateChanged ?? throw new ArgumentNullException(nameof(onStateChanged));

            _fsConnect = new FsConnect { SimConnectFileLocation = SimConnectFileLocation.Local };
            _fsConnect.FsDataReceived += OnFsDataReceived;

            Logger.Log("RadioSimConnectClient starting.");
            new Thread(ReadFromSimConnect) { Name = "RadioSimConnectClient.ReadFromSimConnect", IsBackground = true }.Start();
        }

        public void SetCom1Frequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            SetFrequencyViaEvent("COM1 active", megahertz, Events.SetCom1FrequencyHz, () => _lastCom1FrequencyMhz);
        }

        public void SetCom2Frequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            SetFrequencyViaEvent("COM2 active", megahertz, Events.SetCom2FrequencyHz, () => _lastCom2FrequencyMhz);
        }

        public void SetCom1StandbyFrequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            SetFrequencyViaEvent("COM1 standby", megahertz, Events.SetCom1StandbyFrequencyHz, () => _lastCom1StandbyFrequencyMhz);
        }

        public void SetCom2StandbyFrequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            SetFrequencyViaEvent("COM2 standby", megahertz, Events.SetCom2StandbyFrequencyHz, () => _lastCom2StandbyFrequencyMhz);
        }

        public void SetTransponderCode(int squawk)
        {
            Handoff.Plugin.TransponderCode.ValidateSquawkRange(squawk);
            var targetBcd = Handoff.Plugin.TransponderCode.ToBcd(squawk);

            Logger.Log("Setting transponder code via XPNDR_SET: " + squawk + " (bcd=0x" + targetBcd.ToString("X4") + ")");
            TransmitPriorityEvent(Events.SetTransponderCode, (uint)targetBcd);

            Thread.Sleep(SettleWaitMs);
            Logger.Log("Transponder code now reads 0x" + _lastTransponderCodeBcd.ToString("X4") + " (target 0x" + targetBcd.ToString("X4") + ").");
        }

        private void SetFrequencyViaEvent(string label, double megahertz, Events eventId, Func<double> readCurrent)
        {
            var hertz = (uint)Math.Round(megahertz * 1_000_000);
            Logger.Log("Setting " + label + " frequency via SimConnect event: " + megahertz + " MHz (hz=" + hertz + ")");
            TransmitPriorityEvent(eventId, hertz);

            Thread.Sleep(SettleWaitMs);
            Logger.Log(label + " now reads " + readCurrent() + " MHz (target " + megahertz + ").");
        }

        /// <summary>Transmits via the raw underlying SimConnect object (reached through
        /// CTrue.FsConnect's private field, since its own TransmitClientEvent wrapper hardcodes
        /// SIMCONNECT_EVENT_FLAG.DEFAULT) using GROUPID_IS_PRIORITY instead -- the GroupID
        /// parameter is then treated as a literal priority value rather than a registered
        /// notification group. Falls back to the normal wrapper call if the private field can't
        /// be reached for any reason (e.g. a future FsConnect version renames it).</summary>
        private void TransmitPriorityEvent(Events eventId, uint dwData)
        {
            if (_rawSimConnect == null)
            {
                _rawSimConnect = RawSimConnectField?.GetValue(_fsConnect) as SimConnect;
            }

            if (_rawSimConnect == null)
            {
                Logger.Log("Could not reach raw SimConnect via reflection -- using normal TransmitClientEvent for " + eventId + ".");
                _fsConnect.TransmitClientEvent(eventId, dwData, Groups.Radio);
                return;
            }

            _rawSimConnect.TransmitClientEvent((uint)SIMCONNECT_SIMOBJECT_TYPE.USER, eventId, dwData, Priority.Highest, SIMCONNECT_EVENT_FLAG.GROUPID_IS_PRIORITY);
        }

        private void OnFsDataReceived(object sender, FsDataReceivedEventArgs e)
        {
            foreach (var simConnectObject in e.Data)
            {
                if (simConnectObject is RadioSimVars radioSimVars)
                {
                    _lastCom1FrequencyMhz = radioSimVars.Com1FrequencyMhz;
                    _lastCom2FrequencyMhz = radioSimVars.Com2FrequencyMhz;
                    _lastCom1StandbyFrequencyMhz = radioSimVars.Com1StandbyFrequencyMhz;
                    _lastCom2StandbyFrequencyMhz = radioSimVars.Com2StandbyFrequencyMhz;
                    _lastTransponderCodeBcd = radioSimVars.TransponderCodeBcd;

                    var next = new RadioState(
                        RadioFrequency.ToVatsimCompressed(radioSimVars.Com1FrequencyMhz),
                        RadioFrequency.ToVatsimCompressed(radioSimVars.Com2FrequencyMhz),
                        RadioFrequency.ToVatsimCompressed(radioSimVars.Com1StandbyFrequencyMhz),
                        RadioFrequency.ToVatsimCompressed(radioSimVars.Com2StandbyFrequencyMhz),
                        radioSimVars.TransponderState == TransponderStateAlt,
                        Handoff.Plugin.TransponderCode.FromBcd(radioSimVars.TransponderCodeBcd),
                        DateTimeOffset.Now);

                    if (!_loggedFirstState)
                    {
                        _loggedFirstState = true;
                        Logger.Log($"First SimConnect radio data received: Com1={radioSimVars.Com1FrequencyMhz}, Com2={radioSimVars.Com2FrequencyMhz}, Com1Stby={radioSimVars.Com1StandbyFrequencyMhz}, Com2Stby={radioSimVars.Com2StandbyFrequencyMhz}, TransponderState={radioSimVars.TransponderState}, TransponderCodeBcd=0x{radioSimVars.TransponderCodeBcd:X4} -> Com1={next.Com1Frequency}, Com2={next.Com2Frequency}, Com1Stby={next.Com1StandbyFrequency}, Com2Stby={next.Com2StandbyFrequency}, ModeC={next.ModeCEnabled}, Xpdr={next.TransponderCode}");
                    }

                    _onStateChanged(next);
                }
            }
        }

        private void ReadFromSimConnect()
        {
            while (true)
            {
                try
                {
                    if (!_fsConnect.Connected)
                    {
                        try
                        {
                            Logger.Log("Attempting SimConnect connection...");
                            _fsConnect.Connect("Handoff", 0);

                            _rawSimConnect = RawSimConnectField?.GetValue(_fsConnect) as SimConnect;
                            if (_rawSimConnect != null)
                            {
                                // Previously invisible -- neither CTrue.FsConnect nor this class
                                // surfaced rejected calls at all. This is what revealed the ID
                                // collision bug described in the class doc comment.
                                _rawSimConnect.OnRecvException += (sender, data) =>
                                    Logger.Log("SimConnect exception: " + (SIMCONNECT_EXCEPTION)data.dwException + " (sendId=" + data.dwSendID + ", index=" + data.dwIndex + ")");
                            }

                            _fsConnect.RegisterDataDefinition<RadioSimVars>(Requests.RadioSimVars, new List<SimVar>
                            {
                                new SimVar("COM ACTIVE FREQUENCY:1", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                                new SimVar("COM ACTIVE FREQUENCY:2", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                                new SimVar("COM STANDBY FREQUENCY:1", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                                new SimVar("COM STANDBY FREQUENCY:2", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                                new SimVar("TRANSPONDER STATE:1", "Enum", SIMCONNECT_DATATYPE.INT32),
                                new SimVar("TRANSPONDER CODE:1", "BCO16", SIMCONNECT_DATATYPE.INT32)
                            });

                            _fsConnect.MapClientEventToSimEvent(Groups.Radio, Events.SetCom1FrequencyHz, "COM_RADIO_SET_HZ");
                            _fsConnect.MapClientEventToSimEvent(Groups.Radio, Events.SetCom2FrequencyHz, "COM2_RADIO_SET_HZ");
                            _fsConnect.MapClientEventToSimEvent(Groups.Radio, Events.SetCom1StandbyFrequencyHz, "COM_STBY_RADIO_SET_HZ");
                            _fsConnect.MapClientEventToSimEvent(Groups.Radio, Events.SetCom2StandbyFrequencyHz, "COM2_STBY_RADIO_SET_HZ");
                            _fsConnect.MapClientEventToSimEvent(Groups.Radio, Events.SetTransponderCode, "XPNDR_SET");
                            _fsConnect.SetNotificationGroupPriority(Groups.Radio);

                            _dataDefinitionsRegistered = true;

                            Logger.Log("SimConnect connected, data definitions registered, events mapped.");
                        }
                        catch (Exception ex)
                        {
                            // SimConnect throws when the sim isn't running -- expected while
                            // waiting for MSFS to start.
                            Logger.Log("Error connecting to SimConnect: " + ex);
                        }
                    }

                    if (_fsConnect.Connected && _dataDefinitionsRegistered)
                    {
                        _fsConnect.RequestData(Requests.RadioSimVars, Requests.RadioSimVars);
                        Thread.Sleep(PollIntervalMs);
                    }
                    else
                    {
                        Thread.Sleep(TimeSpan.FromSeconds(5));
                    }
                }
                catch (Exception ex)
                {
                    // A call throwing here can leave the SimConnect handle in a broken state
                    // where .Connected still reports true but every subsequent call keeps
                    // throwing -- force a reconnect from scratch rather than retrying the same
                    // dead handle forever.
                    Logger.Log("Error in SimConnect read loop, forcing reconnect: " + ex);
                    _dataDefinitionsRegistered = false;
                    try
                    {
                        _fsConnect.Disconnect();
                    }
                    catch (Exception disconnectEx)
                    {
                        Logger.Log("Error disconnecting stale SimConnect handle: " + disconnectEx.Message);
                    }
                    Thread.Sleep(TimeSpan.FromSeconds(5));
                }
            }
        }
    }
}
