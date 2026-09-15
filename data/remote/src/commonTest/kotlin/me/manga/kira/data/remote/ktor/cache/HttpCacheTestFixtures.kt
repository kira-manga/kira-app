package me.manga.kira.data.remote.ktor.cache

import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertTrue

internal const val CACHE_TEST_NOW = 1_000L

internal fun cacheHeaders(
    contentType: String? = "application/json",
    cacheControl: String? = "public, max-age=60",
): Headers =
    Headers.build {
        contentType?.let { append(HttpHeaders.ContentType, it) }
        cacheControl?.let { append(HttpHeaders.CacheControl, it) }
    }

internal fun cachedResponse(
    url: String = "https://metadata.test/catalog",
    body: ByteArray = "{}".encodeToByteArray(),
    vary: Map<String, String> = emptyMap(),
    headers: Headers = cacheHeaders(),
    expires: Long = CACHE_TEST_NOW + 60_000,
): CachedResponseData =
    CachedResponseData(
        Url(url),
        HttpStatusCode.OK,
        GMTDate(CACHE_TEST_NOW),
        GMTDate(CACHE_TEST_NOW),
        HttpProtocolVersion.HTTP_1_1,
        GMTDate(expires),
        headers,
        vary,
        body,
    )

internal fun smallCachePolicy(): HttpCachePolicy =
    HttpCachePolicy(
        maxTotalBytes = 4_096,
        maxBodyBytes = 512,
        maxMetadataBytes = 1_024,
        maxEntries = 8,
        maxVariantsPerUrl = 4,
    )

internal fun TestScope.cacheOwner(
    policy: HttpCachePolicy = smallCachePolicy(),
    persistence: HttpCachePersistence? = null,
    clock: () -> Long = { CACHE_TEST_NOW },
): ManagedHttpCache = ManagedHttpCache(policy, persistence, clock, StandardTestDispatcher(testScheduler))

internal suspend fun ManagedHttpCache.assertWithin(policy: HttpCachePolicy) {
    val state = snapshot()
    assertTrue(state.entries in 0..policy.maxEntries)
    assertTrue(state.bytes in 0..policy.maxTotalBytes)
    assertTrue(state.mostVariantsPerUrl in 0..policy.maxVariantsPerUrl)
}

internal class RecordingCachePersistence : HttpCachePersistence {
    val records = linkedMapOf<RecordedCacheKey, Pair<CachedResponseData, ByteArray>>()
    var loadFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var removeFailure: Throwable? = null
    var clearFailure: Throwable? = null
    var loadCalls = 0
    var writeCalls = 0
    var clearCalls = 0

    override fun load(consume: (CacheNamespace, CachedResponseData, ByteArray) -> Unit) {
        loadCalls++
        loadFailure?.let { throw it }
        records.toList().forEach { (key, record) -> consume(key.namespace, record.first, record.second) }
    }

    override fun write(
        namespace: CacheNamespace,
        data: CachedResponseData,
        metadata: ByteArray,
    ) {
        writeCalls++
        writeFailure?.let { throw it }
        records[RecordedCacheKey(namespace, data.url, data.varyKeys)] = data to metadata
    }

    override fun remove(
        namespace: CacheNamespace,
        url: Url,
        varyKeys: Map<String, String>,
    ) {
        removeFailure?.let { throw it }
        records.remove(RecordedCacheKey(namespace, url, varyKeys))
    }

    override fun clear() {
        clearCalls++
        clearFailure?.let { throw it }
        records.clear()
    }
}

internal data class RecordedCacheKey(
    val namespace: CacheNamespace,
    val url: Url,
    val vary: Map<String, String>,
)
