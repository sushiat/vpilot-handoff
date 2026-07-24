using System;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Reflection;
using System.Threading;

namespace Handoff.Plugin
{
    /// <summary>
    /// Client for ownship radio state (COM1/COM2 tuned frequency, Mode C transponder),
    /// served by the separate Handoff.RadioHost process over a local named pipe.
    ///
    /// Why a separate process: CTrue.FsConnect's native simconnect.dll is x64-only, but
    /// vPilot's own process is x86 (confirmed by direct PE-header inspection against a real
    /// vPilot install) -- an x64 assembly simply cannot load into vPilot at all. Rather than
    /// depend on vPilot's own bundled 2007-era legacy SimConnect assembly (not ours to rely
    /// on, and a different, incompatible SDK generation from anything else available), the
    /// SimConnect integration runs in its own x64 helper process, spawned here and talked to
    /// over IPC. See plugin/README.md for the full story.
    ///
    /// Threading: owns a background thread that (re)connects to the named pipe, reads
    /// newline-delimited JSON state updates, and forwards writes the other direction. No
    /// clean shutdown path -- IPlugin has no unload hook, same accepted limitation as
    /// elsewhere in this plugin.
    /// </summary>
    public sealed class RadioStateModel
    {
        private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(2);
        private static readonly TimeSpan ReconnectDelay = TimeSpan.FromSeconds(5);

        private readonly object _gate = new object();
        private readonly Action<string> _logDebug;
        private RadioState _current = new RadioState(null, null, false, DateTimeOffset.Now);
        private StreamWriter _writer;
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

            EnsureRadioHostRunning();

            new Thread(ReadFromRadioHost) { Name = "RadioStateModel.ReadFromRadioHost", IsBackground = true }.Start();
        }

        public RadioState Current
        {
            get { lock (_gate) { return _current; } }
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
                using (var probe = new NamedPipeClientStream(".", RadioIpcProtocol.PipeName, PipeDirection.InOut))
                {
                    probe.Connect((int)ConnectTimeout.TotalMilliseconds);
                    Log("Handoff.RadioHost is already running, reusing it.");
                    return; // Already running (a prior vPilot session's helper, or otherwise) -- reuse it.
                }
            }
            catch (Exception)
            {
                // Nothing listening -- expected on first launch. Fall through and spawn it.
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
            while (true)
            {
                try
                {
                    using (var pipe = new NamedPipeClientStream(".", RadioIpcProtocol.PipeName, PipeDirection.InOut))
                    {
                        pipe.Connect((int)ConnectTimeout.TotalMilliseconds);
                        Log("Connected to Handoff.RadioHost.");

                        var writer = new StreamWriter(pipe) { AutoFlush = true };
                        var reader = new StreamReader(pipe);
                        lock (_gate) { _writer = writer; }

                        RadioIpcMessage message;
                        while ((message = RadioIpcProtocol.ReadMessage(reader)) != null)
                        {
                            if (message.Type == RadioIpcMessage.TypeRadioState)
                            {
                                var next = new RadioState(message.Com1Frequency, message.Com2Frequency, message.ModeCEnabled ?? false, DateTimeOffset.Now);
                                lock (_gate) { _current = next; }

                                if (!_loggedFirstState)
                                {
                                    _loggedFirstState = true;
                                    Log($"First radio state received: Com1={next.Com1Frequency}, Com2={next.Com2Frequency}, ModeC={next.ModeCEnabled}");
                                }

                                Changed?.Invoke(this, EventArgs.Empty);
                            }
                        }

                        Log("Handoff.RadioHost closed the pipe.");
                    }
                }
                catch (Exception ex)
                {
                    Log("Handoff.RadioHost connection error: " + ex.Message);
                }
                finally
                {
                    lock (_gate) { _writer = null; }
                }

                Thread.Sleep(ReconnectDelay);
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
