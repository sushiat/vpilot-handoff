using System.IO;
using Newtonsoft.Json;

namespace Handoff.Plugin
{
    /// <summary>
    /// Newline-delimited JSON over two one-directional named pipes -- shared by both the
    /// plugin (client) and Handoff.RadioHost (server) so the wire format can't drift between
    /// the two ends.
    ///
    /// Two pipes, not one duplex pipe: confirmed by direct local reproduction that .NET
    /// Framework's synchronous (non-overlapped) named pipe I/O does not reliably support a
    /// blocking read on one thread running concurrently with a write from a different thread
    /// on the same duplex PipeStream -- the write hangs indefinitely with no exception. That
    /// is exactly the shape of this protocol (one thread blocked reading commands, a separate
    /// thread writing state whenever SimConnect data arrives), so state and commands each get
    /// their own pipe, each with exactly one reader and one writer, never both on the same
    /// stream from different threads.
    /// </summary>
    public static class RadioIpcProtocol
    {
        public const string StatePipeName = "HandoffRadioHostState";
        public const string CommandPipeName = "HandoffRadioHostCommand";

        public static void WriteMessage(StreamWriter writer, RadioIpcMessage message)
        {
            writer.WriteLine(JsonConvert.SerializeObject(message));
            writer.Flush();
        }

        /// <summary>Returns null when the pipe has been closed by the other end.</summary>
        public static RadioIpcMessage ReadMessage(StreamReader reader)
        {
            var line = reader.ReadLine();
            return line == null ? null : JsonConvert.DeserializeObject<RadioIpcMessage>(line);
        }
    }
}
