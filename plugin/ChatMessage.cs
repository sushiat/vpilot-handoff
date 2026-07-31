using System;

namespace Handoff.Plugin
{
    public enum ChatChannel { Private, Radio, Broadcast }

    public enum ChatDirection { Incoming, Outgoing }

    /// <summary>
    /// Immutable record of a single chat message, incoming or outgoing, across any of
    /// vPilot's three text channels (private, radio, broadcast).
    /// </summary>
    public sealed class ChatMessage
    {
        public ChatChannel Channel { get; }
        public ChatDirection Direction { get; }

        // Private: the other party's callsign (sender if Incoming, recipient if Outgoing).
        // Broadcast: the sender's callsign. Radio: null (frequencies identify the channel instead).
        public string Peer { get; }

        public string Text { get; }

        // Radio only; vPilot's compressed-integer format, e.g. 23725 == 123.725. Null otherwise.
        public int[] Frequencies { get; }

        // Radio incoming only: the transmitting station's callsign (RadioMessageReceivedEventArgs.From).
        // Null for every other channel/direction -- Peer already covers private/broadcast.
        public string From { get; }

        public DateTimeOffset Timestamp { get; }

        public ChatMessage(ChatChannel channel, ChatDirection direction, string peer, string text, int[] frequencies, DateTimeOffset timestamp, string from = null)
        {
            Channel = channel;
            Direction = direction;
            Peer = peer;
            Text = text;
            Frequencies = frequencies;
            Timestamp = timestamp;
            From = from;
        }
    }
}
