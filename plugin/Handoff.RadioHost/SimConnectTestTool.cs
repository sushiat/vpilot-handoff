using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using CTrue.FsConnect;
using Microsoft.FlightSimulator.SimConnect;

namespace Handoff.RadioHost
{
    /// <summary>
    /// Command-line test mode: connect to the live sim, fire one thing, read back, exit.
    /// Entirely separate from the plugin/vPilot IPC path (see Program.Main) -- for quickly
    /// trying a single SimConnect idea without a full plugin redeploy each time.
    ///
    /// Usage (run Handoff.RadioHost.exe directly from a terminal on the machine running MSFS):
    ///   Handoff.RadioHost.exe test-event &lt;eventName&gt; &lt;dwData&gt;
    ///     Fires a client event with a raw dwData value (decimal, or hex with a 0x prefix).
    ///     e.g. test-event COM_STBY_RADIO_SET 0x2280
    ///   Handoff.RadioHost.exe test-write &lt;simVarName&gt; &lt;units&gt; &lt;value&gt;
    ///     Raw SetDataOnSimObject write of a double value to a named SimVar (object 0/user).
    ///     e.g. test-write "COM STANDBY FREQUENCY:1" MHz 122.8
    ///   Handoff.RadioHost.exe test-event-priority &lt;eventName&gt; &lt;dwData&gt;
    ///     Same as test-event, but transmits via the raw underlying SimConnect object with
    ///     SIMCONNECT_EVENT_FLAG.GROUPID_IS_PRIORITY instead of through a registered
    ///     notification group -- matches how the main RadioSimConnectClient sends its events,
    ///     to isolate whether that detail (rather than the event itself) matters.
    ///   Handoff.RadioHost.exe test-event-raw &lt;eventName&gt; &lt;dwData&gt;
    ///     Same idea, but bypasses CTrue.FsConnect for the *entire* event pipeline (map, add to
    ///     notification group, set priority, transmit), not just the transmit call -- in case
    ///     FsConnect's own registration wrapper (built for FSX/P3D-era SimConnect) is itself
    ///     the problem, not just which transmit overload/flag gets used.
    ///   Handoff.RadioHost.exe test-read
    ///     Dumps current COM1/2 active/standby frequency and transponder code/state once.
    ///
    /// All modes subscribe to the raw SimConnect's OnRecvException, printing the actual
    /// SIMCONNECT_EXCEPTION code for any rejected call -- previously invisible, since neither
    /// CTrue.FsConnect nor our own code wired this up at all.
    /// </summary>
    internal static class SimConnectTestTool
    {
        private static readonly FieldInfo RawSimConnectField =
            typeof(FsConnect).GetField("_simConnect", BindingFlags.NonPublic | BindingFlags.Instance);

        // Large, arbitrary offset -- client event/notification group/data definition IDs
        // turned out NOT to be scoped per-connection: they collide across every concurrently
        // connected SimConnect client (confirmed via EVENT_ID_DUPLICATE/UNRECOGNIZED_ID on
        // OnRecvException once that was finally wired up). Starting at 0 collided with the
        // main RadioSimConnectClient's own IDs (also starting at 0) whenever both were
        // connected at once, silently corrupting every test run until now.
        private const int IdBase = 50000;

        private enum Requests
        {
            Read = IdBase,
            Write = IdBase + 1
        }

        private enum Events
        {
            Test = IdBase + 2
        }

        private enum Groups
        {
            Test = IdBase + 3
        }

        private enum Priority
        {
            Highest = 1
        }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct ReadVars
        {
            public double Com1FrequencyMhz;
            public double Com2FrequencyMhz;
            public double Com1StandbyFrequencyMhz;
            public double Com2StandbyFrequencyMhz;
            public int TransponderState;
            public int TransponderCodeBcd;
        }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct WriteVar
        {
            public double Value;
        }

