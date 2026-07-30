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
        void ShowCode(string code);
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

        public HandoffPairingWindow(SynchronizationContext uiContext)
        {
            _uiContext = uiContext ?? throw new ArgumentNullException(nameof(uiContext));
        }

        /// <summary>Shows (creating if needed) the pairing window with the given code, bringing
        /// it to the front -- called both for a brand new code and to refresh an already-visible
        /// one after regeneration.</summary>
        public void ShowCode(string code)
        {
            _uiContext.Post(_ =>
            {
                if (_form == null || _form.IsDisposed)
                {
                    _form = BuildForm();
                }
                _codeLabel.Text = FormatForDisplay(code);
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
                if (_form != null && !_form.IsDisposed)
                {
                    _form.Close();
                    _form.Dispose();
                }
                _form = null;
                _codeLabel = null;
            }, null);
        }

        private Form BuildForm()
        {
            _codeLabel = new Label
            {
                AutoSize = false,
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleCenter,
                // At least 30pt per the pilot's ask -- this needs to be readable at a glance from
                // across a cockpit setup, not squinted at.
                Font = new Font("Consolas", 40f, FontStyle.Bold)
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

            var form = new Form
            {
                Text = "Handoff Pairing Code",
                Width = 460,
                Height = 260,
                StartPosition = FormStartPosition.CenterScreen,
                TopMost = true,
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false,
                MinimizeBox = true
            };
            form.Controls.Add(_codeLabel);
            form.Controls.Add(instructions);
            return form;
        }

        // "123 456" reads easier at a glance than a flat 6-digit run.
        private static string FormatForDisplay(string code) =>
            code.Length == 6 ? code.Substring(0, 3) + " " + code.Substring(3) : code;
    }
}
