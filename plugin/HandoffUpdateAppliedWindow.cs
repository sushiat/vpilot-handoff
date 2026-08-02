using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace Handoff.Plugin
{
    /// <summary>
    /// What PluginUpdateModel needs to show once an update has actually been applied -- split out
    /// so tests can fake it instead of touching WinForms, same reasoning as
    /// IHandoffUpdatePromptDisplay/IHandoffPairingDisplay.
    /// </summary>
    public interface IHandoffUpdateAppliedDisplay
    {
        void ShowUpdated(string version);
    }

    /// <summary>
    /// A branded "Handoff updated to {version}" confirmation shown once on the first plugin load
    /// after an auto-update (issue #85). Before this, PluginUpdateModel.CheckMarker reported the
    /// applied update only to the Android app (OperationProgressModel) and vPilot's /dbgwin window
    /// (PostDebugMessage) -- neither of which a pilot is looking at during a normal startup, so the
    /// update landed with no visible confirmation anywhere in vPilot itself.
    ///
    /// Same branded chrome as HandoffPairingWindow/HandoffUpdatePromptWindow (logo + "Handoff"
    /// wordmark header via HandoffBrandedFormChrome), and like HandoffPairingWindow it's *modeless*
    /// -- Show() via SynchronizationContext.Post, deliberately NOT the update prompt's blocking
    /// ShowDialog()/Send. This matters because CheckMarker runs inline on vPilot's own
    /// Initialize-calling thread (see HandoffPlugin), so a blocking modal here would stall vPilot's
    /// startup; a fire-and-forget notice needs no answer, so it just posts the window and returns.
    /// </summary>
    public sealed class HandoffUpdateAppliedWindow : IHandoffUpdateAppliedDisplay
    {
        private readonly SynchronizationContext _uiContext;

        public HandoffUpdateAppliedWindow(SynchronizationContext uiContext)
        {
            _uiContext = uiContext ?? throw new ArgumentNullException(nameof(uiContext));
        }

        public void ShowUpdated(string version)
        {
            _uiContext.Post(_ =>
            {
                // Modeless: Show() (not ShowDialog()) so this never blocks the posting thread.
                // The form disposes itself on close (see BuildForm's FormClosed handler) -- nothing
                // holds a reference to it, unlike the pairing window which is reshown/refreshed.
                BuildForm(version).Show();
            }, null);
        }

        private static Form BuildForm(string version)
        {
            var logo = HandoffBrandedFormChrome.LoadLogo();

            const int formWidth = 460;
            const int headerHeight = 80;
            const int buttonPanelHeight = 60;

            var messageLabel = new Label
            {
                Text = $"Handoff has been updated to version {version}.",
                AutoSize = false,
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleCenter,
                Font = new Font("Segoe UI", 11f),
                Padding = new Padding(24, 0, 24, 0)
            };

            var okButton = new Button
            {
                Text = "OK",
                DialogResult = DialogResult.OK,
                Width = 150,
                Height = 34,
                Location = new Point(formWidth / 2 - 75, 13)
            };
            var buttonPanel = new Panel { Dock = DockStyle.Bottom, Height = buttonPanelHeight };
            buttonPanel.Controls.Add(okButton);

            var form = new Form
            {
                Text = "Handoff Updated",
                Width = formWidth,
                Height = 280,
                StartPosition = FormStartPosition.CenterScreen,
                TopMost = true,
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false,
                MinimizeBox = false,
                AcceptButton = okButton,
                CancelButton = okButton
            };
            // Modeless, so OK's DialogResult won't auto-close it (that's a ShowDialog behaviour) --
            // close it explicitly, and dispose once it's gone since nothing else owns it.
            okButton.Click += (s, e) => form.Close();
            form.FormClosed += (s, e) => form.Dispose();

            form.Controls.Add(messageLabel);
            form.Controls.Add(buttonPanel);
            form.Controls.Add(HandoffBrandedFormChrome.BuildHeader(logo, formWidth, headerHeight));

            return form;
        }
    }
}
