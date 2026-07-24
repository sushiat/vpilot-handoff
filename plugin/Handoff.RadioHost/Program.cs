using System.IO;
using System.IO.Pipes;
using Handoff.Plugin;

namespace Handoff.RadioHost
{
    /// <summary>
    /// Standalone x64 process bridging SimConnect (COM1/COM2 frequency, Mode C transponder)
    /// to the vPilot plugin over a local named pipe. Split out from the plugin itself because
    /// CTrue.FsConnect's native simconnect.dll is x64-only, while vPilot's own process is x86
    /// -- see plugin/README.md.
    ///
    /// Accepts one plugin connection at a time, on the well-known pipe
    /// RadioIpcProtocol.PipeName. Runs forever, independent of whether a client is currently
    /// connected -- the SimConnect polling loop (RadioSimConnectClient) keeps running so
    /// fresh state is ready immediately whenever the plugin (re)connects.
    /// </summary>
    internal static class Program
    {
        private static readonly object WriterGate = new object();
        private static StreamWriter _currentWriter;

        private static void Main()
        {
            Logger.Log("Handoff.RadioHost starting, listening on pipe " + RadioIpcProtocol.PipeName);
            var radio = new RadioSimConnectClient(OnRadioStateChanged);

            while (true)
            {
                using (var pipeServer = new NamedPipeServerStream(RadioIpcProtocol.PipeName, PipeDirection.InOut))
                {
                    pipeServer.WaitForConnection();
                    Logger.Log("Plugin connected.");
                    HandleConnection(pipeServer, radio);
                }
            }
        }

        private static void HandleConnection(NamedPipeServerStream pipeServer, RadioSimConnectClient radio)
        {
            var writer = new StreamWriter(pipeServer) { AutoFlush = true };
            var reader = new StreamReader(pipeServer);

            lock (WriterGate) { _currentWriter = writer; }

            try
            {
                RadioIpcMessage message;
                while ((message = RadioIpcProtocol.ReadMessage(reader)) != null)
                {
                    switch (message.Type)
                    {
                        case RadioIpcMessage.TypeSetCom1Frequency:
                            if (message.Megahertz.HasValue) radio.SetCom1Frequency(message.Megahertz.Value);
                            break;
                        case RadioIpcMessage.TypeSetCom2Frequency:
                            if (message.Megahertz.HasValue) radio.SetCom2Frequency(message.Megahertz.Value);
                            break;
                    }
                }
            }
            catch (IOException ex)
            {
                // Plugin (or vPilot itself) disconnected -- expected, go back to waiting.
                Logger.Log("Plugin disconnected: " + ex.Message);
            }
            finally
            {
                lock (WriterGate)
                {
                    if (_currentWriter == writer) _currentWriter = null;
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
                    RadioIpcProtocol.WriteMessage(_currentWriter, new RadioIpcMessage
                    {
                        Type = RadioIpcMessage.TypeRadioState,
                        Com1Frequency = state.Com1Frequency,
                        Com2Frequency = state.Com2Frequency,
                        ModeCEnabled = state.ModeCEnabled
                    });
                }
                catch (IOException ex)
                {
                    Logger.Log("Failed writing to pipe client: " + ex.Message);
                    _currentWriter = null;
                }
            }
        }
    }
}
