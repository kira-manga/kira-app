package me.manga.kira.data.remote.ktor

import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cache.InvalidCacheStateException
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.remote.ktor.cache.cacheOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpCacheRevalidationSafetyTest {
    @Test
    fun callerAuthoredConditionsAreNeverRemovedOrAutomaticallyRetried() =
        runTest {
            val conditions =
                mapOf(
                    HttpHeaders.IfNoneMatch to "\"caller-only\"",
                    HttpHeaders.IfModifiedSince to METADATA_MODIFIED,
                    HttpHeaders.IfMatch to "\"caller-only\"",
                    HttpHeaders.IfUnmodifiedSince to METADATA_MODIFIED,
                    HttpHeaders.IfRange to "\"caller-only\"",
                )
            conditions.forEach { (name, value) -> assertCallerConditionPreserved(name, value) }
        }

    @Test
    fun unsolicited304WithoutAnyOwnedValidatorIsNotRetried() =
        runTest {
            val cache = cacheOwner()
            var calls = 0
            withHttpCacheClient(cache, { request ->
                calls++
                request.assertUnconditional()
                respond("", HttpStatusCode.NotModified, revalidationHeaders())
            }) { client ->
                assertFailsWith<InvalidCacheStateException> { client.fetchRevalidationMetadata() }
                assertEquals(1, calls)
                assertEquals(0, cache.snapshot().entries)
            }
        }

    @Test
    fun getWithNonemptyOutgoingContentIsNeverReplayed() =
        runTest {
            val cache = cacheOwner()
            var calls = 0
            withHttpCacheClient(cache, { request ->
                calls++
                request.assertUnconditional()
                respond("", HttpStatusCode.NotModified, revalidationHeaders())
            }) { client ->
                assertFailsWith<InvalidCacheStateException> {
                    client.get(REVALIDATION_URL) { setBody("not replayable metadata") }.bodyAsText()
                }
                assertEquals(1, calls)
            }
        }

    @Test
    fun second304PropagatesAtTheRetryCeilingAndBothFailedBodiesAreCancelled() =
        runTest {
            val replies = RepeatedNotModifiedReplies(this)
            try {
                withHttpCacheClient(replies.cache, replies.handler) { client ->
                    assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata())
                    assertFailsWith<InvalidCacheStateException> { client.fetchRevalidationMetadata() }
                    assertEquals(3, replies.calls)
                    assertTrue(replies.bodies.all { it.isClosedForRead && it.closedCause != null })
                    assertEquals(0, replies.cache.snapshot().entries)
                }
            } finally {
                replies.bodies.forEach { it.cancel(null) }
            }
        }

    @Test
    fun changedValidatorAfterThe304IsNotClobberedByRecovery() =
        runTest {
            val cache = cacheOwner()
            val mutate = changeValidatorAfterCacheMiss()
            var calls = 0
            withHttpCacheClient(cache, { request ->
                if (++calls == 1) {
                    respond(OLD_METADATA_BODY, headers = revalidationHeaders())
                } else {
                    request.assertCacheConditional()
                    cache.clear()
                    respond("", HttpStatusCode.NotModified, revalidationHeaders())
                }
            }, configure = { install(mutate) }) { client ->
                assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata())
                assertFailsWith<InvalidCacheStateException> { client.fetchRevalidationMetadata() }
                assertEquals(2, calls)
            }
        }
}

private suspend fun TestScope.assertCallerConditionPreserved(
    name: String,
    value: String,
) {
    val cache = cacheOwner()
    var calls = 0
    withHttpCacheClient(cache, { request ->
        if (++calls == 1) {
            respond(OLD_METADATA_BODY, headers = revalidationHeaders())
        } else {
            assertTrue(value in request.headers.getAll(name).orEmpty())
            request.assertOriginalMetadataHeaders()
            cache.clear()
            respond("", HttpStatusCode.NotModified, revalidationHeaders())
        }
    }) { client ->
        assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata())
        assertFailsWith<InvalidCacheStateException> {
            client
                .get(REVALIDATION_URL) {
                    revalidationRequestHeaders.forEach { (key, header) -> headers.append(key, header) }
                    headers.append(name, value)
                }.bodyAsText()
        }
        assertEquals(2, calls)
    }
}

private class RepeatedNotModifiedReplies(
    scope: TestScope,
) {
    val cache = scope.cacheOwner()
    val bodies = listOf(ByteChannel(), ByteChannel())
    var calls = 0
    val handler: MockRequestHandler = { request ->
        request.assertOriginalMetadataHeaders()
        when (++calls) {
            1 -> respond(OLD_METADATA_BODY, headers = revalidationHeaders())
            2 -> {
                request.assertCacheConditional()
                cache.clear()
                respond(bodies[0], HttpStatusCode.NotModified, revalidationHeaders())
            }
            3 -> {
                request.assertUnconditional()
                respond(bodies[1], HttpStatusCode.NotModified, revalidationHeaders())
            }
            else -> error("Recovery exceeded one request")
        }
    }
}

private fun changeValidatorAfterCacheMiss() =
    createClientPlugin("ChangeValidatorAfterCacheMiss") {
        on(Send) { request ->
            try {
                proceed(request)
            } catch (failure: InvalidCacheStateException) {
                request.headers.remove(HttpHeaders.IfNoneMatch)
                request.headers.append(HttpHeaders.IfNoneMatch, "\"plugin-changed\"")
                throw failure
            }
        }
    }
