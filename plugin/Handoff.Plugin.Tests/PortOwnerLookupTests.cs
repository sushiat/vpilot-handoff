using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class PortOwnerLookupTests
    {
        [Fact]
        public void TryDescribeOwner_Tcp_FindsThisTestProcess()
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            try
            {
                var port = ((IPEndPoint)listener.LocalEndpoint).Port;

                var description = PortOwnerLookup.TryDescribeOwner(port, tcp: true);

                var currentPid = Process.GetCurrentProcess().Id;
                Assert.NotNull(description);
                Assert.Contains("PID " + currentPid, description);
            }
            finally
            {
                listener.Stop();
            }
        }

        [Fact]
        public void TryDescribeOwner_Udp_FindsThisTestProcess()
        {
            var client = new UdpClient(0);
            try
            {
                var port = ((IPEndPoint)client.Client.LocalEndPoint).Port;

                var description = PortOwnerLookup.TryDescribeOwner(port, tcp: false);

                var currentPid = Process.GetCurrentProcess().Id;
                Assert.NotNull(description);
                Assert.Contains("PID " + currentPid, description);
            }
            finally
            {
                client.Close();
            }
        }

        [Fact]
        public void TryDescribeOwner_NothingBound_ReturnsNull()
        {
            // A random high port very unlikely to be in use during a test run -- if it does
            // collide, that's a real bind failure the test would surface anyway (see similar
            // reasoning in HandoffDiscoveryListenerTests).
            var description = PortOwnerLookup.TryDescribeOwner(59123, tcp: true);

            Assert.Null(description);
        }
    }
}
