package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic regression tests for [TransferRetryRules] — the iOS background engine's
 * failed-page-transfer policy. Locks the retry-vs-fail boundary at the durable attempt budget, the
 * bounded exponential backoff (incl. the overflow clamp), and the transfer-stage Cloudflare
 * routing: an exhausted challenge-class failure (the transport's bare "HTTP 403") must classify as
 * a challenge so the engine stamps the solver sentinel instead of a dead-end raw error.
 */
class TransferRetryRulesTest {

    @Test
    fun underBudget_retries_withExponentialBackoff() {
        assertEquals(TransferRetryRules.Decision.Retry(2_000L), TransferRetryRules.decide(1, 3, "HTTP 403"))
        assertEquals(TransferRetryRules.Decision.Retry(4_000L), TransferRetryRules.decide(2, 3, "HTTP 403"))
    }

    @Test
    fun atBudget_failsChapter() {
        assertTrue(TransferRetryRules.decide(3, 3, "move failed -> /x") is TransferRetryRules.Decision.FailChapter)
    }

    @Test
    fun missingManifestAttemptZero_stillRetries_withBaseDelay() {
        // incrementAttempt returns 0 when the manifest is gone (deleted mid-flight) — the policy
        // retries with the base delay; the retry path itself then re-reads the manifest and no-ops.
        assertEquals(TransferRetryRules.Decision.Retry(2_000L), TransferRetryRules.decide(0, 3, null))
    }

    @Test
    fun exhaustedChallengeFailure_classifiesAsChallenge() {
        // The transport reports a challenge-class HTTP status as a bare "HTTP 403". Exhausting the
        // budget on it must route to the Cloudflare sentinel (transfer-stage solver parity) — the
        // expired-cf_clearance batch killer previously died as a plain FAILED with no solver.
        val d = TransferRetryRules.decide(3, 3, "HTTP 403")
        assertEquals(TransferRetryRules.Decision.FailChapter(isChallenge = true), d)
    }

    @Test
    fun exhaustedNonChallengeFailure_staysRawFailure() {
        for (msg in listOf("HTTP 404", "move failed -> /x/image_5.webp", "The request timed out.", null)) {
            assertEquals(
                TransferRetryRules.Decision.FailChapter(isChallenge = false),
                TransferRetryRules.decide(3, 3, msg),
                "msg=$msg",
            )
        }
    }

    @Test
    fun backoff_isBoundedAndOverflowSafe() {
        assertEquals(2_000L, TransferRetryRules.backoffMs(0)) // pre-first-attempt / missing manifest
        assertEquals(2_000L, TransferRetryRules.backoffMs(1))
        assertEquals(4_000L, TransferRetryRules.backoffMs(2))
        assertEquals(8_000L, TransferRetryRules.backoffMs(3))
        assertEquals(30_000L, TransferRetryRules.backoffMs(5)) // 32s capped to 30s
        assertEquals(30_000L, TransferRetryRules.backoffMs(1_000)) // clamped shift — never negative
        assertTrue(TransferRetryRules.backoffMs(Int.MAX_VALUE) > 0)
        assertFalse(TransferRetryRules.backoffMs(Int.MIN_VALUE) < 2_000L) // degenerate input → base
    }
}
