using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Issue #223 -- lets an action through at most once per minimum interval. Used to rate-limit
    /// the extra `controllers` broadcast sent on a COM frequency change, so a burst of radio
    /// changes (a swap sends active and standby back to back) can't flood the tablet.
    /// Thread-safe; the clock is injectable for tests.
    /// </summary>
    public class MinIntervalGate
    {
        private readonly TimeSpan _minInterval;
        private readonly Func<DateTimeOffset> _now;
        private readonly object _gate = new object();
        private DateTimeOffset? _lastAllowed;

        public MinIntervalGate(TimeSpan minInterval, Func<DateTimeOffset> now = null)
        {
            _minInterval = minInterval;
            _now = now ?? (() => DateTimeOffset.Now);
        }

        /// <summary>True (and records the time) if at least the minimum interval has passed since the last allowed call.</summary>
        public bool TryEnter()
        {
            lock (_gate)
            {
                var now = _now();
                if (_lastAllowed.HasValue && now - _lastAllowed.Value < _minInterval) return false;
                _lastAllowed = now;
                return true;
            }
        }
    }
}
