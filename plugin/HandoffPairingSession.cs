using System;
using System.Security.Cryptography;

namespace Handoff.Plugin
{
    /// <summary>
    /// Owns the currently-active device-pairing code (issue #15) -- generation, display via
    /// HandoffPairingWindow, expiry, and single-use consumption. One pending code at a time is
    /// enough: pairing is an inherently one-at-a-time-per-PC action (the pilot is physically at
    /// this machine reading the code off its screen), so simultaneous unauthenticated connection
    /// attempts just share whatever code is already showing rather than each getting their own.
    /// </summary>
    public sealed class HandoffPairingSession
    {
        private static readonly TimeSpan CodeValidity = TimeSpan.FromMinutes(3);
        // Not a real security boundary on its own (a determined attacker could just open a new
        // socket to get a fresh code+attempt budget), but cheaply stops a single connection from
        // grinding through guesses against one code for its whole validity window.
        private const int MaxAttemptsPerCode = 10;

        private readonly IHandoffPairingDisplay _window;
        private readonly Action<string> _logDebug;
        private readonly object _gate = new object();
        private string _code;
        private DateTime _expiresAtUtc;
        private int _failedAttempts;

        public HandoffPairingSession(IHandoffPairingDisplay window, Action<string> logDebug = null)
        {
            _window = window ?? throw new ArgumentNullException(nameof(window));
            _logDebug = logDebug;
        }

        /// <summary>Returns the currently active code, generating a fresh one first if none is
        /// active or the previous one expired/was exhausted. Always (re-)shows the window, even
        /// when reusing an already-active code -- otherwise a pilot manually closing the window
        /// (its own "X", Alt+F4, whatever) desyncs the display from this session's internal
        /// state: the code would stay valid for matching purposes but become invisible, since
        /// nothing would ever re-trigger HandoffPairingWindow.ShowCode for it again. Fixed after
        /// exactly that happening during testing -- ShowCode itself is idempotent (recreates the
        /// form only if it's actually gone), so this is cheap to call unconditionally.</summary>
        public string EnsureActiveCode()
        {
            lock (_gate)
            {
                if (_code == null || DateTime.UtcNow >= _expiresAtUtc)
                {
                    _code = GenerateCode();
                    _expiresAtUtc = DateTime.UtcNow.Add(CodeValidity);
                    _failedAttempts = 0;
                    Log("Displaying new pairing code, valid for " + CodeValidity.TotalMinutes + " minutes");
                }
                _window.ShowCode(_code);
                return _code;
            }
        }

        /// <summary>True if `code` matches the currently active, unexpired code. A match is
        /// single-use -- it immediately invalidates the code and hides the window, so a second
        /// device can't reuse the same code without the pilot re-triggering pairing.</summary>
        public bool TryConsumeCode(string code)
        {
            lock (_gate)
            {
                if (_code == null || DateTime.UtcNow >= _expiresAtUtc) return false;

                if (_code == code)
                {
                    _code = null;
                    _failedAttempts = 0;
                    _window.CloseWindow();
                    return true;
                }

                _failedAttempts++;
                if (_failedAttempts >= MaxAttemptsPerCode)
                {
                    Log("Too many failed pairing attempts -- invalidating this code");
                    _code = null;
                    _failedAttempts = 0;
                    _window.CloseWindow();
                }
                return false;
            }
        }

        private static string GenerateCode()
        {
            // 6-digit numeric -- easy to read off a label at a glance and type on a soft
            // keyboard; the code is single-use and only valid for a few minutes, so it doesn't
            // need alphanumeric-grade entropy.
            var bytes = new byte[4];
            using (var rng = new RNGCryptoServiceProvider()) rng.GetBytes(bytes);
            var value = BitConverter.ToUInt32(bytes, 0) % 1_000_000;
            return value.ToString("D6");
        }

        private void Log(string message)
        {
            var line = "HandoffPairingSession: " + message;
            System.Diagnostics.Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
