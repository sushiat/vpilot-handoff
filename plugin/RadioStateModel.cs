using System;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Reflection;
using System.Threading;

namespace Handoff.Plugin
{
    /// <summary>
    /// Client for ownship radio state (COM1/COM2 tuned frequency, Mode C transponder) and
    /// raw ownship telemetry (on-ground, speed, position -- see OwnshipTelemetry), both
    /// served by the separate Handoff.RadioHost process over the same local named pipe.
    ///
    /// Why a separate process: CTrue.FsConnect's native simconnect.dll is x64-only, but
    /// vPilot's own process is x86 (confirmed by direct PE-header inspection against a real
    /// vPilot install) -- an x64 assembly simply cannot load into vPilot at all. Rather than
    /// depend on vPilot's own bundled 2007-era legacy SimConnect assembly (not ours to rely
    /// on, and a different, incompatible SDK generation from anything else available), the
    /// SimConnect integration runs in its own x64 helper process, spawned here and talked to
    /// over IPC. See plugin/README.md for the full story.
    ///
    /// Lifecycle: tied to the VATSIM connection (Start/Stop called from HandoffPlugin on
    /// IBroker.NetworkConnected/NetworkDisconnected/SessionEnded, matching the pattern used
    /// by vPilot-Pushover), not the plugin's own load lifetime -- radio state isn't needed
    /// before connecting, and this also means the helper process actually exits, rather than
    /// running forever with no way to stop it (IPlugin has no unload hook at all).
    /// </summary>
    public sealed class RadioStateModel : IRadioStateModel
    {
        private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(2);
        private static readonly TimeSpan ReconnectDelay = TimeSpan.FromSeconds(5);

