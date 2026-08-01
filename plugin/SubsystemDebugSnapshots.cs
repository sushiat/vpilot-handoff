using System;
using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// Issue #65 -- one small DTO per non-ranking subsystem's BuildDebugSnapshot() output,
    /// assembled together by DebugSnapshotService into the full debug snapshot file. Kept in one
    /// file since each is a handful of fields with no shared behavior -- see the owning model
    /// (named in each summary) for what actually populates it.
    /// </summary>
    public sealed class RadioDebugSnapshot
    {
        public bool RadioHostConnected { get; }
        public bool SimulatorConnected { get; }
        public RadioState Current { get; }
        public OwnshipTelemetry Telemetry { get; }

        public RadioDebugSnapshot(bool radioHostConnected, bool simulatorConnected, RadioState current, OwnshipTelemetry telemetry)
        {
            RadioHostConnected = radioHostConnected;
            SimulatorConnected = simulatorConnected;
            Current = current;
            Telemetry = telemetry;
        }
    }

    /// <summary>See VatsimDataFeedModel.BuildDebugSnapshot.</summary>
    public sealed class VatsimFeedDebugSnapshot
    {
        public bool Connected { get; }
        public int ControllerCount { get; }
        public int PilotCount { get; }
        public DateTimeOffset? LastPollAt { get; }
        public string LastError { get; }

        public VatsimFeedDebugSnapshot(bool connected, int controllerCount, int pilotCount, DateTimeOffset? lastPollAt, string lastError)
        {
            Connected = connected;
            ControllerCount = controllerCount;
            PilotCount = pilotCount;
            LastPollAt = lastPollAt;
            LastError = lastError;
        }
    }

    /// <summary>See FlightPlanModel.BuildDebugSnapshot. CredentialsPresent is deliberately a bool, never the credential values -- unlike public VATSIM data, SimBrief credentials are not public.</summary>
    public sealed class FlightPlanDebugSnapshot
    {
        public bool HasFetchedSuccessfully { get; }
        public bool CredentialsPresent { get; }
        public DateTimeOffset? LastFetchAttemptAt { get; }
        public string LastError { get; }
        public FlightPlan Current { get; }

        public FlightPlanDebugSnapshot(bool hasFetchedSuccessfully, bool credentialsPresent, DateTimeOffset? lastFetchAttemptAt, string lastError, FlightPlan current)
        {
            HasFetchedSuccessfully = hasFetchedSuccessfully;
            CredentialsPresent = credentialsPresent;
            LastFetchAttemptAt = lastFetchAttemptAt;
            LastError = lastError;
            Current = current;
        }
    }

    /// <summary>See HandoffControllerStateModel.BuildDebugSnapshot -- the raw pre-ranking list, including currently-hidden (grace-window) entries, so "why is X missing entirely" is answerable from the file.</summary>
    public sealed class ControllerStateDebugSnapshot
    {
        public IReadOnlyList<HandoffController> AllControllersIncludingHidden { get; }
        public int PinnedCount { get; }
        public int ContactMePendingCount { get; }
        public int SelcalPendingCount { get; }

        public ControllerStateDebugSnapshot(IReadOnlyList<HandoffController> allControllersIncludingHidden, int pinnedCount, int contactMePendingCount, int selcalPendingCount)
        {
            AllControllersIncludingHidden = allControllersIncludingHidden;
            PinnedCount = pinnedCount;
            ContactMePendingCount = contactMePendingCount;
            SelcalPendingCount = selcalPendingCount;
        }
    }

    /// <summary>See VatGlassesDataModel.BuildDebugSnapshot.</summary>
    public sealed class VatGlassesDebugSnapshot
    {
        public IReadOnlyList<string> LoadedRegionFiles { get; }
        public string CachedCommitSha { get; }

        public VatGlassesDebugSnapshot(IReadOnlyList<string> loadedRegionFiles, string cachedCommitSha)
        {
            LoadedRegionFiles = loadedRegionFiles;
            CachedCommitSha = cachedCommitSha;
        }
    }

    /// <summary>See VatSpyDataModel.BuildDebugSnapshot.</summary>
    public sealed class VatSpyDebugSnapshot
    {
        public int BoundaryCount { get; }
        public int AirportCount { get; }
        public string CachedCommitSha { get; }

        public VatSpyDebugSnapshot(int boundaryCount, int airportCount, string cachedCommitSha)
        {
            BoundaryCount = boundaryCount;
            AirportCount = airportCount;
            CachedCommitSha = cachedCommitSha;
        }
    }

    /// <summary>See HandoffPairedClientStore.BuildDebugSnapshot / HandoffPairingSession.BuildDebugSnapshot -- counts and presence only, never token/code plaintext (both are live secrets).</summary>
    public sealed class PairingDebugSnapshot
    {
        public int PairedClientCount { get; }
        public bool PairingCodeCurrentlyActive { get; }

        public PairingDebugSnapshot(int pairedClientCount, bool pairingCodeCurrentlyActive)
        {
            PairedClientCount = pairedClientCount;
            PairingCodeCurrentlyActive = pairingCodeCurrentlyActive;
        }
    }

    /// <summary>
    /// Issue #65 -- lean, plain-language per-subsystem health lines for the debug overlay's
    /// "Systems" section (docs/protocol.md's `subsystemStatus.systemsDebug`). Deliberately not
    /// the exhaustive per-subsystem detail each model's own BuildDebugSnapshot() exposes -- that
    /// stays snapshot-file-only, same split the issue already draws for VATGlasses specifically,
    /// generalized here to every subsystem so this rides the low-frequency subsystemStatus
    /// broadcast instead of the 1s ranking cadence.
    /// </summary>
    public sealed class SystemsDebugInfo
    {
        public bool RadioHostConnected { get; }
        public bool SimulatorConnected { get; }
        public DateTimeOffset? LastTelemetryAt { get; }
        public bool VatsimFeedConnected { get; }
        public DateTimeOffset? VatsimFeedLastPollAt { get; }
        public bool SimbriefFetchedSuccessfully { get; }
        public string SimbriefLastError { get; }
        public int VatGlassesLoadedRegionCount { get; }
        public int VatSpyBoundaryCount { get; }
        public int PairedClientCount { get; }
        public int AuthenticatedSocketCount { get; }
        public int ActiveOperationCount { get; }

        public SystemsDebugInfo(
            bool radioHostConnected, bool simulatorConnected, DateTimeOffset? lastTelemetryAt,
            bool vatsimFeedConnected, DateTimeOffset? vatsimFeedLastPollAt,
            bool simbriefFetchedSuccessfully, string simbriefLastError,
            int vatGlassesLoadedRegionCount, int vatSpyBoundaryCount,
            int pairedClientCount, int authenticatedSocketCount, int activeOperationCount)
        {
            RadioHostConnected = radioHostConnected;
            SimulatorConnected = simulatorConnected;
            LastTelemetryAt = lastTelemetryAt;
            VatsimFeedConnected = vatsimFeedConnected;
            VatsimFeedLastPollAt = vatsimFeedLastPollAt;
            SimbriefFetchedSuccessfully = simbriefFetchedSuccessfully;
            SimbriefLastError = simbriefLastError;
            VatGlassesLoadedRegionCount = vatGlassesLoadedRegionCount;
            VatSpyBoundaryCount = vatSpyBoundaryCount;
            PairedClientCount = pairedClientCount;
            AuthenticatedSocketCount = authenticatedSocketCount;
            ActiveOperationCount = activeOperationCount;
        }
    }
}
