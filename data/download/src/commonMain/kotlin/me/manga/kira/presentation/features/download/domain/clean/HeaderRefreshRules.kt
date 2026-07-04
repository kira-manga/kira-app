package me.manga.kira.presentation.features.download.domain.clean

/**
 * Pure rules for the B3 stale-auth fix in the iOS background engine
 * (`BackgroundUrlSessionDownloadRepository`). No I/O — fully unit-tested.
 *
 * The bug: per-page headers were frozen into `ManifestPage.headers` at resolve time and replayed
 * verbatim on reconcile/retry/resume, so an expired `cf_clearance`/`Cookie` baked in at resolve
 * time kept 403ing even after a WebView re-solve refreshed the live store. Two rules fix and
 * route it:
 *  - [overlayFreshHeaders]: fresh live-store headers win over the frozen manifest base at enqueue
 *    time, so a re-solved cookie is honored on the next request.
 *  - [isCloudflareChallengeFailure]: classifies a resolve failure as a WebView-solvable
 *    Cloudflare/anti-bot challenge, so the Details VM can auto-route to the solver instead of
 *    surfacing a raw error (downloads parity with the reading path).
 */
object HeaderRefreshRules {

    /**
     * Overlays FRESH per-source site headers (cf_clearance/Cookie/User-Agent from the live store —
     * the store a WebView re-solve writes) onto a manifest page's FROZEN headers: on a key collision
     * the fresh value wins; frozen-only static keys (e.g. Referer) survive. Empty [fresh] returns
     * [frozen] unchanged (identity — no per-page allocation when nothing is live). Keys are
     * exact-match (both sides are written by the same stores, so casing is consistent in practice).
     */
    fun overlayFreshHeaders(frozen: Map<String, String>, fresh: Map<String, String>): Map<String, String> =
        if (fresh.isEmpty()) frozen else frozen + fresh

    /**
     * HTTP statuses a WebView-solvable Cloudflare/anti-bot interstitial uses (matches the Details
     * VM's CHALLENGE_STATUSES).
     */
    val CHALLENGE_STATUS_CODES: Set<Int> = setOf(403, 429, 503, 520, 521, 522, 523, 524)

    /**
     * Classify a resolve **or transfer** failure as a WebView-solvable Cloudflare/anti-bot
     * challenge. The download path bypasses the `:data` `AppError` classifier, so sniff the failure
     * message; the shapes seen in the wild:
     *  - generic source client — "…Http(statusCode=403…)",
     *  - legacy scraper — the `State.fromCode(403)` string ("Forbidden Click On Help…") or a
     *    challenge-bodied throw,
     *  - the transports — bare "HTTP 403" (`IosBackgroundTransport.handleFinishedDownload`) /
     *    "Image download HTTP 403 for …" (`CoroutineDownloadRepositoryImpl.downloadOnePage`) —
     *    matched via [HTTP_STATUS_REGEX] with a digit boundary so "HTTP 4030" can't false-match.
     * A match → the caller stamps the Cloudflare sentinel so the Details VM auto-routes to the
     * solver; a miss → the raw message is kept.
     */
    fun isCloudflareChallengeFailure(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        if (CHALLENGE_STATUS_CODES.any { m.contains("statuscode=$it") }) return true
        if (HTTP_STATUS_REGEX.findAll(m).any { it.groupValues[1].toIntOrNull() in CHALLENGE_STATUS_CODES }) return true
        return m.contains("forbidden") || m.contains("click on help") || m.contains("cloudflare") ||
            m.contains("just a moment") || m.contains("checking your browser") ||
            m.contains("attention required") || m.contains("cf-ray") || m.contains("cf_chl") ||
            m.contains("ddos")
    }

    /** Matches "http 403"-style transport messages; the lookahead keeps 3-digit codes exact. */
    private val HTTP_STATUS_REGEX = Regex("""http\s+(\d{3})(?!\d)""")
}
