using System;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Handoff.Plugin
{
    /// <summary>
    /// Answers LAN discovery requests so the Android app doesn't need the PC's IP typed in by
    /// hand. Plain UDP, not mDNS/Bonjour -- no extra dependency on either side, same
    /// avoid-setup-burden reasoning as choosing Fleck over HttpListener for
    /// HandoffWebSocketServer.
    ///
    /// Lifecycle: started once in HandoffPlugin.Initialize alongside HandoffWebSocketServer,
    /// lives for the plugin's lifetime.
    /// </summary>
    public sealed class HandoffDiscoveryListener
    {
        public const int Port = 48766;
        private const string RequestText = "HANDOFF_DISCOVER";
        private static readonly string ReplyJson = "{\"port\":" + HandoffWebSocketServer.Port + "}";

        private readonly Action<string> _logDebug;
        private UdpClient _client;

        public HandoffDiscoveryListener(Action<string> logDebug = null)
        {
            _logDebug = logDebug;
        }

        public void Start()
        {
            try
            {
                _client = new UdpClient(Port);
                _client.BeginReceive(OnReceive, null);
                Log("Listening on UDP " + Port);
            }
            catch (Exception ex)
            {
                Log("Failed to start discovery listener: " + ex);
            }
        }

        public void Stop()
        {
            _client?.Close();
            _client = null;
        }

        private void OnReceive(IAsyncResult result)
        {
            var client = _client;
            if (client == null) return;

            IPEndPoint sender = null;
            byte[] data;
            try
            {
                data = client.EndReceive(result, ref sender);
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (Exception ex)
            {
                Log("Failed to receive discovery request: " + ex.Message);
                return;
            }

            try
            {
                if (Encoding.UTF8.GetString(data) == RequestText)
                {
                    var reply = Encoding.UTF8.GetBytes(ReplyJson);
                    client.Send(reply, reply.Length, sender);
                    Log("Replied to discovery request from " + sender);
                }
            }
            catch (Exception ex)
            {
                Log("Failed to reply to discovery request: " + ex.Message);
            }

            client.BeginReceive(OnReceive, null);
        }

        private void Log(string message)
        {
            var line = "HandoffDiscoveryListener: " + message;
            System.Diagnostics.Debug.WriteLine(line);
            _logDebug?.Invoke(line);
        }
    }
}
