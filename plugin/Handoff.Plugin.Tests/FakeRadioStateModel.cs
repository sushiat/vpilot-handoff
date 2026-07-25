using System;

namespace Handoff.Plugin.Tests
{
    /// <summary>
    /// Test double for IRadioStateModel -- lets tests set Current/Telemetry directly and fire
    /// Changed on demand, standing in for the real RadioStateModel (which is driven by a
    /// background thread reading a named pipe and has no externally-settable state).
    /// </summary>
    internal sealed class FakeRadioStateModel : IRadioStateModel
    {
        public RadioState Current { get; set; } = new RadioState(null, null, null, null, false, null, DateTimeOffset.Now);
        public OwnshipTelemetry Telemetry { get; set; } = new OwnshipTelemetry(null, null, null, null, null, null, null, DateTimeOffset.Now);

        public event EventHandler Changed;

        public void RaiseChanged() => Changed?.Invoke(this, EventArgs.Empty);
    }
}