        public static void Run(string[] args)
        {
            using var fsConnect = new FsConnect { SimConnectFileLocation = SimConnectFileLocation.Local };

            // The main RadioSimConnectClient retries its own Connect() every 5s in a loop and
            // silently swallows failures until one succeeds -- meaning occasional E_FAIL
            // attempts likely happen there too, just never surfaced. Retry here as well rather
            // than treating one transient failure as fatal.
            Console.WriteLine("Connecting to SimConnect...");
            var connectDeadline = DateTime.UtcNow.AddSeconds(20);
            while (!fsConnect.Connected && DateTime.UtcNow < connectDeadline)
            {
                try
                {
                    // Deliberately the same app name as the main RadioSimConnectClient
                    // ("Handoff") rather than a distinct "HandoffTest" -- no SimConnect.cfg
                    // file exists on this machine, but there could be a registry-based config
                    // keyed by name we haven't checked, and this is a one-line way to rule that
                    // out given the main process's identical connect call works fine.
                    fsConnect.Connect("Handoff", 0);
                }
                catch (Exception ex)
                {
                    Console.WriteLine("Connect attempt failed: " + ex.Message + " -- retrying...");
                    Thread.Sleep(2000);
                    continue;
                }

                var waitDeadline = DateTime.UtcNow.AddSeconds(3);
                while (!fsConnect.Connected && DateTime.UtcNow < waitDeadline) Thread.Sleep(100);
            }

            if (!fsConnect.Connected)
            {
                Console.WriteLine("Failed to connect within 20s -- is MSFS running with a flight loaded?");
                return;
            }
            Console.WriteLine("Connected.");

            var rawSimConnect = RawSimConnectField?.GetValue(fsConnect) as SimConnect;
            if (rawSimConnect != null)
            {
                rawSimConnect.OnRecvException += (sender, data) =>
                {
                    var code = (SIMCONNECT_EXCEPTION)data.dwException;
                    Console.WriteLine($"*** SimConnect exception: {code} (sendId={data.dwSendID}, index={data.dwIndex})");
                };
            }
            else
            {
                Console.WriteLine("(Could not reach raw SimConnect for exception reporting.)");
            }

            try
            {
                switch (args[0])
                {
                    case "test-event":
                        RunTestEvent(fsConnect, args[1], args[2]);
                        break;
                    case "test-event-priority":
                        RunTestEventPriority(fsConnect, args[1], args[2]);
                        break;
                    case "test-event-raw":
                        RunTestEventRaw(rawSimConnect, fsConnect, args[1], args[2]);
                        break;
                    case "test-write":
                        RunTestWrite(fsConnect, args[1], args[2], double.Parse(args[3], CultureInfo.InvariantCulture));
                        break;
                    case "test-read":
                        RunTestRead(fsConnect);
                        break;
                    default:
                        Console.WriteLine("Unknown mode '" + args[0] + "'. Use test-event, test-event-priority, test-event-raw, test-write, or test-read.");
                        break;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine("Test failed: " + ex);
            }

            try { fsConnect.Disconnect(); } catch { /* best effort */ }
        }

        private static void RunTestEvent(FsConnect fsConnect, string eventName, string dwDataText)
        {
            var dwData = ParseUInt(dwDataText);
            Console.WriteLine("Before:");
            DumpRead(fsConnect);

            fsConnect.MapClientEventToSimEvent(Groups.Test, Events.Test, eventName);
            fsConnect.SetNotificationGroupPriority(Groups.Test);

            Console.WriteLine($"Firing {eventName} with dwData=0x{dwData:X} ({dwData})...");
            fsConnect.TransmitClientEvent(Events.Test, dwData, Groups.Test);

            Thread.Sleep(1500);
            Console.WriteLine("After:");
            DumpRead(fsConnect);
        }

        private static void RunTestEventPriority(FsConnect fsConnect, string eventName, string dwDataText)
        {
            var dwData = ParseUInt(dwDataText);
            Console.WriteLine("Before:");
            DumpRead(fsConnect);

            fsConnect.MapClientEventToSimEvent(Groups.Test, Events.Test, eventName);

            var rawSimConnect = RawSimConnectField?.GetValue(fsConnect) as SimConnect;
            if (rawSimConnect == null)
            {
                Console.WriteLine("Could not reach raw SimConnect via reflection -- aborting.");
                return;
            }

            Console.WriteLine($"Firing {eventName} with dwData=0x{dwData:X} ({dwData}) via GROUPID_IS_PRIORITY...");
            rawSimConnect.TransmitClientEvent((uint)SIMCONNECT_SIMOBJECT_TYPE.USER, Events.Test, dwData, Priority.Highest, SIMCONNECT_EVENT_FLAG.GROUPID_IS_PRIORITY);

            Thread.Sleep(1500);
            Console.WriteLine("After:");
            DumpRead(fsConnect);
        }

        /// <summary>Bypasses CTrue.FsConnect for the whole event pipeline, not just the
        /// transmit call -- map, add-to-notification-group, set-priority, and transmit all go
        /// through the raw SimConnect object directly, in case FsConnect's own registration
        /// wrapper (not just which transmit overload we used) is the actual problem.</summary>
        private static void RunTestEventRaw(SimConnect rawSimConnect, FsConnect fsConnect, string eventName, string dwDataText)
        {
            if (rawSimConnect == null)
            {
                Console.WriteLine("Could not reach raw SimConnect via reflection -- aborting.");
                return;
            }

            var dwData = ParseUInt(dwDataText);
            Console.WriteLine("Before:");
            DumpRead(fsConnect);

            Console.WriteLine("Mapping, adding to notification group, and setting priority via raw SimConnect...");
            rawSimConnect.MapClientEventToSimEvent(Events.Test, eventName);
            rawSimConnect.AddClientEventToNotificationGroup(Groups.Test, Events.Test, false);
            rawSimConnect.SetNotificationGroupPriority(Groups.Test, SimConnect.SIMCONNECT_GROUP_PRIORITY_HIGHEST);
            Thread.Sleep(200);

            Console.WriteLine($"Firing {eventName} with dwData=0x{dwData:X} ({dwData}), fully raw pipeline...");
            rawSimConnect.TransmitClientEvent((uint)SIMCONNECT_SIMOBJECT_TYPE.USER, Events.Test, dwData, Groups.Test, SIMCONNECT_EVENT_FLAG.DEFAULT);

            Thread.Sleep(1500);
            Console.WriteLine("After:");
            DumpRead(fsConnect);
        }

        private static void RunTestWrite(FsConnect fsConnect, string simVarName, string units, double value)
        {
            Console.WriteLine("Before:");
            DumpRead(fsConnect);

            fsConnect.RegisterDataDefinition<WriteVar>(Requests.Write, new List<SimVar>
            {
                new SimVar(simVarName, units, SIMCONNECT_DATATYPE.FLOAT64)
            });

            Console.WriteLine($"Writing {simVarName} = {value} {units}...");
            fsConnect.UpdateData(Requests.Write, new WriteVar { Value = value }, 0);

            Thread.Sleep(1500);
            Console.WriteLine("After:");
            DumpRead(fsConnect);
        }

        private static void RunTestRead(FsConnect fsConnect)
        {
            DumpRead(fsConnect);
        }

        private static void DumpRead(FsConnect fsConnect)
        {
            ReadVars? result = null;
            void Handler(object sender, FsDataReceivedEventArgs e)
            {
                foreach (var vars in e.Data.OfType<ReadVars>())
                {
                    result = vars;
                }
            }

            fsConnect.RegisterDataDefinition<ReadVars>(Requests.Read, new List<SimVar>
            {
                new SimVar("COM ACTIVE FREQUENCY:1", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                new SimVar("COM ACTIVE FREQUENCY:2", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                new SimVar("COM STANDBY FREQUENCY:1", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                new SimVar("COM STANDBY FREQUENCY:2", "MHz", SIMCONNECT_DATATYPE.FLOAT64),
                new SimVar("TRANSPONDER STATE:1", "Enum", SIMCONNECT_DATATYPE.INT32),
                new SimVar("TRANSPONDER CODE:1", "BCO16", SIMCONNECT_DATATYPE.INT32)
            });

            fsConnect.FsDataReceived += Handler;
            fsConnect.RequestData(Requests.Read, Requests.Read);

            var deadline = DateTime.UtcNow.AddSeconds(3);
            while (result == null && DateTime.UtcNow < deadline) Thread.Sleep(50);
            fsConnect.FsDataReceived -= Handler;

            if (result == null)
            {
                Console.WriteLine("  (no data received)");
                return;
            }

            var v = result.Value;
            Console.WriteLine($"  COM1 active={v.Com1FrequencyMhz} standby={v.Com1StandbyFrequencyMhz}");
            Console.WriteLine($"  COM2 active={v.Com2FrequencyMhz} standby={v.Com2StandbyFrequencyMhz}");
            Console.WriteLine($"  Transponder state={v.TransponderState} codeBcd=0x{v.TransponderCodeBcd:X4}");
        }

        private static uint ParseUInt(string text)
        {
            if (text.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            {
                return Convert.ToUInt32(text.Substring(2), 16);
            }
            return uint.Parse(text, CultureInfo.InvariantCulture);
        }
    }
}
