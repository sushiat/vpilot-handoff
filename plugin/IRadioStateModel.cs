using System;

namespace Handoff.Plugin
{
    /// <summary>
    /// The subset of RadioStateModel that ContactMeModel/ControllerRankingModel depend on.
    /// Extracted purely so those models can be unit tested against a fake -- RadioStateModel
    /// itself has no externally-settable state (it's driven by a background thread reading a
    /// named pipe from Handoff.RadioHost), so it can't otherwise stand in for itself in a test.
    /// </summary>
    public interface IRadioStateModel
    {
        RadioState Current { get; }
        OwnshipTelemetry Telemetry { get; }

        /// <summary>Whether the plugin's IPC connection to the Handoff.RadioHost process is currently up.</summary>
        bool IsRadioHostConnected { get; }

        /// <summary>Whether Handoff.RadioHost has ever reported a SimConnect-sourced radio state this session (see RadioStateModel for the staleness caveat).</summary>
        bool IsSimulatorConnected { get; }

        event EventHandler Changed;
    }
}
