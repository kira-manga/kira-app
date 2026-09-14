package me.manga.kira.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpCacheRevalidationConcurrencyTest {
    @Test
    fun cancellationBeforeThe304DoesNotStartRecovery() =
        runTest {
            val fixture = DeferredRevalidation(this)
            try {
                withHttpCacheClient(fixture.cache, fixture.handler) { client ->
                    assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata())
                    val request = async { client.fetchRevalidationMetadata() }
                    fixture.conditionalStarted.await()
                    fixture.loseEntry(LostCacheCause.CLEAR, client)
                    request.cancelAndJoin()
                    assertTrue(request.isCancelled)
                    assertEquals(2, fixture.targetCalls)
                    assertTrue(!fixture.release304.isCompleted)
                    fixture.assertTargetAbsent()
                }
            } finally {
                fixture.failed304Body.cancel(null)
            }
        }

    @Test
    fun cancellationDuringRepairRemainsAttachedToTheRequestingJob() =
        runTest {
            val held = HeldCacheRepair(this)
            withHttpCacheClient(held.fixture.cache, held.handler) { client ->
                val request = startHeldRepair(held, client)
                request.cancelAndJoin()
                held.repairFinished.await()
                assertTrue(request.isCancelled)
                assertTrue(held.repairWasCancelled)
                assertEquals(3, held.targetWireCalls)
                assertTrue(held.fixture.failed304Body.isClosedForRead)
                held.fixture.assertTargetAbsent()
            }
        }

    @Test
    fun unrelatedRequestsStillCacheWhileARecoveryBypassIsSuspended() =
        runTest {
            val held = HeldCacheRepair(this)
            withHttpCacheClient(held.fixture.cache, held.handler) { client ->
                val request = startHeldRepair(held, client)
                repeat(2) { assertEquals("other", client.fetchRevalidationMetadata(OTHER_METADATA_URL)) }
                assertEquals(1, held.fixture.otherCalls)
                assertEquals(
                    1,
                    held.fixture.cache
                        .snapshot()
                        .entries,
                )
                held.releaseRepair.complete(Unit)
                assertEquals(NEW_METADATA_BODY, request.await())
                assertEquals(3, held.targetWireCalls)
                assertEquals("other", client.fetchRevalidationMetadata(OTHER_METADATA_URL))
                assertEquals(1, held.fixture.otherCalls)
                held.fixture.assertTargetAbsent()
            }
        }
}

private class HeldCacheRepair(
    scope: TestScope,
) {
    val fixture = DeferredRevalidation(scope)
    val repairStarted = CompletableDeferred<Unit>()
    val releaseRepair = CompletableDeferred<Unit>()
    val repairFinished = CompletableDeferred<Unit>()
    var targetWireCalls = 0
    var repairWasCancelled = false

    val handler: MockRequestHandler = { request ->
        if (request.url == Url(REVALIDATION_URL)) {
            targetWireCalls++
            if (targetWireCalls == 3) {
                request.assertOriginalMetadataHeaders()
                request.assertUnconditional()
                repairStarted.complete(Unit)
                try {
                    releaseRepair.await()
                } finally {
                    repairWasCancelled = !currentCoroutineContext().isActive
                    repairFinished.complete(Unit)
                }
            }
        }
        fixture.handler.invoke(this, request)
    }
}

private suspend fun TestScope.startHeldRepair(
    held: HeldCacheRepair,
    client: HttpClient,
): Deferred<String> {
    assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata())
    val request = async { client.fetchRevalidationMetadata() }
    held.fixture.conditionalStarted.await()
    held.fixture.loseEntry(LostCacheCause.CLEAR, client)
    held.fixture.release304.complete(Unit)
    held.repairStarted.await()
    return request
}
