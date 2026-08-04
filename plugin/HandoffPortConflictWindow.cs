using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace Handoff.Plugin
{
    /// <summary>What HandoffPlugin.Initialize needs to report a listener bind result for --
    /// carries whether that listener actually conflicted, its current port, and (TCP only) a
    /// retry callback bound to HandoffWebSocketServer.RetryBindWithPort. The UDP side never gets
    /// a callback -- issue #98's discovery rendezvous port is deliberately not configurable.</summary>
    public sealed class PortConflictInfo
    {
        public int Port { get; }
        public bool IsConflicted { get; }
        public Func<int, bool> Retry { get; }

        public PortConflictInfo(int port, bool isConflicted, Func<int, bool> retry = null)
        {
            Port = port;
            IsConflicted = isConflicted;
            Retry = retry;
        }
    }

    /// <summary>
    /// What HandoffPlugin needs to report a port-bind outcome to the pilot -- split out from
    /// HandoffPortConflictWindow so tests can fake it instead of touching WinForms, same
    /// reasoning as IHandoffPairingDisplay/IHandoffUpdatePromptDisplay.
    /// </summary>
    public interface IHandoffPortConflictDisplay
    {
        void ShowConflict(PortConflictInfo tcp, PortConflictInfo udp);
    }

    /// <summary>
    /// Themed WinForms dialog shown when either listener fails to bind its port at startup
    /// (issue #98) -- reuses HandoffBrandedFormChrome, same as HandoffPairingWindow/
    /// HandoffUpdatePromptWindow. Always shows both the TCP and UDP sections, one per listener:
    /// whichever one actually conflicted gets the full explanation (and, for TCP only, an
    /// editable port + retry button); the other gets a compact "port NNNN -- OK" status line, so
    /// the pilot isn't left wondering whether the other listener is fine too.
    ///
    /// Marshaled onto vPilot's own UI thread via the SynchronizationContext captured at
    /// HandoffPlugin.Initialize time, same as HandoffPairingWindow -- and non-blocking (Post, not
    /// Send) since a bind failure at startup shouldn't stall Initialize waiting for the pilot to
    /// dismiss a dialog.
    /// </summary>
    public sealed class HandoffPortConflictWindow : IHandoffPortConflictDisplay
    {
        private readonly SynchronizationContext _uiContext;

        public HandoffPortConflictWindow(SynchronizationContext uiContext)
        {
            _uiContext = uiContext ?? throw new ArgumentNullException(nameof(uiContext));
        }

        public void ShowConflict(PortConflictInfo tcp, PortConflictInfo udp)
        {
            _uiContext.Post(_ =>
            {
                var form = BuildForm(tcp, udp);
                form.Show();
                form.Activate();
            }, null);
        }

        private static Form BuildForm(PortConflictInfo tcp, PortConflictInfo udp)
        {
            const int formWidth = 480;
            const int headerHeight = 80;

            var logo = HandoffBrandedFormChrome.LoadLogo();

            var body = new FlowLayoutPanel
            {
                Dock = DockStyle.Fill,
                FlowDirection = FlowDirection.TopDown,
                WrapContents = false,
                AutoScroll = true,
                Padding = new Padding(20, 10, 20, 10)
            };

            body.Controls.Add(BuildTcpSection(tcp, formWidth));
            body.Controls.Add(BuildUdpSection(udp, formWidth));

            var closeButton = new Button
            {
                Text = "Close",
                Width = 100,
                Height = 30,
                DialogResult = DialogResult.Cancel
            };
            var buttonPanel = new Panel { Dock = DockStyle.Bottom, Height = 50 };
            closeButton.Location = new Point((formWidth - closeButton.Width) / 2, 10);
            buttonPanel.Controls.Add(closeButton);

            var form = new Form
            {
                Text = "Handoff -- Port Conflict",
                Width = formWidth,
                Height = 480,
                StartPosition = FormStartPosition.CenterScreen,
                TopMost = true,
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false,
                MinimizeBox = false,
                CancelButton = closeButton
            };
            closeButton.Click += (s, e) => form.Close();

            form.Controls.Add(body);
            form.Controls.Add(buttonPanel);
            form.Controls.Add(HandoffBrandedFormChrome.BuildHeader(logo, formWidth, headerHeight));

            return form;
        }

        private static Panel BuildOkStatus(string label, int port)
        {
            var panel = new Panel { Width = 420, Height = 34, Margin = new Padding(0, 6, 0, 6) };
            panel.Controls.Add(new Label
            {
                Text = "✓ " + label + " port " + port + " -- OK",
                AutoSize = false,
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleLeft,
                ForeColor = Color.DarkGreen,
                Font = new Font("Segoe UI", 10f, FontStyle.Bold)
            });
            return panel;
        }

        private static Panel BuildTcpSection(PortConflictInfo tcp, int formWidth)
        {
            if (!tcp.IsConflicted) return BuildOkStatus("TCP", tcp.Port);

            var panel = new Panel { Width = 420, Height = 220, Margin = new Padding(0, 6, 0, 6) };

            var message = new Label
            {
                Text = "TCP port " + tcp.Port + " is already in use, so the tablet can't connect. " +
                    "This is usually a stale vPilot.exe still running (check Task Manager) or a " +
                    "duplicate Handoff plugin install, not a stranger app -- these ports aren't " +
                    "well-known.\n\n" +
                    "Auto-discovery will keep working automatically after you change the port below " +
                    "-- but if the tablet currently has a manual IP:port entered in Settings, update " +
                    "it to match the new port too.",
                AutoSize = false,
                Location = new Point(0, 0),
                Size = new Size(420, 130),
                Font = new Font("Segoe UI", 9.5f)
            };

            var portLabel = new Label
            {
                Text = "New port:",
                AutoSize = true,
                Location = new Point(0, 138),
                Font = new Font("Segoe UI", 9.5f, FontStyle.Bold)
            };
            var portInput = new NumericUpDown
            {
                Minimum = 1024,
                Maximum = 65535,
                Value = tcp.Port,
                Width = 80,
                Location = new Point(80, 135)
            };
            var saveButton = new Button
            {
                Text = "Save && Restart Listening",
                Width = 190,
                Height = 28,
                Location = new Point(170, 133)
            };
            var resultLabel = new Label
            {
                Text = string.Empty,
                AutoSize = false,
                Location = new Point(0, 172),
                Size = new Size(420, 40),
                Font = new Font("Segoe UI", 9f, FontStyle.Italic)
            };

            saveButton.Click += (s, e) =>
            {
                var newPort = (int)portInput.Value;
                var succeeded = tcp.Retry?.Invoke(newPort) ?? false;
                resultLabel.ForeColor = succeeded ? Color.DarkGreen : Color.DarkRed;
                resultLabel.Text = succeeded
                    ? "Listening on port " + newPort + " now."
                    : "Port " + newPort + " is also in use -- try a different one.";
            };

            panel.Controls.Add(message);
            panel.Controls.Add(portLabel);
            panel.Controls.Add(portInput);
            panel.Controls.Add(saveButton);
            panel.Controls.Add(resultLabel);
            return panel;
        }

        private static Panel BuildUdpSection(PortConflictInfo udp, int formWidth)
        {
            if (!udp.IsConflicted) return BuildOkStatus("UDP discovery", udp.Port);

            var panel = new Panel { Width = 420, Height = 110, Margin = new Padding(0, 6, 0, 6) };

            var message = new Label
            {
                Text = "UDP discovery port " + udp.Port + " is already in use. Sorry, this port " +
                    "can't be changed -- auto-discovery is unavailable this session. Enter this " +
                    "PC's IP address and the TCP port shown above manually on the tablet instead.",
                AutoSize = false,
                Dock = DockStyle.Fill,
                Font = new Font("Segoe UI", 9.5f)
            };

            panel.Controls.Add(message);
            return panel;
        }
    }
}
