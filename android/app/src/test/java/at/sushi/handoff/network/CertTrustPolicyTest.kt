package at.sushi.handoff.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CertTrustPolicyTest {

    @Test
    fun noPinnedFingerprint_isFirstTrust() {
        val decision = CertTrustPolicy.evaluate(pinned = null, presented = "AA:BB")
        val firstTrust = assertIs<TrustDecision.FirstTrust>(decision)
        assertEquals("AA:BB", firstTrust.fingerprint)
    }

    @Test
    fun presentedMatchesPinned_isMatched() {
        val decision = CertTrustPolicy.evaluate(pinned = "AA:BB", presented = "AA:BB")
        assertIs<TrustDecision.Matched>(decision)
    }

    @Test
    fun presentedDiffersFromPinned_isChanged() {
        val decision = CertTrustPolicy.evaluate(pinned = "AA:BB", presented = "CC:DD")
        val changed = assertIs<TrustDecision.Changed>(decision)
        assertEquals("AA:BB", changed.previousFingerprint)
        assertEquals("CC:DD", changed.newFingerprint)
    }
}
