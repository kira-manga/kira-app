package me.manga.kira.data.remote.complaint

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AndroidComplaintEnrollmentRetryTest {
    @Test
    fun enrollmentPostsDoNotReplayOrFollowRedirects() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                ENROLLMENT_TERMINAL_STATUSES.forEachIndexed { index, status ->
                    fixture.enqueueTerminalAndNext(status)
                    assertEquals(status, fixture.enroll().status)
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("next", fixture.enroll().body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                }
            }
        }

    @Test
    fun bootstrap503And421FailOneAttemptWithoutConsumingTheNextExplicitAcquisition() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                listOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST).forEachIndexed { index, status ->
                    fixture.enqueueTerminalAndNext(status)
                    assertFails { fixture.bootstrap() }
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("next", fixture.bootstrap().body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                }
            }
        }

    @Test
    fun bootstrapDoesNotFollowRedirectOrAuthenticationOr408Responses() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                BOOTSTRAP_TERMINAL_STATUSES.forEachIndexed { index, status ->
                    fixture.enqueueTerminalAndNext(status)
                    assertEquals(status, fixture.bootstrap().status)
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("next", fixture.bootstrap().body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                }
            }
        }

    private fun AndroidSessionEngineFixture.enqueueTerminalAndNext(status: Int) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(status)
                .addHeader("Retry-After", "0")
                .addHeader("WWW-Authenticate", "Basic realm=\"test\"")
                .addHeader("Location", server.url("/forbidden"))
                .body("terminal")
                .build(),
        )
        server.enqueue(MockResponse(body = "next"))
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val REQUEST_TIMEOUT = 408
        const val MISDIRECTED_REQUEST = 421
        const val UNAUTHORIZED = 401
        const val SEE_OTHER = 303
        const val TEMPORARY_REDIRECT = 307
        const val PERMANENT_REDIRECT = 308
        val BOOTSTRAP_TERMINAL_STATUSES =
            listOf(REQUEST_TIMEOUT, UNAUTHORIZED, SEE_OTHER, TEMPORARY_REDIRECT, PERMANENT_REDIRECT)
        val ENROLLMENT_TERMINAL_STATUSES =
            BOOTSTRAP_TERMINAL_STATUSES + listOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST)
    }
}
