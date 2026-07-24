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
        private RadioState _current = new RadioState(null, null, false, DateTimeOffset.Now);
        private StreamWriter _writer;

        public event EventHandler Changed;

        public RadioStateModel()
        {
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
                if (_writer == null) return; // Not connected -- drop silently, matches read-side "best effort" behavior.
                try
                {
                    RadioIpcProtocol.WriteMessage(_writer, message);
                }
                catch (IOException ex)
                {
                    Debug.WriteLine("RadioStateModel: failed sending command to RadioHost: " + ex.Message);
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
                    return; // Already running (a prior vPilot session's helper, or otherwise) -- reuse it.
                }
            }
            catch (Exception)
            {
                // Nothing listening -- expected on first launch. Fall through and spawn it.
            }

            try
            {
                var pluginDirectory = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
                var radioHostPath = Path.Combine(pluginDirectory ?? ".", "RadioHost", "Handoff.RadioHost.exe");

                Process.Start(new ProcessStartInfo
                {
                    FileName = radioHostPath,
                    UseShellExecute = false,
                    CreateNoWindow = true
                });
            }
            catch (Exception ex)
            {
                Debug.WriteLine("RadioStateModel: failed to start Handoff.RadioHost: " + ex);
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
                                Changed?.Invoke(this, EventArgs.Empty);
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    Debug.WriteLine("RadioStateModel: RadioHost connection error: " + ex.Message);
                }
                finally
                {
                    lock (_gate) { _writer = null; }
                }

                Thread.Sleep(ReconnectDelay);
            }
        }
    }
}
