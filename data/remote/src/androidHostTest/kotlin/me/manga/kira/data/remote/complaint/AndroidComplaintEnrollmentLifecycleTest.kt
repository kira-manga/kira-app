package me.manga.kira.data.remote.complaint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

class AndroidComplaintEnrollmentLifecycleTest {
    @Test
    fun closingBorrowingClientDoesNotCloseTheEnrollmentOwner() {
        AndroidSessionEngineFixture(enrollment = true).use { fixture ->
            fixture.client.close()
            assertTrue(fixture.owner.engine.coroutineContext.job.isActive)
            fixture.owner.close()
            fixture.owner.close()
            assertFalse(fixture.owner.engine.coroutineContext.job.isActive)
            assertTrue(fixture.resources.dispatcher.executorService.isShutdown)
        }
    }

    @Test
    fun closingAStalledBootstrapDoesNotCloseAnotherEnrollmentOwnersResources() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { first ->
                AndroidSessionEngineFixture(enrollment = true).use { second ->
                    assertNotSame(first.resources.pool, second.resources.pool)
                    assertNotSame(first.resources.dispatcher, second.resources.dispatcher)
                    first.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
                    val request = async(Dispatchers.Default) { runCatching { first.bootstrap() }.isFailure }
                    assertNotNull(first.server.takeRequest(SESSION_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    assertFalse(request.isCompleted)
                    first.owner.close()
                    assertTrue(withTimeout(SESSION_TEST_TIMEOUT_MS) { request.await() })
                    assertFalse(first.owner.engine.coroutineContext.job.isActive)
                    assertTrue(
                        first.resources.dispatcher.executorService
                            .awaitTermination(SESSION_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    )
                    assertEquals(0, first.resources.pool.connectionCount())
                    second.server.enqueue(MockResponse(body = "independent"))
                    assertEquals("independent", second.enroll().body)
                    assertFalse(second.resources.dispatcher.executorService.isShutdown)
                }
            }
        }

    @Test
    fun productionEnrollmentFactoryDoesNotAcceptThePrivateFixtureCaForEitherRoute() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true).use { fixture ->
                fixture.server.enqueue(MockResponse(body = "untrusted"))
                val owner = assertNotNull(createAndroidComplaintEnrollmentEngineOwner(fixture.url))
                val client = sessionTestClient(owner.engine)
                try {
                    assertFails { bootstrapExchange(client, fixture.bootstrapUrl) }
                    assertFails { enrollmentExchange(client, fixture.url) }
                    assertEquals(0, fixture.server.requestCount)
                } finally {
                    client.close()
                    owner.close()
                }
            }
        }

    @Test
    fun trustedFixtureCaStillRequiresMatchingHostnameForBothRoutes() =
        runBlocking {
            AndroidSessionEngineFixture(requestHost = "127.0.0.1", enrollment = true).use { fixture ->
                fixture.server.enqueue(MockResponse(body = "wrong-host"))
                assertFails { fixture.bootstrap() }
                assertFails { fixture.enroll() }
                assertEquals(0, fixture.server.requestCount)
            }
        }
}
