using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
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
            body.Controls.Add(BuildUdpSection(udp, tcp.Port, formWidth));

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

        private const int SectionWidth = 420;

        private static Panel BuildTcpSection(PortConflictInfo tcp, int formWidth)
        {
            if (!tcp.IsConflicted) return BuildOkStatus("TCP", tcp.Port);

            // Named, not just guessed at, whenever the IP Helper API lookup succeeds -- falls
            // back to the general (still usually correct) guess if it can't be determined.
            var owner = PortOwnerLookup.TryDescribeOwner(tcp.Port, tcp: true);
            var causeSentence = owner != null
                ? "Right now it looks like " + owner + " is holding it."
                : "This is almost always a leftover vPilot.exe still running in the background " +
                    "(check Task Manager) or a duplicate Handoff plugin install -- not some other, " +
                    "unrelated program.";

            var messageFont = new Font("Segoe UI", 9.5f);
            var messageText = "TCP port " + tcp.Port + " is already in use, so the tablet can't connect. " +
                causeSentence + "\n\n" +
                "Auto-discovery will keep working automatically after you change the port below " +
                "-- but if the tablet currently has a manual IP:port entered in Settings, update " +
                "it to match the new port too.";
            // Measured, not guessed -- a hardcoded label height previously clipped this text
            // silently instead of wrapping into view (found during issue #98's own manual test).
            var messageHeight = TextRenderer.MeasureText(messageText, messageFont, new Size(SectionWidth, int.MaxValue), TextFormatFlags.WordBreak).Height;

            var message = new Label
            {
                Text = messageText,
                AutoSize = false,
                Location = new Point(0, 0),
                Size = new Size(SectionWidth, messageHeight),
                Font = messageFont
            };

            var controlsTop = messageHeight + 12;
            var portLabel = new Label
            {
                Text = "New port:",
                AutoSize = true,
                Location = new Point(0, controlsTop + 5),
                Font = new Font("Segoe UI", 9.5f, FontStyle.Bold)
            };
            var portInput = new NumericUpDown
            {
                Minimum = 1024,
                Maximum = 65535,
                Value = tcp.Port,
                Width = 80,
                Location = new Point(80, controlsTop + 2)
            };
            var saveButton = new Button
            {
                Text = "Save && Restart Listening",
                Width = 190,
                Height = 28,
                Location = new Point(170, controlsTop)
            };
            var resultLabel = new Label
            {
                Text = string.Empty,
                AutoSize = false,
                Location = new Point(0, controlsTop + 38),
                Size = new Size(SectionWidth, 40),
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

            var panel = new Panel { Width = SectionWidth, Height = controlsTop + 38 + resultLabel.Height, Margin = new Padding(0, 6, 0, 6) };
            panel.Controls.Add(message);
            panel.Controls.Add(portLabel);
            panel.Controls.Add(portInput);
            panel.Controls.Add(saveButton);
            panel.Controls.Add(resultLabel);
            return panel;
        }

        private static Panel BuildUdpSection(PortConflictInfo udp, int currentTcpPort, int formWidth)
        {
            if (!udp.IsConflicted) return BuildOkStatus("UDP discovery", udp.Port);

            var addresses = GetLocalIPv4Addresses();
            var ipHint = addresses.Count == 0 ? "this PC's IP address"
                : addresses.Count == 1 ? "this PC's IP address (" + addresses[0] + ")"
                : "one of this PC's IP addresses (" + string.Join(", ", addresses) + ")";

            // The TCP port only needs calling out when it's not the default Android already
            // falls back to (HandoffConnectionService.resolveHost's ":port"-optional parsing,
            // issue #98) -- on the common default-port path, telling the pilot to also type a
            // port they'd never actually need to enter is just extra, unnecessary work.
            var tcpPortHint = currentTcpPort == WsPortModel.DefaultPort
                ? string.Empty
                : " and the TCP port shown above (" + currentTcpPort + ")";

            var owner = PortOwnerLookup.TryDescribeOwner(udp.Port, tcp: false);
            var ownerSentence = owner != null ? " It's currently held by " + owner + "." : string.Empty;

            var messageFont = new Font("Segoe UI", 9.5f);
            var messageText = "UDP discovery port " + udp.Port + " is already in use." + ownerSentence +
                " Sorry, this port can't be changed -- auto-discovery is unavailable this session. " +
                "Enter " + ipHint + tcpPortHint + " manually on the tablet instead.";
            var messageHeight = TextRenderer.MeasureText(messageText, messageFont, new Size(SectionWidth, int.MaxValue), TextFormatFlags.WordBreak).Height;

            var panel = new Panel { Width = SectionWidth, Height = messageHeight, Margin = new Padding(0, 6, 0, 6) };

            var message = new Label
            {
                Text = messageText,
                AutoSize = false,
                Dock = DockStyle.Fill,
                Font = messageFont
            };

            panel.Controls.Add(message);
            return panel;
        }

        /// <summary>The LAN-reachable IPv4 addresses of this PC's active network interfaces,
        /// loopback excluded -- shown in the UDP-conflict section so the pilot doesn't have to go
        /// find this themselves (ipconfig) before typing it into the tablet's manual IP:port
        /// field. Filtered to OperationalStatus.Up interfaces, same reasoning HandoffCertificateStore
        /// and HandoffDiscoveryListener don't need (they bind to 0.0.0.0/all interfaces), but a
        /// human reading this dialog needs one concrete, actually-reachable address to type.</summary>
        private static List<string> GetLocalIPv4Addresses()
        {
            try
            {
                return NetworkInterface.GetAllNetworkInterfaces()
                    .Where(nic => nic.OperationalStatus == OperationalStatus.Up)
                    .SelectMany(nic => nic.GetIPProperties().UnicastAddresses)
                    .Select(addr => addr.Address)
                    .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                    .Select(ip => ip.ToString())
                    .Distinct()
                    .ToList();
            }
            catch
            {
                return new List<string>();
            }
        }
    }
}
