package me.manga.kira.data.remote.ktor

import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cache.InvalidCacheStateException
import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.remote.ktor.cache.ManagedHttpCache
import me.manga.kira.data.remote.ktor.cache.assertWithin
import me.manga.kira.data.remote.ktor.cache.cacheHeaders
import me.manga.kira.data.remote.ktor.cache.cacheOwner
import me.manga.kira.data.remote.ktor.cache.cachedResponse
import me.manga.kira.data.remote.ktor.cache.smallCachePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpCacheRevalidationTest {
    @Test
    fun clearBetweenConditionalSendAnd304RecoversWithoutResurrectingCache() =
        runTest {
            assertLost304Recovers(LostCacheCause.CLEAR)
        }

    @Test
    fun actualPluginEvictionBetweenConditionalSendAnd304RecoversWithinAggregateCap() =
        runTest {
            assertLost304Recovers(LostCacheCause.EVICTION)
        }

    @Test
    fun expiryBetweenConditionalSendAnd304RecoversWithoutKeepingStaleBodies() =
        runTest {
            assertLost304Recovers(LostCacheCause.EXPIRY)
        }

    @Test
    fun privateVaryRevalidationHasTheSameClearRecovery() =
        runTest {
            assertLost304Recovers(LostCacheCause.CLEAR, visibility = "private")
        }

    @Test
    fun retained304ReusesItsBodyWithoutAnUnconditionalRetry() =
        runTest {
            val cache = cacheOwner()
            var calls = 0
            withHttpCacheClient(cache, { request ->
                request.assertOriginalMetadataHeaders()
                if (++calls == 1) {
                    respond(OLD_METADATA_BODY, headers = revalidationHeaders())
                } else {
                    request.assertCacheConditional()
                    respond("", HttpStatusCode.NotModified, revalidationHeaders())
                }
            }) { client ->
                repeat(2) { assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata()) }
                assertEquals(2, calls)
                assertEquals(1, cache.snapshot().entries)
                cache.assertWithin(smallCachePolicy())
            }
        }

    @Test
    fun concurrentRefillAfterTheMissCannotReAddValidatorsOrBeOverwrittenByRepair() =
        runTest {
            val fixture = DeferredRevalidation(this)
            val replacement =
                cachedResponse(
                    body = "refilled".encodeToByteArray(),
                    vary = mapOf("x-edition" to "phone"),
                    headers = revalidationHeaders(etag = "\"refilled\""),
                )
            val refilled = CompletableDeferred<Unit>()
            val hook = refillAfterCacheMiss(fixture.cache, replacement, refilled)
            withHttpCacheClient(fixture.cache, fixture.handler, configure = { install(hook) }) { client ->
                assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata())
                val request = async { client.fetchRevalidationMetadata() }
                fixture.conditionalStarted.await()
                fixture.loseEntry(LostCacheCause.CLEAR, client)
                fixture.release304.complete(Unit)
                assertEquals(NEW_METADATA_BODY, request.await())
                assertTrue(refilled.isCompleted)
                assertEquals(3, fixture.targetCalls)
                assertEquals(
                    "refilled",
                    fixture.cache.publicStorage
                        .findAll(Url(REVALIDATION_URL))
                        .single()
                        .body
                        .decodeToString(),
                )
                assertEquals(1, fixture.disk.records.size)
                fixture.cache.assertWithin(fixture.policy)
                assertTrue(fixture.failed304Body.isClosedForRead)
            }
        }

    @Test
    fun etagAloneIsRecognizedAndOnlyItsAddedConditionIsRemoved() =
        runTest {
            assertSingleValidatorRecovery(HttpHeaders.ETag, METADATA_ETAG)
        }

    @Test
    fun lastModifiedAloneIsRecognizedAndOnlyItsAddedConditionIsRemoved() =
        runTest {
            assertSingleValidatorRecovery(HttpHeaders.LastModified, METADATA_MODIFIED)
        }
}

private fun refillAfterCacheMiss(
    cache: ManagedHttpCache,
    replacement: CachedResponseData,
    refilled: CompletableDeferred<Unit>,
) = createClientPlugin("RefillAfterCacheMiss") {
    on(Send) { request ->
        try {
            proceed(request)
        } catch (failure: InvalidCacheStateException) {
            if (!refilled.isCompleted) {
                cache.publicStorage.store(replacement.url, replacement)
                refilled.complete(Unit)
            }
            throw failure
        }
    }
}

private suspend fun TestScope.assertSingleValidatorRecovery(
    name: String,
    value: String,
) {
    val cache = cacheOwner()
    val headers =
        Headers.build {
            appendAll(cacheHeaders(cacheControl = "public, max-age=60, no-cache"))
            append(name, value)
        }
    var calls = 0
    withHttpCacheClient(cache, { request ->
        when (++calls) {
            1 -> respond(OLD_METADATA_BODY, headers = headers)
            2 -> {
                val condition = if (name == HttpHeaders.ETag) HttpHeaders.IfNoneMatch else HttpHeaders.IfModifiedSince
                assertEquals(value, request.headers[condition])
                cache.clear()
                respond("", HttpStatusCode.NotModified, headers)
            }
            else -> {
                request.assertUnconditional()
                respond(NEW_METADATA_BODY, headers = headers)
            }
        }
    }) { client ->
        assertEquals(OLD_METADATA_BODY, client.get(REVALIDATION_URL).bodyAsText())
        assertEquals(NEW_METADATA_BODY, client.get(REVALIDATION_URL).bodyAsText())
        assertEquals(3, calls)
        assertEquals(0, cache.snapshot().entries)
    }
}
