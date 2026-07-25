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
        private RadioState _current = new RadioState(null, null, null, null, false, null, DateTimeOffset.Now);
        private OwnshipTelemetry _telemetry = new OwnshipTelemetry(null, null, null, null, null, null, null, DateTimeOffset.Now);
        private StreamWriter _writer;
        private volatile bool _running;
        private bool _loggedFirstState;

        public event EventHandler Changed;

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

        public void Start()
        {
            lock (_lifecycleGate)
            {
                if (_running) return;
                _running = true;

                EnsureRadioHostRunning();
                new Thread(ReadFromRadioHost) { Name = "RadioStateModel.ReadFromRadioHost", IsBackground = true }.Start();
            }
        }

        public void Stop()
        {
            lock (_lifecycleGate)
            {
                if (!_running) return;
                _running = false;
            }

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

            _loggedFirstState = false;
            lock (_gate)
            {
                _writer = null;
                _current = new RadioState(null, null, null, null, false, null, DateTimeOffset.Now);
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

        public void SetTransponderCode(int squawk)
        {
            TransponderCode.ValidateSquawkRange(squawk);
            SendCommand(new RadioIpcMessage { Type = RadioIpcMessage.TypeSetTransponderCode, TransponderCode = squawk });
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
            var radioHostPath = Path.Combine(pluginDirectory ?? ".", "RadioHost", "Handoff.RadioHost.exe");

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

                        var writer = new StreamWriter(commandPipe) { AutoFlush = true };
                        var reader = new StreamReader(statePipe);
                        lock (_gate) { _writer = writer; }

                        RadioIpcMessage message;
                        while (_running && (message = RadioIpcProtocol.ReadMessage(reader)) != null)
                        {
                            if (!_loggedFirstState)
                            {
                                Log("Received message from Handoff.RadioHost, type=" + message.Type);
                            }

                            if (message.Type == RadioIpcMessage.TypeRadioState)
                            {
                                var next = new RadioState(message.Com1Frequency, message.Com2Frequency, message.Com1StandbyFrequency, message.Com2StandbyFrequency, message.ModeCEnabled ?? false, message.TransponderCode, DateTimeOffset.Now);
                                lock (_gate) { _current = next; }

                                if (!_loggedFirstState)
                                {
                                    _loggedFirstState = true;
                                    Log($"First radio state received: Com1={next.Com1Frequency}, Com2={next.Com2Frequency}, Com1Standby={next.Com1StandbyFrequency}, Com2Standby={next.Com2StandbyFrequency}, ModeC={next.ModeCEnabled}, Xpdr={next.TransponderCode}");
                                }

                                Changed?.Invoke(this, EventArgs.Empty);
                            }
                            else if (message.Type == RadioIpcMessage.TypeOwnshipTelemetry)
                            {
                                var next = new OwnshipTelemetry(message.OnGround, message.GroundSpeedKnots, message.AltitudeAboveGroundFeet, message.VerticalSpeedFpm, message.HeadingDegrees, message.Latitude, message.Longitude, DateTimeOffset.Now);
                                lock (_gate) { _telemetry = next; }
                                Changed?.Invoke(this, EventArgs.Empty);
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
