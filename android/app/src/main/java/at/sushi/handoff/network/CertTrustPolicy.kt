package at.sushi.handoff.network

/** Pure trust-on-first-use decision logic (issue #15) -- kept separate from
 *  [HandoffTlsTrust]/[HandoffWebSocketClient] so it's unit-testable without a real TLS handshake,
 *  same as the issue's own testing ask. */
sealed class TrustDecision {
    /** Presented fingerprint matches the one already pinned -- nothing for the UI to do. */
    object Matched : TrustDecision()

    /** No fingerprint pinned yet -- first connection to this plugin, needs a one-time
     *  Trust/Cancel prompt before being treated as trusted going forward. */
    data class FirstTrust(val fingerprint: String) : TrustDecision()

    /** A fingerprint *is* pinned, but doesn't match what was just presented -- could be a
     *  legitimate cert rotation (reinstalled plugin, deleted cache) or a genuine MITM/spoof.
     *  Surfaced as a distinct, scarier prompt rather than silently re-prompting like
     *  [FirstTrust], so a real spoof doesn't get quietly re-approved the same way initial trust
     *  would. */
    data class Changed(val previousFingerprint: String, val newFingerprint: String) : TrustDecision()
}

object CertTrustPolicy {
    fun evaluate(pinned: String?, presented: String): TrustDecision = when {
        pinned == null -> TrustDecision.FirstTrust(presented)
        pinned == presented -> TrustDecision.Matched
        else -> TrustDecision.Changed(previousFingerprint = pinned, newFingerprint = presented)
    }
}
