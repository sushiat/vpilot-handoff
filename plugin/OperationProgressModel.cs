using System;
using System.Collections.Generic;

namespace Handoff.Plugin
{
    /// <summary>
    /// Tracks in-progress background operations (e.g. VatGlassesDataModel's startup sync) and
    /// broadcasts step-by-step status over the operationProgress protocol message -- see
    /// docs/protocol.md. Deliberately generic, not VatGlasses-specific: any future long-running
    /// plugin operation can reuse this via its own operationId rather than growing its own
    /// bespoke protocol message.
    ///
    /// Unlike every other *Model in this codebase, Changed here carries the single operation that
    /// just changed (OperationProgressEventArgs), not "go re-read my current state" -- this is an
    /// event stream (closer to a resend of one message than a resend of a snapshot), not
    /// resendable full state like docs/protocol.md's other messages.
    /// </summary>
    public sealed class OperationProgressModel
    {
        private readonly object _gate = new object();
        private readonly Dictionary<string, string> _activeStatus = new Dictionary<string, string>(StringComparer.Ordinal);

        public event EventHandler<OperationProgressEventArgs> Changed;

        /// <summary>Point-in-time snapshot of every still-active operation's latest status, keyed
        /// by operationId -- used to catch up a client that connects mid-operation.</summary>
        public IReadOnlyDictionary<string, string> ActiveOperations
        {
            get { lock (_gate) { return new Dictionary<string, string>(_activeStatus, StringComparer.Ordinal); } }
        }

        /// <summary>Reports (or updates) an in-progress step for the given operation.</summary>
        public void Report(string operationId, string status)
        {
            if (string.IsNullOrEmpty(operationId)) throw new ArgumentNullException(nameof(operationId));

            lock (_gate) { _activeStatus[operationId] = status; }
            Changed?.Invoke(this, new OperationProgressEventArgs(operationId, status, finished: false));
        }

        /// <summary>
        /// Marks the given operation finished -- the "end of update" signal clients use to swap
        /// their spinner for a success/failure icon (docs/protocol.md). <paramref name="status"/>
        /// is optional: pass it to report a final summary status (e.g. "VatGlasses data up to
        /// date") even when Report was never called for this operation at all (the common, fast,
        /// nothing-changed case); omit it to just echo whatever the last reported status was.
        /// <paramref name="success"/> drives which icon/linger-duration a client shows -- see
        /// docs/protocol.md's operationProgress message.
        /// </summary>
        public void Finish(string operationId, string status = null, bool success = true)
        {
            if (string.IsNullOrEmpty(operationId)) throw new ArgumentNullException(nameof(operationId));

            string lastStatus;
            lock (_gate)
            {
                _activeStatus.TryGetValue(operationId, out lastStatus);
                _activeStatus.Remove(operationId);
            }
            Changed?.Invoke(this, new OperationProgressEventArgs(operationId, status ?? lastStatus, finished: true, success: success));
        }
    }

    public sealed class OperationProgressEventArgs : EventArgs
    {
        public string OperationId { get; }
        public string Status { get; }
        public bool Finished { get; }

        /// <summary>Only meaningful when Finished is true -- see OperationProgressModel.Finish.</summary>
        public bool Success { get; }

        public OperationProgressEventArgs(string operationId, string status, bool finished, bool success = true)
        {
            OperationId = operationId;
            Status = status;
            Finished = finished;
            Success = success;
        }
    }
}
