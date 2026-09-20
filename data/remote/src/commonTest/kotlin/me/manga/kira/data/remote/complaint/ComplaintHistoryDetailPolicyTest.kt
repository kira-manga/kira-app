package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComplaintHistoryDetailPolicyTest {
    @Test
    fun sameConfiguredHistoryOwnerAdmitsListAndCanonicalQuerylessContentOrNoticeId() {
        val target = assertNotNull(ComplaintHistoryTarget.checked(Url(BASE)))
        assertEquals(ComplaintHistoryRoute.LIST, target.route("$BASE?limit=50"))
        assertEquals(ComplaintHistoryRoute.DETAIL, target.route("$BASE/$CONTENT_ID"))
        assertEquals(ComplaintHistoryRoute.DETAIL, target.route("$BASE/$NOTICE_ID"))
        assertNull(ComplaintHistoryTarget.checked(Url("$BASE/$CONTENT_ID")))
        assertFalse(target.matches(BASE))
    }

    @Test
    fun detailRejectsEveryQueryPathAliasOriginPortAndPrefixChange() {
        val target = assertNotNull(ComplaintHistoryTarget.checked(Url(BASE)))
        listOf(
            "$BASE/$CONTENT_ID?",
            "$BASE/$CONTENT_ID?limit=50",
            "$BASE/$CONTENT_ID?cursor=v1.a.b",
            "$BASE/$CONTENT_ID/",
            "$BASE/$CONTENT_ID/replies",
            "$BASE/${CONTENT_ID.uppercase()}",
            "$BASE/%61${CONTENT_ID.drop(1)}",
            "$BASE/../$CONTENT_ID",
            "$BASE//$CONTENT_ID",
            "$BASE/$CONTENT_ID#fragment",
            "$BASE/$CONTENT_ID".replace("https://", "http://"),
            "$BASE/$CONTENT_ID".replace(":9443", ""),
            "$BASE/$CONTENT_ID".replace("/base_1/v2", ""),
            "$BASE/$CONTENT_ID".replace("example.invalid", "elsewhere.invalid"),
        ).forEach { assertFalse(target.matches(it), it) }
    }

    @Test
    fun responseMustBindTheExactIdNotMerelyTheDetailRouteOrAnotherListPage() {
        val target = assertNotNull(ComplaintHistoryTarget.checked(Url(BASE)))
        val request = "$BASE/$CONTENT_ID"
        assertTrue(target.samePage(request, request))
        assertFalse(target.samePage(request, "$BASE/$NOTICE_ID"))
        assertFalse(target.samePage(request, "$BASE?limit=50"))
        assertFalse(target.samePage("$BASE?limit=50", request))
        assertFalse(target.samePage(request, "$request?"))
        assertTrue(target.samePage("$BASE?limit=50&cursor=v1.a.b", "$BASE?cursor=v1.a.b&limit=50"))
    }

    @Test
    fun onlyDetail200WithSingleJsonMediaGetsThirtyTwoKiBAndListKeepsTwoMiB() {
        val detail = assertNotNull(detailBudget())
        assertEquals(32 * 1_024, detail.remainingBytes)
        assertTrue(detail.accept((32 * 1_024).toULong()))
        assertFalse(detail.accept(1uL))
        assertEquals(32 * 1_024, detail.receivedBytes)
        val list = assertNotNull(ComplaintHistoryReceiveBudget.checked(200, emptyList(), emptyList(), emptyList()))
        assertEquals(2 * 1_024 * 1_024, list.remainingBytes)
        assertTrue(list.accept((32 * 1_024 + 1).toULong()))
        assertNotNull(detailBudget(media = listOf("Application/JSON; charset=\"UTF-8\"")))
    }

    @Test
    fun problemsAndUnrecognizedOrAmbiguousSuccessMediaKeepSixteenKiB() {
        for (status in listOf(201L, 206L, 304L, 401L, 404L, 409L, 429L, 503L)) {
            val budget = assertNotNull(detailBudget(status))
            assertEquals(16 * 1_024, budget.remainingBytes)
            assertFalse(budget.accept((16 * 1_024 + 1).toULong()))
        }
        val invalidMedia =
            listOf(
                emptyList(),
                listOf("application/problem+json"),
                listOf("text/html"),
                listOf("application/json", "application/json"),
                listOf("application/json; q=1"),
            )
        for (media in invalidMedia) {
            val budget = assertNotNull(detailBudget(media = media))
            assertEquals(16 * 1_024, budget.remainingBytes)
        }
    }

    @Test
    fun detailBudgetRefusesDeclaredOverflowFramingAmbiguityAndIncompleteOrUnsignedOverrun() {
        assertNull(detailBudget(length = listOf("32769")))
        assertNull(detailBudget(length = listOf("1", "1")))
        assertNull(detailBudget(length = listOf("1"), transfer = listOf("chunked")))
        assertNull(detailBudget(encoding = listOf("gzip")))
        assertNull(detailBudget(encoding = listOf("identity", "identity")))
        assertNull(detailBudget(transfer = listOf("gzip")))
        val exact = assertNotNull(detailBudget(length = listOf("32768")))
        assertTrue(exact.accept(32767uL))
        assertFalse(exact.isComplete())
        assertFalse(exact.accept(ULong.MAX_VALUE))
        assertEquals(32767, exact.receivedBytes)
        assertTrue(exact.accept(1uL))
        assertTrue(exact.isComplete())
    }

    private fun detailBudget(
        status: Long = 200L,
        media: List<String> = listOf("application/json"),
        encoding: List<String> = emptyList(),
        length: List<String> = emptyList(),
        transfer: List<String> = emptyList(),
    ): ComplaintReceiveBudget? = ComplaintHistoryReceiveBudget.checkedDetail(status, media, encoding, length, transfer)

    private companion object {
        const val BASE = "https://example.invalid:9443/base_1/v2/api/v1/complaints"
        const val CONTENT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val NOTICE_ID = "bbbbbbbb-bbbb-1bbb-8bbb-bbbbbbbbbbbb"
    }
}
