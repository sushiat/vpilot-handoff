using System.IO;
using Newtonsoft.Json;

namespace Handoff.Plugin
{
    /// <summary>
    /// Newline-delimited JSON over a duplex named pipe -- shared by both the plugin (client)
    /// and Handoff.RadioHost (server) so the wire format can't drift between the two ends.
    /// </summary>
    public static class RadioIpcProtocol
    {
        public const string PipeName = "HandoffRadioHost";

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
