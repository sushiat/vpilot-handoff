using System;
using System.Diagnostics;
using System.IO;
using Handoff.Plugin;

namespace Handoff.RadioHost
{
    /// <summary>
    /// Handoff.RadioHost runs as its own process with no console (CreateNoWindow) and no
    /// vPilot debug window to post to -- Debug.WriteLine alone is invisible without attaching
    /// a debugger. This writes a plain timestamped log file instead, so behavior (in
    /// particular, whether the SimConnect connection itself succeeds) can be checked without
    /// one.
    /// </summary>
    internal static class Logger
    {
        private static readonly string LogPath = PathJoin.Combine(Path.GetTempPath(), "Handoff.RadioHost.log");
        private static readonly object Gate = new object();

        public static void Log(string message)
        {
            var line = $"[{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff}] {message}";
            Debug.WriteLine(line);

            lock (Gate)
            {
                try
                {
                    File.AppendAllText(LogPath, line + Environment.NewLine);
                }
                catch
                {
                    // Best effort -- a logging failure shouldn't take down the SimConnect bridge.
                }
            }
        }
    }
}
