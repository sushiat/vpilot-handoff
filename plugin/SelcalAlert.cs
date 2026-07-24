using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Immutable record of a SELCAL alert received from another station. Kept separate
    /// from ChatMessage since it carries no text — it's a "someone wants your attention"
    /// notification, not a message.
    /// </summary>
    public sealed class SelcalAlert
    {
        public string From { get; }
        public int[] Frequencies { get; }
        public DateTimeOffset Timestamp { get; }

        public SelcalAlert(string from, int[] frequencies, DateTimeOffset timestamp)
        {
            From = from;
            Frequencies = frequencies;
            Timestamp = timestamp;
        }
    }
}
