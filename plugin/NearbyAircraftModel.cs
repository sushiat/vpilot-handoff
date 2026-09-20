using System;
using System.Collections.Generic;
using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;

namespace Handoff.Plugin
{
    /// <summary>
    /// Live list of other traffic within reporting range, for the chat panel's "start chat with
    /// a nearby aircraft" dialog (see docs/protocol.md "Not yet in this protocol"). Built from
    /// IBroker's AircraftAdded/AircraftUpdated/AircraftDeleted events -- same pattern as
    /// ControllerStateModel -- rather than the public VATSIM data feed, since IBroker already
    /// reports other aircraft in real time with no ~15s lag, and (per CLAUDE.md) only ever
    /// reports other aircraft, never ownship, so no self-filtering is needed.
    ///
    /// Distance is computed against ownship's own position (IRadioStateModel.Telemetry, sourced
    /// from SimConnect via Handoff.RadioHost -- IBroker has no ownship position of its own),
    /// filtered to RadiusNauticalMiles, closest first, matching the design's "AIRCRAFT WITHIN
    /// 20NM · CLOSEST FIRST" header. Every aircraft/telemetry change just marks state dirty;
    /// the actual recompute is deferred to the next Current read (issue #134 -- a burst of
    /// IBroker events in a busy area must not each cost a full recompute).
    /// </summary>
    public sealed class NearbyAircraftModel
    {
        private const double RadiusNauticalMiles = 20;

        private readonly object _gate = new object();
        private readonly IRadioStateModel _radioState;
        private readonly Dictionary<string, AircraftPosition> _aircraft =
            new Dictionary<string, AircraftPosition>(StringComparer.OrdinalIgnoreCase);
        private IReadOnlyList<NearbyAircraft> _current = new List<NearbyAircraft>();
        private bool _dirty;

        public event EventHandler Changed;

        public NearbyAircraftModel(IBroker broker, IRadioStateModel radioState)
        {
            if (broker == null) throw new ArgumentNullException(nameof(broker));
            _radioState = radioState ?? throw new ArgumentNullException(nameof(radioState));

            broker.AircraftAdded += OnAircraftAdded;
            broker.AircraftUpdated += OnAircraftUpdated;
            broker.AircraftDeleted += OnAircraftDeleted;
            _radioState.Changed += (s, e) => { lock (_gate) { _dirty = true; } };
        }

        /// <summary>
        /// Point-in-time snapshot, within RadiusNauticalMiles of ownship, closest first.
        /// Recompute is deferred to read time rather than run on every IBroker event --
        /// a burst of AircraftAdded events (e.g. connecting in a busy area) only ever
        /// costs one actual recompute, on whichever thread next reads this property (see
        /// issue #134).
        /// </summary>
        public IReadOnlyList<NearbyAircraft> Current
        {
            get
            {
                IReadOnlyList<NearbyAircraft> snapshot;
                bool recomputed;
                lock (_gate)
                {
                    recomputed = _dirty;
                    if (_dirty)
                    {
                        _dirty = false;
                        RecomputeLocked();
                    }
                    snapshot = _current;
                }
                // Raised outside _gate so a Changed subscriber can't block other threads
                // waiting to read Current.
                if (recomputed) Changed?.Invoke(this, EventArgs.Empty);
                return snapshot;
            }
        }

        private void OnAircraftAdded(object sender, AircraftAddedEventArgs e)
        {
            lock (_gate) { _aircraft[e.Callsign] = new AircraftPosition(e.TypeCode, e.Latitude, e.Longitude); _dirty = true; }
        }

        private void OnAircraftUpdated(object sender, AircraftUpdatedEventArgs e)
        {
            lock (_gate)
            {
                if (_aircraft.TryGetValue(e.Callsign, out var existing))
                    _aircraft[e.Callsign] = existing.WithLocation(e.Latitude, e.Longitude);
                // Unknown callsign: ignore rather than fabricate a partial entry (mirrors
                // ControllerStateModel's OnControllerFrequencyChanged handling).
                _dirty = true;
            }
        }

        private void OnAircraftDeleted(object sender, AircraftDeletedEventArgs e)
        {
            lock (_gate) { _aircraft.Remove(e.Callsign); _dirty = true; }
        }

        /// <summary>Assumes the caller already holds _gate for the duration of this call.</summary>
        private void RecomputeLocked()
        {
            var telemetry = _radioState.Telemetry;
            var result = new List<NearbyAircraft>();

            if (telemetry.Latitude.HasValue && telemetry.Longitude.HasValue)
            {
                result = _aircraft
                    .Select(kvp => new
                    {
                        kvp.Key,
                        kvp.Value.TypeCode,
                        DistanceNm = GeoDistance.NauticalMiles(telemetry.Latitude.Value, telemetry.Longitude.Value, kvp.Value.Latitude, kvp.Value.Longitude)
                    })
                    .Where(a => a.DistanceNm <= RadiusNauticalMiles)
                    .OrderBy(a => a.DistanceNm)
                    .Select(a => new NearbyAircraft(a.Key, a.TypeCode, a.DistanceNm))
                    .ToList();
            }

            _current = result;
        }

        private readonly struct AircraftPosition
        {
            public string TypeCode { get; }
            public double Latitude { get; }
            public double Longitude { get; }

            public AircraftPosition(string typeCode, double latitude, double longitude)
            {
                TypeCode = typeCode;
                Latitude = latitude;
                Longitude = longitude;
            }

            public AircraftPosition WithLocation(double latitude, double longitude) =>
                new AircraftPosition(TypeCode, latitude, longitude);
        }
    }
}
