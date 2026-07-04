package me.manga.kira.presentation.features.download.domain.clean

/**
 * Pure retry policy for a failed page **transfer** in the iOS background engine
 * (`BackgroundUrlSessionDownloadRepository.handlePageFailedLocked`). No I/O — fully unit-tested.
 *
 * Given the page's durable attempt count (from [DownloadManifestStore.incrementAttempt]) and the
 * transport's failure message, decides between:
 *  - **[Decision.Retry]** — attempts remain: re-enqueue just this page after a bounded exponential
 *    backoff ([backoffMs]: 2s, 4s, 8s, … capped at 30s). The retry itself re-reads FRESH site
 *    headers (B3), so a WebView re-solve that happened meanwhile is honored.
 *  - **[Decision.FailChapter]** — the retry budget is exhausted: the chapter fails, and
 *    [Decision.FailChapter.isChallenge] says whether the terminal failure classifies as a
 *    WebView-solvable Cloudflare/anti-bot challenge ([HeaderRefreshRules.isCloudflareChallengeFailure],
 *    which recognizes the transport's bare "HTTP 403" shape). A challenge → the engine stamps the
 *    Cloudflare sentinel so the Details VM auto-routes to the solver — the transfer-stage analogue
 *    of the resolve-stage stamping. Without it, the most common real-world batch killer (an expired
 *    `cf_clearance` 403ing every page mid-batch) died as a plain FAILED with no solver.
 */
object TransferRetryRules {

    sealed interface Decision {
        /** Attempts remain — re-enqueue this page after [delayMs] (bounded exponential backoff). */
        data class Retry(val delayMs: Long) : Decision

        /** Budget exhausted — fail the chapter; [isChallenge] routes to the Cloudflare sentinel. */
        data class FailChapter(val isChallenge: Boolean) : Decision
    }

    fun decide(attempts: Int, maxAttempts: Int, message: String?): Decision =
        if (attempts >= maxAttempts) {
            Decision.FailChapter(isChallenge = HeaderRefreshRules.isCloudflareChallengeFailure(message))
        } else {
            Decision.Retry(delayMs = backoffMs(attempts))
        }

    /**
     * Exponential backoff for the Nth attempt (1-based): 2s, 4s, 8s, … capped at [maxMs]. The shift
     * is clamped to 16 so a corrupt/huge attempt count can never overflow into a negative delay;
     * attempt values ≤ 1 (including the 0 a missing manifest reports) all get the base delay.
     */
    fun backoffMs(attempt: Int, baseMs: Long = 2_000L, maxMs: Long = 30_000L): Long {
        val shift = (attempt - 1).coerceIn(0, 16)
        return (baseMs shl shift).coerceAtMost(maxMs)
    }
}
