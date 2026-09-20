package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.HTTPShouldHandleCookies
import platform.Foundation.NSError
import platform.Foundation.NSInputStream
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPBodyStream
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real Foundation tasks with driven callbacks only: no task is resumed or live Darwin queue measured. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintHistoryDetailTest {
    @Test
    fun sameHistoryGuardPreparesQuerylessCanonicalDetailAndNoticeButNoAliases() =
        withIosHistoryGuard { fixture ->
            val request = iosHistoryTestRequest(DETAIL_URL)
            fixture.guard.prepare(request)
            assertFalse(request.HTTPShouldHandleCookies)
            assertEquals(NSURLRequestReloadIgnoringLocalCacheData, request.cachePolicy)
            fixture.guard.prepare(iosHistoryTestRequest("$IOS_HISTORY_TEST_URL/$NOTICE_ID"))
            listOf(
                "$DETAIL_URL?",
                "$DETAIL_URL?limit=50",
                "$DETAIL_URL/",
                "$DETAIL_URL/replies",
                "$IOS_HISTORY_TEST_URL/${DETAIL_ID.uppercase()}",
                DETAIL_URL.replace("example.invalid", "elsewhere.invalid"),
                DETAIL_URL.replace("example.invalid", "example.invalid:9443"),
            ).forEach { assertFails { fixture.guard.prepare(iosHistoryTestRequest(it)) } }
        }

    @Test
    fun detailRejectsConditionalMutationHeadersAndEveryBodyRepresentationBeforeAdmission() =
        withIosHistoryGuard { fixture ->
            listOf(
                "If-Match",
                "If-None-Match",
                "If-Modified-Since",
                "If-Unmodified-Since",
                "If-Range",
                "X-Kira-Idempotency-Key",
                "Cookie",
                "Proxy-Authorization",
                "Content-Type",
                "Content-Length",
            ).forEach { name ->
                assertFails {
                    fixture.guard.prepare(iosHistoryTestRequest(DETAIL_URL).apply { setValue("synthetic", name) })
                }
            }
            listOf(0, 1).forEach { size ->
                assertFails {
                    fixture.guard.prepare(
                        iosHistoryTestRequest(DETAIL_URL).apply { setHTTPBody(iosSessionTestData(size)) },
                    )
                }
            }
            assertFails {
                fixture.guard.prepare(
                    iosHistoryTestRequest(DETAIL_URL).apply {
                        setHTTPBodyStream(NSInputStream(data = iosSessionTestData(1)))
                    },
                )
            }
            assertFails { fixture.guard.prepare(iosHistoryTestRequest(DETAIL_URL).apply { setHTTPMethod("POST") }) }
        }

    @Test
    fun responseIdCannotSwitchToAnotherDetailOrListAndNoPrefixOrLateCallbackEscapes() {
        for (url in listOf("$IOS_HISTORY_TEST_URL/$NOTICE_ID", IOS_HISTORY_FIRST_PAGE, "$DETAIL_URL?limit=50")) {
            withIosHistoryGuard { fixture ->
                val task = fixture.task(iosHistoryTestRequest(DETAIL_URL))
                assertFalse(fixture.admitHistory(task, JSON_HEADERS, url = url))
                fixture.receive(task, 1)
                fixture.complete(task)
                fixture.receive(task, 1)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun exactDetailJsonCompletesAtThirtyTwoKiBBeforeAOneByteOverrunIsRefused() {
        for (overflow in listOf(false, true)) {
            withIosHistoryGuard { fixture ->
                val task = fixture.task(iosHistoryTestRequest(DETAIL_URL))
                assertTrue(fixture.admitHistory(task, JSON_HEADERS, url = DETAIL_URL))
                fixture.receive(task, 32 * 1_024)
                if (overflow) fixture.receive(task, 1)
                fixture.complete(task)
                fixture.receive(task, 1)
                assertEquals((32 * 1_024).toULong(), fixture.callbacks.forwardedBytes)
                if (overflow) {
                    assertNotNull(fixture.callbacks.errors.single())
                } else {
                    assertNull(fixture.callbacks.errors.single())
                }
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun problemOrUnrecognizedSuccessMediaKeepsSixteenKiBAndCannotBorrowListCapacity() {
        val cases = listOf(404 to "application/problem+json", 401 to "application/problem+json", 200 to "text/html")
        for ((status, media) in cases) {
            withIosHistoryGuard { fixture ->
                val task = fixture.task(iosHistoryTestRequest(DETAIL_URL))
                assertTrue(fixture.admitHistory(task, mapOf("Content-Type" to media), DETAIL_URL, status))
                fixture.receive(task, 16 * 1_024)
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals((16 * 1_024).toULong(), fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun declaredDetailOverflowAndIncompleteEofFailWithoutTruncatedSuccess() {
        for (length in listOf(32 * 1_024, 32 * 1_024 + 1)) {
            withIosHistoryGuard { fixture ->
                val task = fixture.task(iosHistoryTestRequest(DETAIL_URL))
                val accepted = fixture.admitHistory(task, JSON_HEADERS + ("Content-Length" to "$length"), DETAIL_URL)
                assertEquals(length == 32 * 1_024, accepted)
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(if (accepted) 1uL else 0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun listAndDetailShareOneNativeSlotAndClosedOwnerRejectsLateCallbacksAndNewRequests() =
        withIosHistoryGuard { fixture ->
            val detail = fixture.task(iosHistoryTestRequest(DETAIL_URL))
            assertTrue(fixture.admitHistory(detail, JSON_HEADERS, DETAIL_URL))
            assertFalse(fixture.admitHistory(fixture.task(iosHistoryTestRequest())))
            val cancelled = NSError(NSURLErrorDomain, NSURLErrorCancelled, null)
            fixture.complete(detail, cancelled)
            assertSame(cancelled, fixture.callbacks.errors.last())
            fixture.receive(detail, 1)
            val list = fixture.task(iosHistoryTestRequest())
            assertTrue(fixture.admitHistory(list))
            fixture.receive(list, 32 * 1_024 + 1)
            fixture.guard.close(fixture.session)
            fixture.receive(list, 1)
            fixture.complete(list)
            assertEquals((32 * 1_024 + 1).toULong(), fixture.callbacks.forwardedBytes)
            assertFails { fixture.guard.prepare(iosHistoryTestRequest(DETAIL_URL)) }
            assertNotNull(fixture.callbacks.errors.last())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun delivered503And421RemainRefusedForDetailWithoutHiddenReplayClaims() {
        for (status in listOf(503, 421)) {
            withIosHistoryGuard { fixture ->
                val task = fixture.task(iosHistoryTestRequest(DETAIL_URL))
                assertFalse(fixture.admitHistory(task, JSON_HEADERS + ("Retry-After" to "0"), DETAIL_URL, status))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
            }
        }
    }

    private companion object {
        const val DETAIL_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val NOTICE_ID = "bbbbbbbb-bbbb-1bbb-8bbb-bbbbbbbbbbbb"
        const val DETAIL_URL = "$IOS_HISTORY_TEST_URL/$DETAIL_ID"
        val JSON_HEADERS = mapOf("Content-Type" to "application/json")
    }
}
