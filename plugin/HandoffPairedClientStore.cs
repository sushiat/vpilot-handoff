using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using Newtonsoft.Json;

namespace Handoff.Plugin
{
    /// <summary>
    /// Persists paired-client bearer tokens (issue #15's device-authorization layer, on top of
    /// the TLS certificate's own silent TOFU pinning). A list, not a single value -- there was
    /// never an actual one-device constraint anywhere else in this codebase
    /// (HandoffWebSocketServer already tracks a List of simultaneous sockets), so multiple
    /// devices (a tablet and a backup phone, say) can stay paired to one plugin at once.
    ///
    /// Only each token's SHA-256 hash is persisted, never the plaintext -- the plugin only ever
    /// needs to check "does this presented token match one we've issued," never to recall a
    /// token's plaintext later.
    /// </summary>
    public sealed class HandoffPairedClientStore
    {
        private static readonly string Default_configPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "paired-clients.json");

        private readonly object _gate = new object();
        private readonly Action<string> _logDebug;
        private readonly string _configPath;
        private List<PairedClient> _clients;

        /// <param name="configPath">Overridable only for tests, same reasoning as
        /// FlightPlanModel's configPath.</param>
        public HandoffPairedClientStore(Action<string> logDebug = null, string configPath = null)
        {
            _logDebug = logDebug;
            _configPath = configPath ?? Default_configPath;
            _clients = Load();
        }

        public bool IsTokenValid(string token)
        {
            if (string.IsNullOrEmpty(token)) return false;
            var hash = HashToken(token);
            lock (_gate) { return _clients.Any(c => c.TokenHash == hash); }
        }

        /// <summary>Generates and persists a new paired-client entry, returning the plaintext
        /// token to send to the client -- this is the only time the plaintext exists anywhere
        /// but the client itself.</summary>
        public string IssueToken()
        {
            var tokenBytes = new byte[32];
            using (var rng = new RNGCryptoServiceProvider()) rng.GetBytes(tokenBytes);
            var token = Convert.ToBase64String(tokenBytes);

            lock (_gate)
            {
                _clients.Add(new PairedClient { TokenHash = HashToken(token), PairedAtUtc = DateTime.UtcNow });
                Save(_clients);
            }
            return token;
        }

        private static string HashToken(string token)
        {
            using (var sha256 = SHA256.Create())
            {
                return Convert.ToBase64String(sha256.ComputeHash(Encoding.UTF8.GetBytes(token)));
            }
        }

        private List<PairedClient> Load()
        {
            try
            {
                if (!File.Exists(_configPath)) return new List<PairedClient>();

                var json = File.ReadAllText(_configPath);
                return JsonConvert.DeserializeObject<List<PairedClient>>(json) ?? new List<PairedClient>();
            }
            catch (Exception ex)
            {
                Log("Failed to load paired clients: " + ex.Message);
                return new List<PairedClient>();
            }
        }

        private void Save(List<PairedClient> clients)
        {
            try
            {
                var directory = Path.GetDirectoryName(_configPath);
                if (directory != null) Directory.CreateDirectory(directory);
                File.WriteAllText(_configPath, JsonConvert.SerializeObject(clients));
            }
            catch (Exception ex)
            {
                Log("Failed to persist paired clients: " + ex.Message);
            }
        }

        private void Log(string message)
        {
            var line = "HandoffPairedClientStore: " + message;
            System.Diagnostics.Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }

        private sealed class PairedClient
        {
            public string TokenHash { get; set; }
            public DateTime PairedAtUtc { get; set; }
        }
    }
}
