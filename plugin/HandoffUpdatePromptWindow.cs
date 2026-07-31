using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace Handoff.Plugin
{
    /// <summary>
    /// What PluginUpdateModel needs to ask before installing an update -- split out so tests can
    /// fake it instead of touching WinForms, same reasoning as IHandoffPairingDisplay/
    /// HandoffPairingWindow.
    /// </summary>
    public interface IHandoffUpdatePromptDisplay
    {
        bool AskToInstall(Version version);
    }

    /// <summary>
    /// A branded Yes/No confirmation (logo + "Handoff" wordmark header, same
    /// HandoffBrandedFormChrome as HandoffPairingWindow) before the plugin silently installs a
    /// downloaded update (issue #34). Deliberately local to the vPilot PC rather than
    /// round-tripped through the Android app: the update check now runs at plugin startup
    /// (HandoffPlugin.Initialize), which can be well before the tablet is connected/paired for
    /// the session, so a prompt that only the PC can answer is the one guaranteed to actually
    /// reach the pilot at that point -- they're sitting at this PC setting up the sim anyway.
    ///
    /// Marshals onto vPilot's own UI thread via the SynchronizationContext captured at
    /// HandoffPlugin.Initialize time, same as HandoffPairingWindow -- but blocks
    /// (SynchronizationContext.Send, not Post) since PluginUpdateModel needs the answer before
    /// deciding whether to launch the installer. Safe to block: this always runs on
    /// PluginUpdateModel's own background thread (see HandoffPlugin's
    /// "PluginUpdateModel.Startup" thread), never vPilot's own. Form.ShowDialog() itself pumps a
    /// nested modal message loop once Send has marshaled onto the UI thread, so this blocks that
    /// thread exactly as long as the pilot takes to answer, same as any normal modal dialog.
    /// </summary>
    public sealed class HandoffUpdatePromptWindow : IHandoffUpdatePromptDisplay
    {
        private readonly SynchronizationContext _uiContext;

        public HandoffUpdatePromptWindow(SynchronizationContext uiContext)
        {
            _uiContext = uiContext ?? throw new ArgumentNullException(nameof(uiContext));
        }

        public bool AskToInstall(Version version)
        {
            var accepted = false;
            _uiContext.Send(_ =>
            {
                using (var form = BuildForm(version))
                {
                    accepted = form.ShowDialog() == DialogResult.Yes;
                }
            }, null);
            return accepted;
        }

        private static Form BuildForm(Version version)
        {
            var logo = HandoffBrandedFormChrome.LoadLogo();

            const int formWidth = 460;
            const int headerHeight = 80;
            const int buttonPanelHeight = 60;

            var messageLabel = new Label
            {
                Text = $"A new Handoff plugin version is available: {version}.\n\n" +
                    "Install it now? If vPilot is still connected, the installer will wait for it to close first.",
                AutoSize = false,
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleCenter,
                Font = new Font("Segoe UI", 11f),
                Padding = new Padding(24, 0, 24, 0)
            };

            var installButton = new Button
            {
                Text = "Install Now",
                DialogResult = DialogResult.Yes,
                Width = 150,
                Height = 34,
                Location = new Point(formWidth / 2 - 160, 13)
            };
            var notNowButton = new Button
            {
                Text = "Not Now",
                DialogResult = DialogResult.No,
                Width = 150,
                Height = 34,
                Location = new Point(formWidth / 2 + 10, 13)
            };
            var buttonPanel = new Panel { Dock = DockStyle.Bottom, Height = buttonPanelHeight };
            buttonPanel.Controls.Add(installButton);
            buttonPanel.Controls.Add(notNowButton);

            var form = new Form
            {
                Text = "Handoff Update Available",
                Width = formWidth,
                Height = 300,
                StartPosition = FormStartPosition.CenterScreen,
                TopMost = true,
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false,
                MinimizeBox = false,
                AcceptButton = installButton,
                CancelButton = notNowButton
            };
            form.Controls.Add(messageLabel);
            form.Controls.Add(buttonPanel);
            form.Controls.Add(HandoffBrandedFormChrome.BuildHeader(logo, formWidth, headerHeight));

            return form;
        }
    }
}
