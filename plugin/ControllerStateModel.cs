using System;
using System.Collections.Generic;
using System.Linq;
using RossCarlson.Vatsim.Vpilot.Plugins;
using RossCarlson.Vatsim.Vpilot.Plugins.Events;

namespace Handoff.Plugin
{
    /// <summary>
    /// Live in-memory model of currently-connected ATC stations, built entirely from
    /// IBroker's controller events. Takes an IBroker at construction and wires itself
    /// up immediately (no separate Start/Stop) so it can be unit-tested against a fake
    /// IBroker without vPilot or a real plugin host.
    ///
    /// Threading: vPilot raises these events from its own thread(s). A lock around the
    /// backing dictionary keeps individual add/remove/update operations atomic and
    /// snapshot reads consistent; this is not meant to guarantee anything stronger
    /// (e.g. no "read-then-act" transactions across calls) since this is a single
    /// local plugin talking to one local client, not a concurrent server.
    /// </summary>
    public sealed class ControllerStateModel
    {
        private readonly object _gate = new object();
        private readonly Dictionary<string, Controller> _controllers =
            new Dictionary<string, Controller>(StringComparer.OrdinalIgnoreCase);

        /// <summary>
        /// Fires after any add/delete/frequency/location change. Payload-free by design
        /// for now — consumers (e.g. a future WebSocket server) re-read
        /// <see cref="Controllers"/> for the current snapshot rather than being handed
        /// a diff.
        /// </summary>
        public event EventHandler Changed;

        public ControllerStateModel(IBroker broker)
        {
            if (broker == null) throw new ArgumentNullException(nameof(broker));

            broker.ControllerAdded += OnControllerAdded;
            broker.ControllerDeleted += OnControllerDeleted;
            broker.ControllerFrequencyChanged += OnControllerFrequencyChanged;
            broker.ControllerLocationChanged += OnControllerLocationChanged;
        }

        /// <summary>Point-in-time snapshot of all currently-connected controllers.</summary>
        public IReadOnlyCollection<Controller> Controllers
        {
            get { lock (_gate) { return _controllers.Values.ToList(); } }
        }

        private void OnControllerAdded(object sender, ControllerAddedEventArgs e)
        {
            lock (_gate)
            {
                _controllers[e.Callsign] = new Controller(e.Callsign, e.Frequency, e.Latitude, e.Longitude);
            }
            RaiseChanged();
        }

        private void OnControllerDeleted(object sender, ControllerDeletedEventArgs e)
        {
            lock (_gate) { _controllers.Remove(e.Callsign); }
            RaiseChanged();
        }

        private void OnControllerFrequencyChanged(object sender, ControllerFrequencyChangedEventArgs e)
        {
            lock (_gate)
            {
                if (_controllers.TryGetValue(e.Callsign, out var existing))
                    _controllers[e.Callsign] = existing.WithFrequency(e.NewFrequency);
                // Unknown callsign: ignore rather than fabricate a partial entry.
                // Would only happen if vPilot fired FrequencyChanged before Added,
                // which the real client isn't expected to do.
            }
            RaiseChanged();
        }

        private void OnControllerLocationChanged(object sender, ControllerLocationChangedEventArgs e)
        {
            lock (_gate)
            {
                if (_controllers.TryGetValue(e.Callsign, out var existing))
                    _controllers[e.Callsign] = existing.WithLocation(e.NewLatitude, e.NewLongitude);
            }
            RaiseChanged();
        }

        private void RaiseChanged() => Changed?.Invoke(this, EventArgs.Empty);
    }
}
