package me.manga.kira.data.remote.complaint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class AndroidComplaintHistoryLifecycleTest {
    @Test
    fun authenticatedGetRefusesNative503And421FollowUpsWithoutLosingTheNextExplicitRequest() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                listOf(SERVICE_UNAVAILABLE, MISDIRECTED_REQUEST).forEachIndexed { index, status ->
                    fixture.enqueueTerminalAndNext(status)
                    assertFails { fixture.get() }
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("next", fixture.get().body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                }
            }
        }

    @Test
    fun authenticatedGetDoesNotFollowRedirectAuthenticationOrTimeoutResponses() =
        runBlocking {
            AndroidHistoryEngineFixture().use { fixture ->
                listOf(UNAUTHORIZED, REQUEST_TIMEOUT, TEMPORARY_REDIRECT).forEachIndexed { index, status ->
                    fixture.enqueueTerminalAndNext(status)
                    assertEquals(status, fixture.get().status)
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("next", fixture.get().body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                }
            }
        }

    @Test
    fun closingHistoryCancelsItsStalledRequestWithoutClosingTheIndependentInstallationOwner() =
        runBlocking {
            AndroidHistoryEngineFixture().use { history ->
                AndroidSessionEngineFixture().use { installation ->
                    assertNotSame(history.resources.pool, installation.resources.pool)
                    assertNotSame(history.resources.dispatcher, installation.resources.dispatcher)
                    history.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
                    val request = async(Dispatchers.Default) { runCatching { history.get() }.isFailure }
                    try {
                        assertNotNull(history.server.takeRequest(SESSION_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                        assertFalse(request.isCompleted)
                        history.owner.close()
                        history.owner.close()
                        assertTrue(withTimeout(SESSION_TEST_TIMEOUT_MS) { request.await() })
                        assertReleased(history)
                        installation.server.enqueue(MockResponse(body = "independent"))
                        assertEquals("independent", installation.exchange().body)
                        assertTrue(installation.owner.engine.coroutineContext.job.isActive)
                    } finally {
                        history.owner.close()
                        request.cancelAndJoin()
                    }
                }
            }
        }

    private fun assertReleased(fixture: AndroidHistoryEngineFixture) {
        assertFalse(fixture.owner.engine.coroutineContext.job.isActive)
        assertTrue(
            fixture.resources.dispatcher.executorService
                .awaitTermination(SESSION_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertEquals(0, fixture.resources.pool.connectionCount())
    }

    private fun AndroidHistoryEngineFixture.enqueueTerminalAndNext(status: Int) {
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
        const val MISDIRECTED_REQUEST = 421
        const val UNAUTHORIZED = 401
        const val REQUEST_TIMEOUT = 408
        const val TEMPORARY_REDIRECT = 307
    }
}
