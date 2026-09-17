package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComplaintHistoryTargetTest {
    @Test
    fun targetIsQuerylessHttpsAndOnlyTheFixedHistoryRoute() {
        listOf(
            "http://example.invalid/api/v1/complaints",
            "https://user:synthetic@example.invalid/api/v1/complaints",
            "$HISTORY_URL?limit=50",
            "$HISTORY_URL?",
            "$HISTORY_URL#fragment",
            "$HISTORY_URL/",
            "$HISTORY_URL/11111111-1111-4111-8111-111111111111",
            "https://example.invalid/api/v1/installations/session",
            "https://example.invalid/bad/../api/v1/complaints",
            "https://example.invalid/%2e/api/v1/complaints",
            "https://example.invalid//api/v1/complaints",
        ).forEach { assertNull(ComplaintHistoryTarget.checked(Url(it)), it) }
        assertNotNull(ComplaintHistoryTarget.checked(Url(HISTORY_URL)))
    }

    @Test
    fun nativeAdmissionPreservesExactOriginPortAndBase() {
        val base = "https://example.invalid:9443/base_1/v2/api/v1/complaints"
        val target = assertNotNull(ComplaintHistoryTarget.checked(Url(base)))
        assertTrue(target.matches("$base?limit=50"))
        listOf(
            "$HISTORY_URL?limit=50",
            base.replace(":9443", "") + "?limit=50",
            base.replace("example.invalid", "elsewhere.invalid") + "?limit=50",
            base.replace("https://", "http://") + "?limit=50",
            "$base/other?limit=50",
            "$base?limit=50#fragment",
        ).forEach { assertFalse(target.matches(it), it) }
        assertEquals("ComplaintHistoryTarget(redacted)", target.toString())
    }

    @Test
    fun queryHasOneCanonicalLimitAndAnOptionalOpaqueCursor() {
        val target = assertNotNull(ComplaintHistoryTarget.checked(Url(HISTORY_URL)))
        listOf("limit=1", "limit=50", "limit=50&cursor=v1.a.b", "cursor=v1.a_B-0.c_D-1&limit=50")
            .forEach { assertTrue(target.matches("$HISTORY_URL?$it"), it) }
        assertFalse(target.matches(HISTORY_URL))
        invalidQueries().forEach { assertFalse(target.matches("$HISTORY_URL?$it"), it) }
    }

    @Test
    fun cursorBoundDoesNotAccidentallyBecomeAWholeUrlBound() {
        val target = assertNotNull(ComplaintHistoryTarget.checked(Url(HISTORY_URL)))
        val exact = "v1." + "a".repeat(ComplaintHistoryQuery.MAX_CURSOR_CHARACTERS - "v1..b".length) + ".b"
        assertEquals(ComplaintHistoryQuery.MAX_CURSOR_CHARACTERS, exact.length)
        assertTrue(target.matches("$HISTORY_URL?limit=50&cursor=$exact"))
        assertFalse(target.matches("$HISTORY_URL?limit=50&cursor=${exact}b"))
        val longBase = "https://example.invalid/" + "a".repeat(BASE_SEGMENT_CHARACTERS) + "/api/v1/complaints"
        val longTarget = assertNotNull(ComplaintHistoryTarget.checked(Url(longBase)))
        assertTrue(longTarget.matches("$longBase?limit=50&cursor=$exact"))
    }

    @Test
    fun responseCannotSwitchPageWithinTheAllowedHistoryRoute() {
        val target = assertNotNull(ComplaintHistoryTarget.checked(Url(HISTORY_URL)))
        val request = "$HISTORY_URL?limit=50&cursor=v1.a.b"
        assertTrue(target.samePage(request, "$HISTORY_URL?cursor=v1.a.b&limit=50"))
        assertFalse(target.samePage(request, "$HISTORY_URL?limit=50&cursor=v1.a.c"))
        assertFalse(target.samePage(request, "$HISTORY_URL?limit=49&cursor=v1.a.b"))
        assertFalse(target.samePage(request, "$HISTORY_URL?limit=50"))
        assertFalse(target.samePage(HISTORY_URL, HISTORY_URL))
    }

    private fun invalidQueries(): List<String> =
        listOf(
            "", "cursor=v1.a.b", "limit=", "limit=0", "limit=51", "limit=01", "limit=+1", "limit=1.0",
            "limit=50&cursor=", "limit=50&limit=50", "cursor=v1.a.b&cursor=v1.a.b", "limit=50&extra=1",
            "limit=50&cursor=v1.a.b&extra=1", "limit=50&", "&limit=50", "limit=50&&cursor=v1.a.b",
            "%6cimit=50", "limit=%35%30", "limit=50&cursor=v1.%61.b", "limit=50&cursor=v1.a.b=",
            "limit=50&cursor=v2.a.b", "limit=50&cursor=v1.a.", "limit=50&cursor=v1..b",
            "limit=50&cursor=v1.a.b.c", "limit=50&cursor=v1.a+b.c", "limit=50&cursor=v1.a/b.c",
            "limit=50&cursor=v1.a.b\n", "limit=50&cursor=v1.a.é", "limit=50;cursor=v1.a.b",
        )

    private companion object {
        const val HISTORY_URL = "https://example.invalid/api/v1/complaints"
        const val BASE_SEGMENT_CHARACTERS = 1_900
    }
}
