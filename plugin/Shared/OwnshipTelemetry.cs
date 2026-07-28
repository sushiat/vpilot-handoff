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

        // Standard-pressure (29.92"/1013.25hPa) referenced altitude -- i.e. flight-level units
        // (PressureAltitudeFeet / 100 = FL), independent of local QNH. SeaLevelPressureHpa is
        // the sim's actual local QNH at the aircraft's position (not the pilot's altimeter
        // Kohlsman subscale) -- together they let a caller derive a QNH-true AMSL altitude for
        // comparison against VATGlasses sectors near the ground (see issue #9 phase 2).
        public double? PressureAltitudeFeet { get; }
        public double? SeaLevelPressureHpa { get; }

        public DateTimeOffset Timestamp { get; }

        public OwnshipTelemetry(bool? onGround, double? groundSpeedKnots, double? altitudeAboveGroundFeet, double? verticalSpeedFpm, double? headingDegrees, double? latitude, double? longitude, DateTimeOffset timestamp, double? pressureAltitudeFeet = null, double? seaLevelPressureHpa = null)
        {
            OnGround = onGround;
            GroundSpeedKnots = groundSpeedKnots;
            AltitudeAboveGroundFeet = altitudeAboveGroundFeet;
            VerticalSpeedFpm = verticalSpeedFpm;
            HeadingDegrees = headingDegrees;
            Latitude = latitude;
            Longitude = longitude;
            Timestamp = timestamp;
            PressureAltitudeFeet = pressureAltitudeFeet;
            SeaLevelPressureHpa = seaLevelPressureHpa;
        }
    }
}
