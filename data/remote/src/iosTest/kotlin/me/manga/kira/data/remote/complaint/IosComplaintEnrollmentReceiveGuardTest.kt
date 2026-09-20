package me.manga.kira.data.remote.complaint

import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintEnrollmentReceiveGuardTest {
    @Test
    fun bothRoutesStopBeforeOverflowAndCannotBecomeTruncatedSuccess() {
        listOf(true, false).forEach { bootstrap ->
            withIosEnrollmentGuard { fixture ->
                val url = if (bootstrap) IOS_BOOTSTRAP_TEST_URL else IOS_ENROLLMENT_TEST_URL
                val task = fixture.task(iosEnrollmentTestRequest(bootstrap))
                assertTrue(fixture.admit(task, url = url))
                repeat(ComplaintSessionReceiveBudget.MAX_BYTES / CALLBACK_BYTES) {
                    fixture.receive(task, CALLBACK_BYTES)
                }
                fixture.receive(task, 1)
                fixture.complete(task)
                assertFalse(fixture.admit(task, url = url))
                fixture.receive(task, 1)
                assertEquals(ComplaintSessionReceiveBudget.MAX_BYTES.toULong(), fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun bothRoutesRejectInvalidNativeHeadersAndIncompleteDeclaredLength() {
        val invalid = invalidNativeResponseHeaders()
        listOf(true, false).forEach { bootstrap ->
            val url = if (bootstrap) IOS_BOOTSTRAP_TEST_URL else IOS_ENROLLMENT_TEST_URL
            invalid.forEach { headers ->
                withIosEnrollmentGuard { fixture ->
                    val task = fixture.task(iosEnrollmentTestRequest(bootstrap))
                    assertFalse(fixture.admit(task, headers, url))
                    fixture.receive(task, 1)
                    fixture.complete(task)
                    assertEquals(0uL, fixture.callbacks.forwardedBytes)
                    assertNotNull(fixture.callbacks.errors.single())
                    assertEquals(0, fixture.guard.activeTaskCount)
                }
            }
            withIosEnrollmentGuard { fixture ->
                val task = fixture.task(iosEnrollmentTestRequest(bootstrap))
                assertTrue(fixture.admit(task, mapOf("Content-Length" to "2"), url))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun bootstrapThenEnrollmentReuseOnlyTheCompletedResponseSlot() =
        withIosEnrollmentGuard { fixture ->
            val bootstrap = fixture.task(iosEnrollmentTestRequest(bootstrap = true))
            val length = mapOf("Content-Encoding" to "identity", "Content-Length" to "1")
            assertTrue(fixture.admit(bootstrap, length, IOS_BOOTSTRAP_TEST_URL))
            val concurrent = fixture.task(iosEnrollmentTestRequest())
            assertFalse(fixture.admit(concurrent, url = IOS_ENROLLMENT_TEST_URL))
            assertEquals(1, fixture.guard.activeTaskCount)
            fixture.receive(bootstrap, 1)
            fixture.complete(bootstrap)
            assertEquals(0, fixture.guard.activeTaskCount)
            val enrollment = fixture.task(iosEnrollmentTestRequest())
            assertTrue(fixture.admit(enrollment, length, IOS_ENROLLMENT_TEST_URL, HttpStatusCode.Created.value))
            fixture.receive(enrollment, 1)
            fixture.complete(enrollment)
            assertEquals(2uL, fixture.callbacks.forwardedBytes)
            assertEquals(3, fixture.callbacks.errors.size)
            assertNotNull(fixture.callbacks.errors.first())
            assertEquals(listOf(null, null), fixture.callbacks.errors.drop(1))
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun responseMustMatchOriginalNativeRequestMethodAndRoute() {
        listOf(
            Triple("GET", IOS_BOOTSTRAP_TEST_URL, IOS_ENROLLMENT_TEST_URL),
            Triple("POST", IOS_ENROLLMENT_TEST_URL, IOS_BOOTSTRAP_TEST_URL),
            Triple("GET", IOS_ENROLLMENT_TEST_URL, IOS_ENROLLMENT_TEST_URL),
            Triple("POST", IOS_BOOTSTRAP_TEST_URL, IOS_BOOTSTRAP_TEST_URL),
            Triple("PUT", IOS_ENROLLMENT_TEST_URL, IOS_ENROLLMENT_TEST_URL),
            Triple("POST", IOS_ENROLLMENT_TEST_URL, IOS_SESSION_TEST_URL),
            Triple("POST", "https://elsewhere.invalid/api/v1/installations", IOS_ENROLLMENT_TEST_URL),
            Triple("GET", IOS_BOOTSTRAP_TEST_URL, "https://elsewhere.invalid/api/v1/installations/bootstrap"),
        ).forEach { (method, originalUrl, responseUrl) ->
            withIosEnrollmentGuard { fixture ->
                val request =
                    iosSessionTestRequest(originalUrl).apply {
                        setHTTPMethod(method)
                        if (method == "GET") setHTTPBody(null)
                    }
                val task = fixture.task(request)
                assertFalse(fixture.admit(task, url = responseUrl))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun deliveredBootstrapFollowUpStatusesFailButLaterExplicitTasksAreAllowed() {
        listOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST).forEach { status ->
            withIosEnrollmentGuard { fixture ->
                val bootstrap = fixture.task(iosEnrollmentTestRequest(bootstrap = true))
                // Tasks are not resumed: refusal here does not measure hidden Foundation replay.
                assertFalse(fixture.admit(bootstrap, mapOf("Retry-After" to "0"), IOS_BOOTSTRAP_TEST_URL, status))
                fixture.receive(bootstrap, 1)
                fixture.complete(bootstrap)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                val explicitRetry = fixture.task(iosEnrollmentTestRequest(bootstrap = true))
                assertTrue(fixture.admit(explicitRetry, url = IOS_BOOTSTRAP_TEST_URL))
                fixture.receive(explicitRetry, 1)
                fixture.complete(explicitRetry)
                val enrollment = fixture.task(iosEnrollmentTestRequest())
                assertTrue(fixture.admit(enrollment, url = IOS_ENROLLMENT_TEST_URL, status = status))
                fixture.receive(enrollment, 1)
                fixture.complete(enrollment)
                assertEquals(2uL, fixture.callbacks.forwardedBytes)
                assertEquals(listOf(null, null), fixture.callbacks.errors.drop(1))
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun cancellationAndCloseClearBothRoutesAndRejectLateCallbacks() {
        listOf(true, false).forEach { bootstrap ->
            withIosEnrollmentGuard { fixture ->
                val url = if (bootstrap) IOS_BOOTSTRAP_TEST_URL else IOS_ENROLLMENT_TEST_URL
                val task = fixture.task(iosEnrollmentTestRequest(bootstrap))
                val cancelled = NSError(NSURLErrorDomain, NSURLErrorCancelled, null)
                assertTrue(fixture.admit(task, url = url))
                fixture.complete(task, cancelled)
                assertSame(cancelled, fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
                val next = fixture.task(iosEnrollmentTestRequest(bootstrap))
                assertTrue(fixture.admit(next, url = url))
                fixture.receive(next, 1)
                fixture.guard.close(fixture.session)
                fixture.guard.close(fixture.session)
                fixture.receive(next, 1)
                fixture.complete(next)
                assertFalse(fixture.admit(next, url = url))
                assertFails { fixture.guard.prepare(iosEnrollmentTestRequest(bootstrap)) }
                assertEquals(1uL, fixture.callbacks.forwardedBytes)
                assertEquals(2, fixture.callbacks.errors.size)
                assertNotNull(fixture.callbacks.errors.last())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    private fun invalidNativeResponseHeaders(): List<Map<String, String>> =
        listOf(
            mapOf("Content-Encoding" to "gzip"),
            mapOf("Content-Length" to (ComplaintSessionReceiveBudget.MAX_BYTES + 1).toString()),
            mapOf("Content-Length" to "1, 1"),
            mapOf("Content-Length" to "1", "Transfer-Encoding" to "chunked"),
        )

    private companion object {
        const val CALLBACK_BYTES = 1_024
        const val SERVICE_UNAVAILABLE = 503
        const val MISDIRECTED_REQUEST = 421
    }
}
