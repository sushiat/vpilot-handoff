using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// A single controller as re-ranked by ControllerRankingModel -- the full station list
    /// IBroker reports, reordered, plus boolean flags the Android app uses for colour-coding and
    /// badges. Nothing is ever hidden except a recently-disconnected station within its brief
    /// grace window (see HandoffController/HandoffControllerStateModel) -- every other connected
    /// station appears exactly once. Cid/Name/Facility/Rating are null until
    /// VatsimDataFeedModel solidifies enrichment for that callsign.
    ///
    /// Since issue #18, every flag the client displays is computed here and sent explicitly --
    /// the client never re-derives a badge/tag from other data it happens to have (e.g. comparing
    /// a controller's frequency against radioState's own standby fields, or tracking a locally-
    /// guessed "pinned callsign"). That was the actual bug behind IsPinned/IsStandbyTuned being
    /// client-side derivations for a while during the issue #17 session -- this class is the
    /// single source of truth for what the client shows, matching the same "no sorting on
    /// Android" principle already established for ranking order.
    /// </summary>
    public sealed class RankedController
    {
        public string Callsign { get; }
        public int Frequency { get; }
        public double Latitude { get; }
        public double Longitude { get; }

        public int? Cid { get; }
        public string Name { get; }
        public int? Facility { get; }
        public int? Rating { get; }

        // Facility/airport display name (e.g. "Heathrow Tower" for EGLL_TWR). Two sources, in
        // preference order (issue #11): the controller's own live ATIS/info text when it parses
        // cleanly into a name (VatAtisStationNameExtractor -- the controller's own live self-
        // description beats a generic composition), else a vatspy-composed place+suffix name
        // (VatSpyStationNaming). Null when neither source yields anything confident -- the
        // Android client falls back to parsing just the facility-suffix word from the callsign.
        public string StationName { get; }

        // The controller's raw ATIS/info lines (VATSIM data feed's "text_atis"), unprocessed --
        // StationName above is a derived summary of just the first line; this is the full text
        // for whatever richer client UI eventually wants it (issue #11 part (a), not yet built on
        // the Android side). Null when the controller hasn't set one/the feed omits it, same
        // "null means nothing here yet" convention as the other enrichment fields.
        public IReadOnlyList<string> TextAtis { get; }

        public bool RequestsContactMe { get; }
        public bool IsCurrent { get; }
        public bool IsContactMe { get; }

        // Since issue #18 (docs/controller-ranking.md): three-flag design, not the old two-flag
        // IsLikelyNextCandidate/IsApproaching split.
        //   - IsHighlighted: relevance/visibility -- "worth seeing," independent of whether it's
        //     the one to actually contact next (flight-plan match, proximity, or polygon
        //     containment/convergence, per buckets 6a-8a).
        //   - IsNext: confident and actionable -- exactly one qualifying candidate, unambiguous.
        //   - IsLikelyNext: the same underlying signal as IsNext but confidence-capped, either
        //     because multiple candidates are genuinely tied, or because route-relevance itself
        //     is unconfirmed (not on the flight plan) even when the geometry is unambiguous.
        public bool IsHighlighted { get; }
        public bool IsNext { get; }
        public bool IsLikelyNext { get; }

        // Manual bookmark (pinController/clearPinnedController) -- its own ranking bucket, never
        // a stand-in for IsCurrent. Persists even if the pinned station becomes current/standby
        // (both flags can be true at once); only cleared by an explicit unpin or the controller
        // going offline past HandoffControllerStateModel's hidden-expiry window.
        public bool IsPinned { get; }

        // Loaded into COM1 or COM2 standby, ready to swap to active the moment a handoff comes.
        public bool IsStandbyTuned { get; }

        // Currently-active SELCAL alert. Unlike IsContactMe, tuning the alerting frequency does
        // NOT clear this -- only an explicit dismissSelcal command or the alert's own expiry does.
        public bool IsSelcalActive { get; }

        public RankedController(
            string callsign, int frequency, double latitude, double longitude,
            int? cid, string name, int? facility, int? rating,
            bool requestsContactMe, bool isCurrent, bool isContactMe,
            bool isHighlighted, bool isNext, bool isLikelyNext,
            bool isPinned, bool isStandbyTuned, bool isSelcalActive,
            string stationName = null, IReadOnlyList<string> textAtis = null)
        {
            Callsign = callsign;
            Frequency = frequency;
            Latitude = latitude;
            Longitude = longitude;
            Cid = cid;
            Name = name;
            Facility = facility;
            Rating = rating;
            RequestsContactMe = requestsContactMe;
            IsCurrent = isCurrent;
            IsContactMe = isContactMe;
            IsHighlighted = isHighlighted;
            IsNext = isNext;
            IsLikelyNext = isLikelyNext;
            IsPinned = isPinned;
            IsStandbyTuned = isStandbyTuned;
            IsSelcalActive = isSelcalActive;
            StationName = stationName;
            TextAtis = textAtis != null && textAtis.Count > 0 ? textAtis : null;
        }
    }
}
