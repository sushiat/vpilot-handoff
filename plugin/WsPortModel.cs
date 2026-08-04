using System;
using System.Diagnostics;
using System.IO;
using Newtonsoft.Json;

namespace Handoff.Plugin
{
    /// <summary>
    /// The pilot-configurable override for HandoffWebSocketServer's TCP listen port (issue #98),
    /// persisted plugin-side. Same shape as UpdateIntervalModel: one persisted value + a
    /// payload-free Changed event. Unlike UpdateIntervalModel, there's no wire command to set
    /// this remotely -- it's only ever changed locally, from HandoffPortConflictWindow's "Save &amp;
    /// Restart Listening" button, in response to a bind failure on the default port.
    /// </summary>
    public sealed class WsPortModel
    {
        public const int DefaultPort = 48765;
        private const int MinPort = 1024;
        private const int MaxPort = 65535;

        private static readonly string Default_configPath = PathJoin.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "wsPort.json");

        private readonly object _gate = new object();
        private readonly Action<string> _logDebug;
        private readonly string _configPath;
        private int _port = DefaultPort;

        public event EventHandler Changed;

        /// <summary>Loads the persisted port override from disk, defaulting to DefaultPort.</summary>
        /// <param name="configPath">Overridable only for tests, same reasoning as
        /// UpdateIntervalModel's configPath.</param>
        public WsPortModel(Action<string> logDebug = null, string configPath = null)
        {
            _logDebug = logDebug;
            _configPath = configPath ?? Default_configPath;
            Load();
        }

        public int CurrentPort
        {
            get { lock (_gate) { return _port; } }
        }

        /// <summary>Persists the given port (a full overwrite of whatever was persisted before)
        /// and raises Changed. Out-of-range values are ignored (logged, left untouched) rather
        /// than persisted -- a bad NumericUpDown value should never end up unbindable on the next
        /// launch. No-ops (no save, no event) if the port is unchanged.</summary>
        public void SetPort(int port)
        {
            if (port < MinPort || port > MaxPort)
            {
                Log("Ignoring out-of-range port: " + port);
                return;
            }

            lock (_gate)
            {
                if (_port == port) return;
                _port = port;
            }
            Save(port);
            Log("WebSocket port set to " + port + ".");
            Changed?.Invoke(this, EventArgs.Empty);
        }

        private void Load()
        {
            try
            {
                if (!File.Exists(_configPath)) return;

                var json = File.ReadAllText(_configPath);
                var config = JsonConvert.DeserializeObject<PersistedSettings>(json);
                if (config == null) return;

                if (config.Port >= MinPort && config.Port <= MaxPort)
                {
                    lock (_gate) { _port = config.Port; }
                }
                else
                {
                    Log("Persisted WebSocket port out of range (" + config.Port + "), keeping default.");
                }
            }
            catch (Exception ex)
            {
                Log("Failed to load persisted WebSocket port: " + ex.Message);
            }
        }

        private void Save(int port)
        {
            try
            {
                var directory = Path.GetDirectoryName(_configPath);
                if (directory != null) Directory.CreateDirectory(directory);

                var json = JsonConvert.SerializeObject(new PersistedSettings { Port = port });
                File.WriteAllText(_configPath, json);
            }
            catch (Exception ex)
            {
                Log("Failed to persist WebSocket port: " + ex.Message);
            }
        }

        private void Log(string message)
        {
            var line = "WsPortModel: " + message;
            Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }

        private sealed class PersistedSettings
        {
            public int Port { get; set; }
        }
    }
}
