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
        private static readonly string Default_configPath = PathJoin.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Handoff", "paired-clients.json");

        private readonly object _gate = new object();
        private readonly Action<string> _logDebug;
        private readonly string _configPath;
        private readonly List<PairedClient> _clients;

        /// <summary>Loads the on-disk list of previously paired Android clients.</summary>
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

        /// <summary>Issue #65 -- paired-device count only, never token hashes/plaintext, for the debug snapshot file.</summary>
        public PairingDebugSnapshot BuildDebugSnapshot(bool pairingCodeCurrentlyActive)
        {
            lock (_gate) { return new PairingDebugSnapshot(_clients.Count, pairingCodeCurrentlyActive); }
        }

        /// <summary>Generates and persists a new paired-client entry, returning the plaintext
        /// token to send to the client -- this is the only time the plaintext exists anywhere
        /// but the client itself.
        ///
        /// <paramref name="deviceId"/> is the client's own stable per-install identifier (see
        /// docs/protocol.md -- Android sends Settings.Secure.ANDROID_ID), optional. When
        /// provided, any existing entries sharing the same deviceId are dropped before adding
        /// the new one -- otherwise every re-pair from the same physical device (a forced
        /// re-pair after the plugin's certificate changed, say) leaves a stale, never-cleaned-up
        /// hash behind forever. A null/blank deviceId (an older or non-Android client) just
        /// always adds a new entry, same as before this existed.</summary>
        public string IssueToken(string deviceId = null)
        {
            var tokenBytes = new byte[32];
            using (var rng = new RNGCryptoServiceProvider()) rng.GetBytes(tokenBytes);
            var token = Convert.ToBase64String(tokenBytes);

            lock (_gate)
            {
                if (!string.IsNullOrEmpty(deviceId))
                {
                    var removed = _clients.RemoveAll(c => c.DeviceId == deviceId);
                    if (removed > 0) Log("Replacing " + removed + " existing paired-client entr(y/ies) for the same device");
                }
                _clients.Add(new PairedClient { TokenHash = HashToken(token), DeviceId = deviceId, PairedAtUtc = DateTime.UtcNow });
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
            public string DeviceId { get; set; }
            public DateTime PairedAtUtc { get; set; }
        }
    }
}
