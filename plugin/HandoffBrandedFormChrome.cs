using System.Drawing;
using System.Reflection;
using System.Windows.Forms;

namespace Handoff.Plugin
{
    /// <summary>
    /// Shared "logo + Handoff wordmark" header used by every branded WinForms window the plugin
    /// shows directly on the vPilot PC (HandoffPairingWindow, HandoffUpdatePromptWindow) -- split
    /// out so neither one duplicates the embedded-resource loading or the centered-header layout
    /// math.
    /// </summary>
    internal static class HandoffBrandedFormChrome
    {
        /// <summary>Loads the embedded logo (Assets/logo.png, see Handoff.Plugin.csproj's
        /// EmbeddedResource entry), or null if it's ever missing/corrupt -- a missing logo
        /// shouldn't take the whole window down with it, just render without one.</summary>
        public static Image LoadLogo()
        {
            try
            {
                using (var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Handoff.Plugin.Assets.logo.png"))
                {
                    return stream != null ? Image.FromStream(stream) : null;
                }
            }
            catch
            {
                return null;
            }
        }

        /// <summary>Logo + "Handoff" wordmark, side by side, centered as one group within a
        /// Dock=Top panel -- WinForms has no built-in "center this row of controls" layout short
        /// of FlowLayoutPanel (which left-aligns, not centers), so this measures both pieces and
        /// positions them by hand instead. Falls back to just the centered text if the logo
        /// failed to load (see LoadLogo).</summary>
        public static Panel BuildHeader(Image logo, int formWidth, int headerHeight)
        {
            const int logoSize = 60;
            const int gap = 14;

            var header = new Panel { Dock = DockStyle.Top, Height = headerHeight };

            var titleFont = new Font("Segoe UI", 28f, FontStyle.Bold);
            var titleLabel = new Label
            {
                Text = "Handoff",
                Font = titleFont,
                AutoSize = true,
                TextAlign = ContentAlignment.MiddleLeft
            };
            var titleSize = TextRenderer.MeasureText(titleLabel.Text, titleFont);

            var totalWidth = logo != null ? logoSize + gap + titleSize.Width : titleSize.Width;
            var startX = (formWidth - totalWidth) / 2;

            if (logo != null)
            {
                header.Controls.Add(new PictureBox
                {
                    Image = logo,
                    SizeMode = PictureBoxSizeMode.Zoom,
                    Size = new Size(logoSize, logoSize),
                    Location = new Point(startX, (headerHeight - logoSize) / 2)
                });
                startX += logoSize + gap;
            }

            titleLabel.Location = new Point(startX, (headerHeight - titleSize.Height) / 2);
            header.Controls.Add(titleLabel);

            return header;
        }
    }
}
