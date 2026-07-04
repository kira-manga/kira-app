package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure-logic regression tests for [HeaderRefreshRules] (B3, stale cf_clearance/Cookie replay).
 * Locks the fresh-over-frozen overlay semantics used at reconcile/retry enqueue time and the
 * Cloudflare-challenge message classification that routes a resolve failure to the WebView solver.
 */
class HeaderRefreshRulesTest {

    // ---- (i) overlayFreshHeaders ----

    @Test
    fun freshHeaders_replaceStaleFrozenValues() {
        // B3 core: the cookie a WebView re-solve just wrote must win over the one frozen into the
        // manifest at resolve time — otherwise the expired value keeps 403ing.
        val frozen = mapOf(
            "Cookie" to "cf_clearance=EXPIRED",
            "User-Agent" to "UA-old",
            "Referer" to "https://source.example/",
        )
        val fresh = mapOf(
            "Cookie" to "cf_clearance=FRESH",
            "User-Agent" to "UA-new",
        )
        assertEquals(
            mapOf(
                "Cookie" to "cf_clearance=FRESH",
                "User-Agent" to "UA-new",
                "Referer" to "https://source.example/", // frozen-only static key survives
            ),
            HeaderRefreshRules.overlayFreshHeaders(frozen, fresh),
        )
    }

    @Test
    fun emptyFreshStore_returnsFrozenUnchanged() {
        // Identity, not just equality: with nothing live there must be no per-page re-allocation
        // and the frozen base is used as-is.
        val frozen = mapOf("Cookie" to "c", "Referer" to "r")
        assertSame(frozen, HeaderRefreshRules.overlayFreshHeaders(frozen, emptyMap()))
    }

    @Test
    fun freshOnlyKeys_areAdded() {
        // A source whose resolve-time headers had no cookie yet (solved AFTER enqueue) still gets
        // the fresh one on retry.
        assertEquals(
            mapOf("Referer" to "r", "Cookie" to "cf_clearance=NEW"),
            HeaderRefreshRules.overlayFreshHeaders(
                frozen = mapOf("Referer" to "r"),
                fresh = mapOf("Cookie" to "cf_clearance=NEW"),
            ),
        )
    }

    @Test
    fun overlay_isExactKeyMatch() {
        // Documented semantics: keys are exact-match (both sides come from the same stores, so
        // casing is consistent in practice) — a differently-cased key is NOT treated as a collision.
        assertEquals(
            mapOf("cookie" to "old", "Cookie" to "new"),
            HeaderRefreshRules.overlayFreshHeaders(
                frozen = mapOf("cookie" to "old"),
                fresh = mapOf("Cookie" to "new"),
            ),
        )
    }

    // ---- (ii) isCloudflareChallengeFailure ----

    @Test
    fun genericClientStatusCodes_areChallenges() {
        // The generic source client surfaces "…Http(statusCode=403…)" — every WebView-solvable
        // interstitial status must classify as a challenge.
        for (code in HeaderRefreshRules.CHALLENGE_STATUS_CODES) {
            assertTrue(
                HeaderRefreshRules.isCloudflareChallengeFailure("Http(statusCode=$code, message=blocked)"),
                "statusCode=$code",
            )
        }
        assertEquals(setOf(403, 429, 503, 520, 521, 522, 523, 524), HeaderRefreshRules.CHALLENGE_STATUS_CODES)
    }

    @Test
    fun legacyScraperAndChallengeBodyMarkers_areChallenges() {
        // Legacy scraper's State.fromCode(403) string + the interstitial-body markers, case-insensitive.
        val challengeMessages = listOf(
            "Forbidden Click On Help To Fix It",
            "CLOUDFLARE blocked the request",
            "Just a moment...",
            "Checking your browser before accessing",
            "Attention Required! | Cloudflare",
            "cf-ray: 8c1de2",
            "cf_chl_opt token missing",
            "DDoS protection by …",
        )
        for (msg in challengeMessages) {
            assertTrue(HeaderRefreshRules.isCloudflareChallengeFailure(msg), msg)
        }
    }

    @Test
    fun transportHttpStatusMessages_areChallenges() {
        // The transports report a bare status: IosBackgroundTransport → "HTTP 403";
        // CoroutineDownloadRepositoryImpl → "Image download HTTP 403 for <url>". These are the
        // transfer-stage challenge shape (expired cf_clearance 403ing the image CDN) and must
        // classify so the exhausted-retry failure stamps the solver sentinel.
        for (code in HeaderRefreshRules.CHALLENGE_STATUS_CODES) {
            assertTrue(HeaderRefreshRules.isCloudflareChallengeFailure("HTTP $code"), "HTTP $code")
        }
        assertTrue(
            HeaderRefreshRules.isCloudflareChallengeFailure("Image download HTTP 403 for https://cdn.example/p0.webp"),
        )
    }

    @Test
    fun ordinaryFailures_areNotChallenges() {
        // A miss keeps the raw message — never mis-route a plain network error to the solver.
        val ordinary = listOf(
            null,
            "Connection reset by peer",
            "timeout while reading response",
            "Http(statusCode=404, message=not found)",
            "Http(statusCode=500, message=server error)",
            "No images for chapter",
            "HTTP 404",
            "HTTP 500",
            "http 4030 weird proxy code", // digit boundary: must not parse as 403
            "move failed -> /x/image_5.webp",
        )
        for (msg in ordinary) {
            assertFalse(HeaderRefreshRules.isCloudflareChallengeFailure(msg), msg ?: "null")
        }
    }
}
