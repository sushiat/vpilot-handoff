using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Immutable snapshot of a single connected ATC station, including all ranking-relevant
    /// metadata (contact-me/SELCAL expiry, pin, hidden-since) that used to live scattered across
    /// separate callsign-keyed models (ContactMeModel, SelcalActiveModel,
    /// ControllerRankingModel's own _pinnedCallsign) -- issue #18 unifies them onto one record.
    ///
    /// Every mutation is copy-on-write (WithXxx returns a new instance), same discipline as the
    /// old Controller.WithFrequency/WithLocation -- no setters anywhere. This is what makes the
    /// "lock briefly to swap/read a snapshot, then work outside the lock" pattern
    /// (HandoffControllerStateModel.Controllers, ControllerRankingModel.Current) safe without a
    /// deeper copy: once an instance is published, nothing will ever mutate it, so a reader
    /// holding a reference to it (or a list of them) needs no further synchronization.
    /// </summary>
    public sealed class HandoffController
    {
        public string Callsign { get; }
        public int Frequency { get; }      // vPilot's compressed-integer format, e.g. 23725 == 123.725
        public double Latitude { get; }
        public double Longitude { get; }

        // Disconnect handling: set instead of removing outright, so a brief reconnect (an FSD
        // blip, not a real end to the session) doesn't wipe an outstanding contact-me/SELCAL/pin.
        // HandoffControllerStateModel drops the record entirely once it's been hidden longer
        // than its own expiry window -- see that class for the actual timeout value.
        public bool IsHidden { get; }
        public DateTimeOffset? HiddenSinceUtc { get; }

        // Null when there's no outstanding request/alert right now.
        public DateTimeOffset? ContactMeExpiresAtUtc { get; }
        public DateTimeOffset? SelcalExpiresAtUtc { get; }

        public bool IsPinned { get; }

        public HandoffController(
            string callsign, int frequency, double latitude, double longitude,
            bool isHidden = false, DateTimeOffset? hiddenSinceUtc = null,
            DateTimeOffset? contactMeExpiresAtUtc = null, DateTimeOffset? selcalExpiresAtUtc = null,
            bool isPinned = false)
        {
            Callsign = callsign;
            Frequency = frequency;
            Latitude = latitude;
            Longitude = longitude;
            IsHidden = isHidden;
            HiddenSinceUtc = hiddenSinceUtc;
            ContactMeExpiresAtUtc = contactMeExpiresAtUtc;
            SelcalExpiresAtUtc = selcalExpiresAtUtc;
            IsPinned = isPinned;
        }

        internal HandoffController WithFrequency(int frequency) =>
            new HandoffController(Callsign, frequency, Latitude, Longitude, IsHidden, HiddenSinceUtc, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned);

        internal HandoffController WithLocation(double latitude, double longitude) =>
            new HandoffController(Callsign, Frequency, latitude, longitude, IsHidden, HiddenSinceUtc, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned);

        /// <summary>ControllerAdded firing again for an already-known, currently-hidden callsign -- a reconnect within the expiry window. Every other field survives untouched.</summary>
        internal HandoffController Reconnected() =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, false, null, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned);

        /// <summary>ControllerDeleted -- marked hidden rather than removed, so a brief reconnect can restore it via Reconnected() above.</summary>
        internal HandoffController Hidden(DateTimeOffset hiddenSinceUtc) =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, true, hiddenSinceUtc, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned);

        internal HandoffController WithContactMeExpiry(DateTimeOffset? expiresAtUtc) =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, IsHidden, HiddenSinceUtc, expiresAtUtc, SelcalExpiresAtUtc, IsPinned);

        internal HandoffController WithSelcalExpiry(DateTimeOffset? expiresAtUtc) =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, IsHidden, HiddenSinceUtc, ContactMeExpiresAtUtc, expiresAtUtc, IsPinned);

        internal HandoffController WithPinned(bool isPinned) =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, IsHidden, HiddenSinceUtc, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, isPinned);
    }
}
