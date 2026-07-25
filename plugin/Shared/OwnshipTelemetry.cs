using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// Immutable snapshot of raw ownship SimConnect telemetry (position, ground state,
    /// speed), as last read via the Handoff.RadioHost helper process. Deliberately just raw
    /// values with no interpretation -- phase-of-flight classification (which needs this
    /// combined with which controller is tuned, not just these values alone) is a separate
    /// piece of plugin-side logic layered on top later.
    /// </summary>
    public sealed class OwnshipTelemetry
    {
        // Null until the first SimConnect read completes.
        public bool? OnGround { get; }
        public double? GroundSpeedKnots { get; }
        public double? AltitudeAboveGroundFeet { get; }
        public double? VerticalSpeedFpm { get; }
        public double? HeadingDegrees { get; }
        public double? Latitude { get; }
        public double? Longitude { get; }

        public DateTimeOffset Timestamp { get; }

        public OwnshipTelemetry(bool? onGround, double? groundSpeedKnots, double? altitudeAboveGroundFeet, double? verticalSpeedFpm, double? headingDegrees, double? latitude, double? longitude, DateTimeOffset timestamp)
        {
            OnGround = onGround;
            GroundSpeedKnots = groundSpeedKnots;
            AltitudeAboveGroundFeet = altitudeAboveGroundFeet;
            VerticalSpeedFpm = verticalSpeedFpm;
            HeadingDegrees = headingDegrees;
            Latitude = latitude;
            Longitude = longitude;
            Timestamp = timestamp;
        }
    }
}
