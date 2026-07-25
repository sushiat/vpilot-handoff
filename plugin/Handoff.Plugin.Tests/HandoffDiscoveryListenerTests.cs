using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Xunit;

namespace Handoff.Plugin.Tests
{
    public class HandoffDiscoveryListenerTests
    {
        [Fact]
        public void RepliesToDiscoveryRequestWithPort()
        {
            var listener = new HandoffDiscoveryListener();
            listener.Start();
            try
            {
                using (var client = new UdpClient())
                {
                    client.Client.ReceiveTimeout = 2000;
                    var request = Encoding.UTF8.GetBytes("HANDOFF_DISCOVER");
                    client.Send(request, request.Length, new IPEndPoint(IPAddress.Loopback, HandoffDiscoveryListener.Port));

                    var remote = new IPEndPoint(IPAddress.Any, 0);
                    var reply = client.Receive(ref remote);

                    Assert.Equal("{\"port\":48765}", Encoding.UTF8.GetString(reply));
                }
            }
            finally
            {
                listener.Stop();
            }
        }

        [Fact]
        public void IgnoresUnrecognizedRequests()
        {
            var listener = new HandoffDiscoveryListener();
            listener.Start();
            try
            {
                using (var client = new UdpClient())
                {
                    client.Client.ReceiveTimeout = 500;
                    var request = Encoding.UTF8.GetBytes("SOMETHING_ELSE");
                    client.Send(request, request.Length, new IPEndPoint(IPAddress.Loopback, HandoffDiscoveryListener.Port));

                    Assert.Throws<SocketException>(() =>
                    {
                        var remote = new IPEndPoint(IPAddress.Any, 0);
                        client.Receive(ref remote);
                    });
                }
            }
            finally
            {
                listener.Stop();
            }
        }
    }
}
