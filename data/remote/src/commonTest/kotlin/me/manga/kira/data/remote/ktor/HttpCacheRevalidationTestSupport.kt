package me.manga.kira.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import me.manga.kira.data.remote.ktor.cache.CACHE_TEST_NOW
import me.manga.kira.data.remote.ktor.cache.RecordingCachePersistence
import me.manga.kira.data.remote.ktor.cache.assertWithin
import me.manga.kira.data.remote.ktor.cache.cacheHeaders
import me.manga.kira.data.remote.ktor.cache.cacheOwner
import me.manga.kira.data.remote.ktor.cache.smallCachePolicy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val REVALIDATION_URL = "https://metadata.test/catalog"
internal const val OTHER_METADATA_URL = "https://metadata.test/other"
internal const val OLD_METADATA_BODY = "{\"version\":1}"
internal const val NEW_METADATA_BODY = "{\"version\":2}"
internal const val METADATA_ETAG = "\"version-one\""
internal const val METADATA_MODIFIED = "Mon, 14 Sep 2026 00:00:00 GMT"
internal val revalidationRequestHeaders =
    mapOf(
        HttpHeaders.UserAgent to "revalidation-test",
        HttpHeaders.Referrer to "https://reader.test/",
        HttpHeaders.Cookie to "session=test-only",
        HttpHeaders.Authorization to "Bearer test-only",
        "X-Edition" to "phone",
    )

internal fun revalidationHeaders(
    visibility: String = "public",
    etag: String = METADATA_ETAG,
): Headers =
    Headers.build {
        appendAll(cacheHeaders(cacheControl = "$visibility, max-age=60, no-cache"))
        append(HttpHeaders.ETag, etag)
        append(HttpHeaders.LastModified, METADATA_MODIFIED)
        append(HttpHeaders.Vary, "X-Edition")
    }

internal suspend fun HttpClient.fetchRevalidationMetadata(url: String = REVALIDATION_URL): String =
    get(url) {
        revalidationRequestHeaders.forEach { (name, value) -> headers.append(name, value) }
    }.bodyAsText()

/** Open304 disposal witnesses must reach the cache hook before Ktor's non-streaming body saving. */
internal suspend fun HttpClient.fetchStreamingRevalidationMetadata(): String =
    prepareGet(REVALIDATION_URL) {
        revalidationRequestHeaders.forEach { (name, value) -> headers.append(name, value) }
    }.execute { it.bodyAsText() }

internal fun HttpRequestData.assertOriginalMetadataHeaders() {
    revalidationRequestHeaders.forEach { (name, value) -> assertEquals(listOf(value), headers.getAll(name)) }
}

internal fun HttpRequestData.assertCacheConditional(etag: String = METADATA_ETAG) {
    assertEquals(listOf(etag), headers.getAll(HttpHeaders.IfNoneMatch))
    assertEquals(listOf(METADATA_MODIFIED), headers.getAll(HttpHeaders.IfModifiedSince))
}

internal fun HttpRequestData.assertUnconditional() {
    assertNull(headers[HttpHeaders.IfNoneMatch])
    assertNull(headers[HttpHeaders.IfModifiedSince])
}

internal enum class LostCacheCause { CLEAR, EXPIRY, EVICTION }

/** Real HttpCache sends a conditional request, then the test mutates the owner before releasing its304. */
internal class DeferredRevalidation(
    scope: TestScope,
    private val visibility: String = "public",
) {
    val policy = smallCachePolicy().copy(maxEntries = 1, maxVariantsPerUrl = 1)
    val disk = RecordingCachePersistence()
    var nowMillis = CACHE_TEST_NOW
    val cache = scope.cacheOwner(policy, disk, clock = { nowMillis })
    val conditionalStarted = CompletableDeferred<Unit>()
    val release304 = CompletableDeferred<Unit>()
    val failed304Body = ByteChannel()
    var targetCalls = 0
    var otherCalls = 0

    val handler: MockRequestHandler = { request ->
        request.assertOriginalMetadataHeaders()
        if (request.url == Url(OTHER_METADATA_URL)) {
            otherCalls++
            respond("other", headers = cacheHeaders())
        } else {
            assertEquals(Url(REVALIDATION_URL), request.url)
            when (++targetCalls) {
                1 -> respond(OLD_METADATA_BODY, headers = revalidationHeaders(visibility))
                2 -> {
                    request.assertCacheConditional()
                    conditionalStarted.complete(Unit)
                    release304.await()
                    respond(failed304Body, HttpStatusCode.NotModified, revalidationHeaders(visibility))
                }
                3 -> {
                    request.assertUnconditional()
                    respond(NEW_METADATA_BODY, headers = revalidationHeaders(visibility))
                }
                else -> error("Unexpected additional metadata request")
            }
        }
    }

    suspend fun loseEntry(
        cause: LostCacheCause,
        client: HttpClient,
    ) {
        when (cause) {
            LostCacheCause.CLEAR -> client.responseCacheClearer().clear()
            LostCacheCause.EXPIRY -> nowMillis = Long.MAX_VALUE
            LostCacheCause.EVICTION -> assertEquals("other", client.fetchRevalidationMetadata(OTHER_METADATA_URL))
        }
        assertTargetAbsent()
    }

    suspend fun assertTargetAbsent() {
        assertTrue(cache.publicStorage.findAll(Url(REVALIDATION_URL)).isEmpty())
        assertTrue(cache.privateStorage.findAll(Url(REVALIDATION_URL)).isEmpty())
        assertTrue(disk.records.keys.none { it.url == Url(REVALIDATION_URL) })
        cache.assertWithin(policy)
    }
}

internal suspend fun TestScope.assertLost304Recovers(
    cause: LostCacheCause,
    visibility: String = "public",
) {
    val fixture = DeferredRevalidation(this, visibility)
    try {
        withHttpCacheClient(fixture.cache, fixture.handler) { client ->
            assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata())
            val request = async { client.fetchStreamingRevalidationMetadata() }
            fixture.conditionalStarted.await()
            fixture.loseEntry(cause, client)
            fixture.release304.complete(Unit)
            assertEquals(NEW_METADATA_BODY, request.await())
            assertEquals(3, fixture.targetCalls)
            assertEquals(if (cause == LostCacheCause.EVICTION) 1 else 0, fixture.otherCalls)
            fixture.assertTargetAbsent() // The repair response must not resurrect a cleared/evicted record.
            assertTrue(fixture.failed304Body.isClosedForRead)
            assertNotNull(fixture.failed304Body.closedCause)
        }
    } finally {
        fixture.failed304Body.cancel(null)
    }
}
