using System;
using System.Collections.Concurrent;
using System.IO;
using System.IO.Pipes;
using System.Threading;
using Handoff.Plugin;
using Newtonsoft.Json;

namespace Handoff.RadioHost
{
    /// <summary>
    /// Standalone x64 process bridging SimConnect (COM1/COM2 frequency, Mode C transponder)
    /// to the vPilot plugin over two local named pipes. Split out from the plugin itself
    /// because CTrue.FsConnect's native simconnect.dll is x64-only, while vPilot's own
    /// process is x86 -- see plugin/README.md.
    ///
    /// Two one-directional pipes (state, command), not one duplex pipe -- see
    /// RadioIpcProtocol's doc comment for why: a blocking read and a concurrent write from a
    /// different thread on the same duplex PipeStream hangs indefinitely with .NET
    /// Framework's synchronous named pipe I/O, confirmed by direct local reproduction.
    ///
    /// Each pipe accepts one plugin connection at a time and runs its own independent accept
    /// loop, on its own thread. Both loops run forever regardless of whether a client is
    /// currently connected -- the SimConnect polling loop (RadioSimConnectClient) also keeps
    /// running so fresh state is ready immediately whenever the plugin (re)connects.
    /// </summary>
    internal static class Program
    {
        private static readonly object WriterGate = new object();
        private static StreamWriter _currentWriter;
        private static bool _loggedFirstWrite;

        // Commands are enqueued by the pipe-reading thread and processed one at a time on a
        // separate dedicated thread -- SimConnect calls aren't safe to make concurrently from
        // multiple threads, but a slow command (e.g. a tuning fallback that takes a while)
        // must never block the pipe reader itself from draining newer incoming commands.
        private static readonly BlockingCollection<RadioIpcMessage> CommandQueue = new BlockingCollection<RadioIpcMessage>();

        private static void Main(string[] args)
        {
            // A quick way to try a single SimConnect event/write/read against the live sim and
            // exit, with no vPilot/plugin involved at all -- see SimConnectTestTool for usage.
            // Much faster iteration than redeploying the whole thing to test one idea.
            if (args.Length > 0)
            {
                SimConnectTestTool.Run(args);
                return;
            }

            Logger.Log("Handoff.RadioHost starting, listening on pipes " + RadioIpcProtocol.StatePipeName + " / " + RadioIpcProtocol.CommandPipeName);
            var radio = new RadioSimConnectClient(OnRadioStateChanged, OnOwnshipTelemetryChanged);

            new Thread(() => ProcessCommandQueue(radio)) { Name = "Program.ProcessCommandQueue", IsBackground = true }.Start();
            new Thread(RunCommandServer) { Name = "Program.RunCommandServer", IsBackground = true }.Start();
            RunStateServer();
        }

        private static void RunStateServer()
        {
            while (true)
            {
                using (var pipeServer = new NamedPipeServerStream(RadioIpcProtocol.StatePipeName, PipeDirection.Out))
                {
                    pipeServer.WaitForConnection();
                    Logger.Log("Plugin connected to state pipe.");

                    var writer = new StreamWriter(pipeServer) { AutoFlush = true };
                    lock (WriterGate) { _currentWriter = writer; _loggedFirstWrite = false; }

                    // Nothing to actively do here -- OnRadioStateChanged (called from
                    // RadioSimConnectClient's own thread) writes to _currentWriter whenever
                    // new data arrives. Block until the pipe breaks (plugin disconnected).
                    try
                    {
                        while (pipeServer.IsConnected) Thread.Sleep(500);
                    }
                    catch (IOException ex)
                    {
                        Logger.Log("State pipe error: " + ex.Message);
                    }
                    finally
                    {
                        lock (WriterGate)
                        {
                            if (_currentWriter == writer) _currentWriter = null;
                        }
                    }

                    Logger.Log("Plugin disconnected from state pipe.");
                }
            }
        }

        private static void RunCommandServer()
        {
            while (true)
            {
                using (var pipeServer = new NamedPipeServerStream(RadioIpcProtocol.CommandPipeName, PipeDirection.In))
                {
                    pipeServer.WaitForConnection();
                    Logger.Log("Plugin connected to command pipe.");

                    using (var reader = new StreamReader(pipeServer))
                    {
                        try
                        {
                            RadioIpcMessage message;
                            while ((message = RadioIpcProtocol.ReadMessage(reader)) != null)
                            {
                                Logger.Log("Received command from plugin: type=" + message.Type + ", megahertz=" + message.Megahertz);
                                CommandQueue.Add(message);
                            }
                        }
                        catch (IOException ex)
                        {
                            Logger.Log("Command pipe error: " + ex.Message);
                        }
                    }

                    Logger.Log("Plugin disconnected from command pipe.");
                }
            }
        }

