using System;
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

        private static void Main()
        {
            Logger.Log("Handoff.RadioHost starting, listening on pipes " + RadioIpcProtocol.StatePipeName + " / " + RadioIpcProtocol.CommandPipeName);
            var radio = new RadioSimConnectClient(OnRadioStateChanged);

            new Thread(() => RunCommandServer(radio)) { Name = "Program.RunCommandServer", IsBackground = true }.Start();
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

        private static void RunCommandServer(RadioSimConnectClient radio)
        {
            while (true)
            {
                using (var pipeServer = new NamedPipeServerStream(RadioIpcProtocol.CommandPipeName, PipeDirection.In))
                {
                    pipeServer.WaitForConnection();
                    Logger.Log("Plugin connected to command pipe.");

                    var reader = new StreamReader(pipeServer);
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
                        Logger.Log("Command pipe error: " + ex.Message);
                    }

                    Logger.Log("Plugin disconnected from command pipe.");
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
                        ModeCEnabled = state.ModeCEnabled
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
    }
}
