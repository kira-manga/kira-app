package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.setHTTPBody
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

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintHistoryLifecycleTest {
    @Test
    fun responseMustMatchTheOriginalAuthenticatedGetAndItsExactPage() {
        val page = "$IOS_HISTORY_FIRST_PAGE&cursor=v1.a.b"
        listOf(
            IOS_HISTORY_FIRST_PAGE,
            "$IOS_HISTORY_FIRST_PAGE&cursor=v1.a.c",
            "$IOS_HISTORY_TEST_URL?limit=49&cursor=v1.a.b",
            page.replace("example.invalid", "elsewhere.invalid"),
            page.replace("example.invalid", "example.invalid:9443"),
        ).forEach { responseUrl ->
            withIosHistoryGuard { fixture ->
                val task = fixture.task(iosHistoryTestRequest(page))
                assertFalse(fixture.admitHistory(task, url = responseUrl))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
            }
        }
        assertOriginalRequestRechecked()
    }

    @Test
    fun delivered503And421AreRefusedWithoutClaimingAbsenceOfHiddenFoundationReplays() {
        listOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST).forEach { status ->
            withIosHistoryGuard { fixture ->
                // Tasks are not resumed; this checks delivered callbacks, not Foundation's network retries.
                val task = fixture.task(iosHistoryTestRequest())
                assertFalse(fixture.admitHistory(task, mapOf("Retry-After" to "0"), status = status))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                val next = fixture.task(iosHistoryTestRequest())
                assertTrue(fixture.admitHistory(next))
                fixture.receive(next, 1)
                fixture.complete(next)
                assertNull(fixture.callbacks.errors.last())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun concurrentResponseCancellationAndCloseCannotResurrectTheLargeResponseSlot() =
        withIosHistoryGuard { fixture ->
            val first = fixture.task(iosHistoryTestRequest())
            assertTrue(fixture.admitHistory(first))
            assertFalse(fixture.admitHistory(fixture.task(iosHistoryTestRequest())))
            assertEquals(1, fixture.guard.activeTaskCount)
            fixture.receive(first, LARGE_PREFIX_BYTES)
            val cancelled = NSError(NSURLErrorDomain, NSURLErrorCancelled, null)
            fixture.complete(first, cancelled)
            assertSame(cancelled, fixture.callbacks.errors.last())
            assertEquals(0, fixture.guard.activeTaskCount)
            fixture.receive(first, 1)
            val next = fixture.task(iosHistoryTestRequest())
            assertTrue(fixture.admitHistory(next))
            fixture.receive(next, LARGE_PREFIX_BYTES)
            fixture.guard.close(fixture.session)
            fixture.guard.close(fixture.session)
            fixture.receive(next, 1)
            fixture.complete(next)
            assertFalse(fixture.admitHistory(next))
            assertFails { fixture.guard.prepare(iosHistoryTestRequest()) }
            assertEquals((LARGE_PREFIX_BYTES * 2).toULong(), fixture.callbacks.forwardedBytes)
            assertEquals(3, fixture.callbacks.errors.size)
            assertNotNull(fixture.callbacks.errors.last())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    private fun assertOriginalRequestRechecked() {
        listOf("method", "authorization", "body").forEach { variant ->
            withIosHistoryGuard { fixture ->
                val request = iosHistoryTestRequest()
                when (variant) {
                    "method" -> request.setHTTPMethod("POST")
                    "authorization" -> request.setValue(null, "Authorization")
                    "body" -> request.setHTTPBody(iosSessionTestData(1))
                }
                val task = fixture.task(request)
                assertFalse(fixture.admitHistory(task))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val MISDIRECTED_REQUEST = 421
        const val LARGE_PREFIX_BYTES = 32 * 1_024
    }
}