        private static void ProcessCommandQueue(RadioSimConnectClient radio)
        {
            foreach (var message in CommandQueue.GetConsumingEnumerable())
            {
                try
                {
                    switch (message.Type)
                    {
                        case RadioIpcMessage.TypeSetCom1Frequency:
                            if (message.Megahertz.HasValue) radio.SetCom1Frequency(message.Megahertz.Value);
                            break;
                        case RadioIpcMessage.TypeSetCom2Frequency:
                            if (message.Megahertz.HasValue) radio.SetCom2Frequency(message.Megahertz.Value);
                            break;
                        case RadioIpcMessage.TypeSetCom1StandbyFrequency:
                            if (message.Megahertz.HasValue) radio.SetCom1StandbyFrequency(message.Megahertz.Value);
                            break;
                        case RadioIpcMessage.TypeSetCom2StandbyFrequency:
                            if (message.Megahertz.HasValue) radio.SetCom2StandbyFrequency(message.Megahertz.Value);
                            break;
                        case RadioIpcMessage.TypeSetCom1ActiveAndStandbyFrequency:
                            if (message.Megahertz.HasValue && message.StandbyMegahertz.HasValue)
                                radio.SetCom1ActiveAndStandbyFrequency(message.Megahertz.Value, message.StandbyMegahertz.Value);
                            break;
                        case RadioIpcMessage.TypeSetCom2ActiveAndStandbyFrequency:
                            if (message.Megahertz.HasValue && message.StandbyMegahertz.HasValue)
                                radio.SetCom2ActiveAndStandbyFrequency(message.Megahertz.Value, message.StandbyMegahertz.Value);
                            break;
                        case RadioIpcMessage.TypeSetTransponderCode:
                            if (message.TransponderCode.HasValue) radio.SetTransponderCode(message.TransponderCode.Value);
                            break;
                        case RadioIpcMessage.TypeSelectCom1Transmitter:
                            radio.SelectCom1Transmitter();
                            break;
                        case RadioIpcMessage.TypeSelectCom2Transmitter:
                            radio.SelectCom2Transmitter();
                            break;
                        case RadioIpcMessage.TypeSetCom1ReceiveEnabled:
                            if (message.Com1ReceiveEnabled.HasValue) radio.SetCom1ReceiveEnabled(message.Com1ReceiveEnabled.Value);
                            break;
                        case RadioIpcMessage.TypeSetCom2ReceiveEnabled:
                            if (message.Com2ReceiveEnabled.HasValue) radio.SetCom2ReceiveEnabled(message.Com2ReceiveEnabled.Value);
                            break;
                        case RadioIpcMessage.TypeSetPollIntervals:
                            if (message.PollIntervalMs.HasValue && message.TelemetryPollIntervalMs.HasValue)
                                radio.SetPollIntervals(message.PollIntervalMs.Value, message.TelemetryPollIntervalMs.Value);
                            break;
                    }
                }
                catch (Exception ex)
                {
                    Logger.Log("Failed to apply command from plugin: " + ex);
                }
            }
        }

        private static void OnRadioStateChanged(RadioState state)
        {
            lock (WriterGate)
            {
                if (_currentWriter == null) return;

                try
                {
                    var message = new RadioIpcMessage
                    {
                        Type = RadioIpcMessage.TypeRadioState,
                        Com1Frequency = state.Com1Frequency,
                        Com2Frequency = state.Com2Frequency,
                        Com1StandbyFrequency = state.Com1StandbyFrequency,
                        Com2StandbyFrequency = state.Com2StandbyFrequency,
                        ModeCEnabled = state.ModeCEnabled,
                        TransponderCode = state.TransponderCode,
                        Com1TransmitEnabled = state.Com1TransmitEnabled,
                        Com2TransmitEnabled = state.Com2TransmitEnabled,
                        Com1ReceiveEnabled = state.Com1ReceiveEnabled,
                        Com2ReceiveEnabled = state.Com2ReceiveEnabled
                    };
                    RadioIpcProtocol.WriteMessage(_currentWriter, message);
                    if (!_loggedFirstWrite)
                    {
                        _loggedFirstWrite = true;
                        Logger.Log("Wrote first radio state message to pipe: " + JsonConvert.SerializeObject(message));
                    }
                }
                catch (Exception ex)
                {
                    Logger.Log("Failed writing to pipe client: " + ex);
                    _currentWriter = null;
                }
            }
        }

        private static void OnOwnshipTelemetryChanged(OwnshipTelemetry telemetry)
        {
            lock (WriterGate)
            {
                if (_currentWriter == null) return;

                try
                {
                    var message = new RadioIpcMessage
                    {
                        Type = RadioIpcMessage.TypeOwnshipTelemetry,
                        OnGround = telemetry.OnGround,
                        GroundSpeedKnots = telemetry.GroundSpeedKnots,
                        AltitudeAboveGroundFeet = telemetry.AltitudeAboveGroundFeet,
                        VerticalSpeedFpm = telemetry.VerticalSpeedFpm,
                        HeadingDegrees = telemetry.HeadingDegrees,
                        Latitude = telemetry.Latitude,
                        Longitude = telemetry.Longitude,
                        PressureAltitudeFeet = telemetry.PressureAltitudeFeet,
                        SeaLevelPressureHpa = telemetry.SeaLevelPressureHpa
                    };
                    RadioIpcProtocol.WriteMessage(_currentWriter, message);
                }
                catch (Exception ex)
                {
                    Logger.Log("Failed writing telemetry to pipe client: " + ex);
                    _currentWriter = null;
                }
            }
        }
    }
}
