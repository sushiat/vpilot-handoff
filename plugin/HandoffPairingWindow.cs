using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace Handoff.Plugin
{
    /// <summary>
    /// What HandoffPairingSession needs from a pairing-code display -- split out from
    /// HandoffPairingWindow so tests can fake it instead of touching WinForms (Form.Show() isn't
    /// something worth exercising from xUnit).
    /// </summary>
    public interface IHandoffPairingDisplay
    {
        void ShowCode(string code, DateTime expiresAtUtc);
        void CloseWindow();
    }

    /// <summary>
    /// Small on-screen window showing the current device-pairing code (issue #15). There's no
    /// existing vPilot UI surface to piggyback on, and hiding this behind /dbgwin (the plugin's
    /// existing debug-output window) would be unreasonable to expect a normal pilot to ever find
    /// -- so this is a genuine, if minimal, foreground window of its own.
    ///
    /// All Form operations are marshaled onto vPilot's own UI thread via the SynchronizationContext
    /// captured at HandoffPlugin.Initialize time (that thread already has a WinForms message loop
    /// running -- vPilot's own window -- so this attaches to it rather than starting a new one).
    /// Necessary because ShowCode/CloseWindow get called from HandoffWebSocketServer's Fleck
    /// callbacks, which run on Fleck's own socket threads, not vPilot's UI thread.
    /// </summary>
    public sealed class HandoffPairingWindow : IHandoffPairingDisplay
    {
        private readonly SynchronizationContext _uiContext;
        private Form _form;
        private Label _codeLabel;
        private Label _expiryLabel;
        private System.Windows.Forms.Timer _countdownTimer;
        private DateTime _expiresAtUtc;

        public HandoffPairingWindow(SynchronizationContext uiContext)
        {
            _uiContext = uiContext ?? throw new ArgumentNullException(nameof(uiContext));
        }

        /// <summary>Shows (creating if needed) the pairing window with the given code, bringing
        /// it to the front -- called both for a brand new code and to refresh an already-visible
        /// one after regeneration. Starts (or restarts) a live "expires in..." countdown against
        /// <paramref name="expiresAtUtc"/>.</summary>
        public void ShowCode(string code, DateTime expiresAtUtc)
        {
            _uiContext.Post(_ =>
            {
                if (_form == null || _form.IsDisposed)
                {
                    _form = BuildForm();
                }
                _codeLabel.Text = FormatForDisplay(code);
                _expiresAtUtc = expiresAtUtc;
                UpdateExpiryLabel();
                if (!_form.Visible) _form.Show();
                _form.Activate();
            }, null);
        }

        /// <summary>Hides and disposes the window -- called once pairing succeeds, or a code
        /// gets invalidated (expiry or too many wrong guesses).</summary>
        public void CloseWindow()
        {
            _uiContext.Post(_ =>
            {
                _countdownTimer?.Stop();
                _countdownTimer?.Dispose();
                _countdownTimer = null;
                if (_form != null && !_form.IsDisposed)
                {
                    _form.Close();
                    _form.Dispose();
                }
                _form = null;
                _codeLabel = null;
                _expiryLabel = null;
            }, null);
        }

        private Form BuildForm()
        {
            var logo = HandoffBrandedFormChrome.LoadLogo();

            _codeLabel = new Label
            {
                AutoSize = false,
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleCenter,
                // At least 30pt per the pilot's ask -- this needs to be readable at a glance from
                // across a cockpit setup, not squinted at. Bumped past the header's 28pt title
                // once that got added -- the code is the one thing on this window that actually
                // matters, so it should read as the dominant element, not tie with the logo/title.
                Font = new Font("Consolas", 56f, FontStyle.Bold)
            };

            _expiryLabel = new Label
            {
                AutoSize = false,
                Dock = DockStyle.Bottom,
                Height = 26,
                TextAlign = ContentAlignment.MiddleCenter,
                ForeColor = Color.DimGray,
                Font = new Font("Segoe UI", 9f)
            };

            var instructions = new Label
            {
                Text = "Enter this code in the Handoff app on your tablet to pair it with this PC.",
                AutoSize = false,
                Dock = DockStyle.Bottom,
                Height = 50,
                TextAlign = ContentAlignment.MiddleCenter,
                Font = new Font("Segoe UI", 10f)
            };

            const int formWidth = 460;
            const int headerHeight = 80;

            var form = new Form
            {
                Text = "Handoff Pairing Code",
                Width = formWidth,
                Height = 350,
                StartPosition = FormStartPosition.CenterScreen,
                TopMost = true,
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false,
                MinimizeBox = true
            };
            // Bottom-docked controls stack in reverse-add order -- instructions added first so it
            // sits above the expiry line, which then ends up as the very bottom row.
            form.Controls.Add(_codeLabel);
            form.Controls.Add(instructions);
            form.Controls.Add(_expiryLabel);
            form.Controls.Add(HandoffBrandedFormChrome.BuildHeader(logo, formWidth, headerHeight));

            _countdownTimer = new System.Windows.Forms.Timer { Interval = 1000 };
            _countdownTimer.Tick += (s, e) => UpdateExpiryLabel();
            _countdownTimer.Start();

            return form;
        }

        private void UpdateExpiryLabel()
        {
            if (_expiryLabel == null) return;
            var remaining = _expiresAtUtc - DateTime.UtcNow;
            _expiryLabel.Text = remaining > TimeSpan.Zero
                ? $"Expires in {(int)remaining.TotalMinutes}:{remaining.Seconds:D2}"
                : "Expired";
        }

        // "123 456" reads easier at a glance than a flat 6-digit run.
        private static string FormatForDisplay(string code) =>
            code.Length == 6 ? code.Substring(0, 3) + " " + code.Substring(3) : code;
    }
}
