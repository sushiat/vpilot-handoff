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

        // A "contact me" arrived from a callsign never seen via IBroker.ControllerAdded (an ATC
        // station on a frequency none of our data sources expose -- see
        // HandoffControllerStateModel.OnChatChanged). There's no IBroker/SimConnect telemetry for
        // it and never will be: no ControllerAdded/Deleted/FrequencyChanged will ever fire, so this
        // record is synthesized purely to surface the contact-me. Deliberately NOT serialized --
        // it only drives two plugin-internal lifecycle steps the client doesn't need to know about:
        // PruneExpired drops the record once its contact-me lapses (nothing else ever would), and a
        // real ControllerAdded later Promote()s it to a normal record. On the wire it rides the
        // ordinary IsContactMe path, showing its real callsign and a parsed-or-zero frequency.
        public bool IsOffList { get; }

        public HandoffController(
            string callsign, int frequency, double latitude, double longitude,
            bool isHidden = false, DateTimeOffset? hiddenSinceUtc = null,
            DateTimeOffset? contactMeExpiresAtUtc = null, DateTimeOffset? selcalExpiresAtUtc = null,
            bool isPinned = false, bool isOffList = false)
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
            IsOffList = isOffList;
        }

        /// <summary>An off-list "contact me" (see <see cref="IsOffList"/>): a private "contact me" from a callsign never seen via ControllerAdded. Frequency is parsed from the message text when present, else 0 (unknown -- the pilot reads the original chat message).</summary>
        public static HandoffController OffList(string callsign, int frequency, DateTimeOffset contactMeExpiresAtUtc) =>
            new HandoffController(callsign, frequency, 0, 0, contactMeExpiresAtUtc: contactMeExpiresAtUtc, isOffList: true);

        internal HandoffController WithFrequency(int frequency) =>
            new HandoffController(Callsign, frequency, Latitude, Longitude, IsHidden, HiddenSinceUtc, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned, IsOffList);

        internal HandoffController WithLocation(double latitude, double longitude) =>
            new HandoffController(Callsign, Frequency, latitude, longitude, IsHidden, HiddenSinceUtc, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned, IsOffList);

        /// <summary>ControllerAdded firing again for an already-known, currently-hidden callsign -- a reconnect within the expiry window. Every other field survives untouched.</summary>
        internal HandoffController Reconnected() =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, false, null, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned, IsOffList);

        /// <summary>A real ControllerAdded finally arrived for a callsign we'd only ever seen as an off-list contact-me -- adopt its real frequency/location and drop the synthetic marker, keeping any outstanding contact-me/SELCAL/pin.</summary>
        internal HandoffController Promoted(int frequency, double latitude, double longitude) =>
            new HandoffController(Callsign, frequency, latitude, longitude, false, null, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned, false);

        /// <summary>ControllerDeleted -- marked hidden rather than removed, so a brief reconnect can restore it via Reconnected() above.</summary>
        internal HandoffController Hidden(DateTimeOffset hiddenSinceUtc) =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, true, hiddenSinceUtc, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, IsPinned, IsOffList);

        internal HandoffController WithContactMeExpiry(DateTimeOffset? expiresAtUtc) =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, IsHidden, HiddenSinceUtc, expiresAtUtc, SelcalExpiresAtUtc, IsPinned, IsOffList);

        internal HandoffController WithSelcalExpiry(DateTimeOffset? expiresAtUtc) =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, IsHidden, HiddenSinceUtc, ContactMeExpiresAtUtc, expiresAtUtc, IsPinned, IsOffList);

        internal HandoffController WithPinned(bool isPinned) =>
            new HandoffController(Callsign, Frequency, Latitude, Longitude, IsHidden, HiddenSinceUtc, ContactMeExpiresAtUtc, SelcalExpiresAtUtc, isPinned, IsOffList);
    }
}