        private readonly object _gate = new object();
        private readonly object _lifecycleGate = new object();
        private readonly Action<string> _logDebug;
        private RadioState _current = new RadioState(null, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
        private OwnshipTelemetry _telemetry = new OwnshipTelemetry(null, null, null, null, null, null, null, DateTimeOffset.Now);
        private StreamWriter _writer;
        private volatile bool _running;
        private volatile bool _radioHostConnected;
        private volatile bool _simulatorConnected;
        private bool _loggedFirstState;

        public event EventHandler Changed;

        /// <summary>Creates the model, initially disconnected from Handoff.RadioHost.</summary>
        /// <param name="logDebug">
        /// Typically IBroker.PostDebugMessage, so lifecycle events show up in vPilot's
        /// /dbgwin window -- Debug.WriteLine alone isn't visible without attaching a
        /// debugger, which is off-limits while connected to VATSIM. Optional so this class
        /// stays usable without an IBroker (e.g. in tests) if that's ever needed.
        /// </param>
        public RadioStateModel(Action<string> logDebug = null)
        {
            _logDebug = logDebug;
        }

        public RadioState Current
        {
            get { lock (_gate) { return _current; } }
        }

        /// <summary>
        /// Raw ownship telemetry, as last received -- no phase-of-flight interpretation
        /// applied yet, see OwnshipTelemetry.
        /// </summary>
        public OwnshipTelemetry Telemetry
        {
            get { lock (_gate) { return _telemetry; } }
        }

        public bool IsRadioHostConnected => _radioHostConnected;

        public bool IsSimulatorConnected => _simulatorConnected;

        /// <summary>Issue #65 -- full internal radio/telemetry state for the debug snapshot file. Current/Telemetry are already public; this just bundles them with the connection flags in one call.</summary>
        public RadioDebugSnapshot BuildDebugSnapshot()
        {
            lock (_gate)
            {
                return new RadioDebugSnapshot(_radioHostConnected, _simulatorConnected, _current, _telemetry);
            }
        }

        public void Start()
        {
            lock (_lifecycleGate)
            {
                if (_running) return;
                _running = true;

                // EnsureRadioHostRunning's named-pipe probe blocks for up to ConnectTimeout (2s)
                // waiting for a "nothing's listening" timeout on a cold start, plus a
                // Process.Start call -- both now run on this same background thread rather than
                // synchronously here. Start() is called directly from IBroker.NetworkConnected
                // (see HandoffPlugin), so anything run inline here would block vPilot's own event
                // dispatch for however long the probe/process-spawn takes, right at the moment
                // the pilot connects to VATSIM.
                new Thread(() =>
                {
                    EnsureRadioHostRunning();
                    ReadFromRadioHost();
                })
                { Name = "RadioStateModel.ReadFromRadioHost", IsBackground = true }.Start();
            }
        }

        public void Stop()
        {
            lock (_lifecycleGate)
            {
                if (!_running) return;
                _running = false;
            }

            // Process enumeration + Kill() is avoidable blocking work on whatever thread called
            // Stop() -- IBroker.NetworkDisconnected/SessionEnded (see HandoffPlugin) -- so it
            // runs on its own background thread rather than inline here, same reasoning as
            // Start() above.
            new Thread(() =>
            {
                foreach (var process in Process.GetProcessesByName("Handoff.RadioHost"))
                {
                    try
                    {
                        process.Kill();
                        Log("Stopped Handoff.RadioHost (PID " + process.Id + ").");
                    }
                    catch (Exception ex)
                    {
                        Log("Failed to stop Handoff.RadioHost (PID " + process.Id + "): " + ex.Message);
                    }
                }
            })
            { Name = "RadioStateModel.StopRadioHost", IsBackground = true }.Start();

            _loggedFirstState = false;
            _radioHostConnected = false;
            _simulatorConnected = false;
            lock (_gate)
            {
                _writer = null;
                _current = new RadioState(null, null, null, null, false, null, false, false, false, false, DateTimeOffset.Now);
                _telemetry = new OwnshipTelemetry(null, null, null, null, null, null, null, DateTimeOffset.Now);
            }
            Changed?.Invoke(this, EventArgs.Empty);
        }

        public void SetCom1Frequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSetCom1Frequency, Megahertz = megahertz });
        }

        public void SetCom2Frequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSetCom2Frequency, Megahertz = megahertz });
        }

        public void SetCom1StandbyFrequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSetCom1StandbyFrequency, Megahertz = megahertz });
        }

        public void SetCom2StandbyFrequency(double megahertz)
        {
            RadioFrequency.ValidateAirbandRange(megahertz);
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSetCom2StandbyFrequency, Megahertz = megahertz });
        }

        /// <summary>
        /// Sets active and standby together as a single IPC round trip -- e.g. a "transfer"
        /// (activate a just-tuned frequency while preserving whatever was previously active into
        /// standby) or a plain swap. One command instead of two separate Set*Frequency calls
        /// avoids each being queued/settle-waited independently on Handoff.RadioHost's single
        /// command-processing thread, which otherwise lands the two writes over a second apart
        /// -- see RadioSimConnectClient.SetCom1ActiveAndStandbyFrequency.
        /// </summary>
        public void SetCom1ActiveAndStandbyFrequency(double activeMegahertz, double standbyMegahertz)
        {
            RadioFrequency.ValidateAirbandRange(activeMegahertz);
            RadioFrequency.ValidateAirbandRange(standbyMegahertz);
            SendCommand(new RadioIpcMessage
            {
                Type = RadioIpcMessage.TypeSetCom1ActiveAndStandbyFrequency,
                Megahertz = activeMegahertz,
                StandbyMegahertz = standbyMegahertz
            });
        }

        public void SetCom2ActiveAndStandbyFrequency(double activeMegahertz, double standbyMegahertz)
        {
            RadioFrequency.ValidateAirbandRange(activeMegahertz);
            RadioFrequency.ValidateAirbandRange(standbyMegahertz);
            SendCommand(new RadioIpcMessage
            {
                Type = RadioIpcMessage.TypeSetCom2ActiveAndStandbyFrequency,
                Megahertz = activeMegahertz,
                StandbyMegahertz = standbyMegahertz
            });
        }

        public void SetTransponderCode(int squawk)
        {
            TransponderCode.ValidateSquawkRange(squawk);
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSetTransponderCode, TransponderCode = squawk });
        }

        /// <summary>
        /// COM transmitter-select/receive-select write capability (issue #20), wired to the
        /// client-facing selectCom1Transmitter/selectCom2Transmitter/setCom1ReceiveEnabled/
        /// setCom2ReceiveEnabled WebSocket commands by HandoffWebSocketServer (issue #29's
        /// MIC/MON buttons).
        /// </summary>
        public void SelectCom1Transmitter()
        {
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSelectCom1Transmitter });
        }

        public void SelectCom2Transmitter()
        {
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSelectCom2Transmitter });
        }

        public void SetCom1ReceiveEnabled(bool enabled)
        {
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSetCom1ReceiveEnabled, Com1ReceiveEnabled = enabled });
        }

        public void SetCom2ReceiveEnabled(bool enabled)
        {
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSetCom2ReceiveEnabled, Com2ReceiveEnabled = enabled });
        }

        private void SendCommand(RadioIpcMessage message)
        {
            lock (_gate)
            {
                if (_writer == null)
                {
                    Log("Dropped outgoing command, not connected to Handoff.RadioHost: " + message.Type);
                    return;
                }
                try
                {
                    RadioIpcProtocol.WriteMessage(_writer, message);
                }
                catch (IOException ex)
                {
                    Log("Failed sending command to Handoff.RadioHost: " + ex.Message);
                }
            }
        }

        private void EnsureRadioHostRunning()
        {
            try
            {
                using (var probe = new NamedPipeClientStream(".", RadioIpcProtocol.StatePipeName, PipeDirection.In))
                {
                    probe.Connect((int)ConnectTimeout.TotalMilliseconds);
                    Log("Handoff.RadioHost is already running, reusing it.");
                    return; // Already running (e.g. didn't get cleanly stopped last time) -- reuse it.
                }
            }
            catch (Exception)
            {
                // Nothing listening -- expected. Fall through and spawn it.
            }

            var pluginDirectory = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
            var radioHostPath = PathJoin.Combine(pluginDirectory ?? ".", "RadioHost", "Handoff.RadioHost.exe");

            if (!File.Exists(radioHostPath))
            {
                Log("Handoff.RadioHost.exe not found at " + radioHostPath + " -- radio state will be unavailable.");
                return;
            }

            try
            {
                Log("Starting Handoff.RadioHost: " + radioHostPath);
                var process = Process.Start(new ProcessStartInfo
                {
                    FileName = radioHostPath,
                    UseShellExecute = false,
                    CreateNoWindow = true
                });
                Log(process != null ? "Handoff.RadioHost started, PID " + process.Id : "Process.Start returned no process for Handoff.RadioHost.");
            }
            catch (Exception ex)
            {
                Log("Failed to start Handoff.RadioHost: " + ex);
            }
        }

        private void ReadFromRadioHost()
        {
            while (_running)
            {
                try
                {
                    // Two one-directional pipes, not one duplex pipe -- a blocking read on one
                    // thread concurrent with a write from another thread on the same duplex
                    // PipeStream hangs indefinitely under .NET Framework's synchronous named
                    // pipe I/O (confirmed by direct local reproduction). See
                    // RadioIpcProtocol's doc comment.
                    using (var statePipe = new NamedPipeClientStream(".", RadioIpcProtocol.StatePipeName, PipeDirection.In))
                    using (var commandPipe = new NamedPipeClientStream(".", RadioIpcProtocol.CommandPipeName, PipeDirection.Out))
                    {
                        statePipe.Connect((int)ConnectTimeout.TotalMilliseconds);
                        commandPipe.Connect((int)ConnectTimeout.TotalMilliseconds);
                        Log("Connected to Handoff.RadioHost.");
                        _radioHostConnected = true;

                        var writer = new StreamWriter(commandPipe) { AutoFlush = true };
                        lock (_gate) { _writer = writer; }

                        using (var reader = new StreamReader(statePipe))
                        {
                            RadioIpcMessage message;
                            while (_running && (message = RadioIpcProtocol.ReadMessage(reader)) != null)
                            {
                                if (!_loggedFirstState)
                                {
                                    Log("Received message from Handoff.RadioHost, type=" + message.Type);
                                }

                                if (message.Type == RadioIpcMessage.TypeRadioState)
                                {
                                    var next = new RadioState(
                                        message.Com1Frequency, message.Com2Frequency, message.Com1StandbyFrequency, message.Com2StandbyFrequency,
                                        message.ModeCEnabled ?? false, message.TransponderCode,
                                        message.Com1TransmitEnabled ?? false, message.Com2TransmitEnabled ?? false,
                                        message.Com1ReceiveEnabled ?? false, message.Com2ReceiveEnabled ?? false,
                                        DateTimeOffset.Now);
                                    lock (_gate) { _current = next; }
                                    _simulatorConnected = true;

                                    if (!_loggedFirstState)
                                    {
                                        _loggedFirstState = true;
                                        Log($"First radio state received: Com1={next.Com1Frequency}, Com2={next.Com2Frequency}, Com1Standby={next.Com1StandbyFrequency}, Com2Standby={next.Com2StandbyFrequency}, ModeC={next.ModeCEnabled}, Xpdr={next.TransponderCode}");
                                    }

                                    Changed?.Invoke(this, EventArgs.Empty);
                                }
                                else if (message.Type == RadioIpcMessage.TypeOwnshipTelemetry)
                                {
                                    var next = new OwnshipTelemetry(message.OnGround, message.GroundSpeedKnots, message.AltitudeAboveGroundFeet, message.VerticalSpeedFpm, message.HeadingDegrees, message.Latitude, message.Longitude, DateTimeOffset.Now, message.PressureAltitudeFeet, message.SeaLevelPressureHpa);
                                    lock (_gate) { _telemetry = next; }
                                    Changed?.Invoke(this, EventArgs.Empty);
                                }
                            }
                        }

                        if (_running) Log("Handoff.RadioHost closed the pipe (ReadMessage returned null).");
                    }
                }
                catch (Exception ex)
                {
                    if (_running) Log("Handoff.RadioHost connection error: " + ex.Message);
                }
                finally
                {
                    lock (_gate) { _writer = null; }
                    _radioHostConnected = false;
                    _simulatorConnected = false;
                }

                if (_running) Thread.Sleep(ReconnectDelay);
            }
        }

        private void Log(string message)
        {
            var line = "RadioStateModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
