package me.manga.kira.data.remote.ktor.cache

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ManagedHttpCacheBudgetTest {
    @Test
    fun productionLimitsAreAggregateAndExplicit() {
        val policy = HttpCachePolicy()
        assertEquals(16L * 1024 * 1024, policy.maxTotalBytes)
        assertEquals(4 * 1024 * 1024, policy.maxBodyBytes)
        assertEquals(64 * 1024, policy.maxMetadataBytes)
        assertEquals(128, policy.maxEntries)
        assertEquals(4, policy.maxVariantsPerUrl)
    }

    @Test
    fun exactBodyMetadataAndAggregateBoundariesAreAdmitted() =
        runTest {
            val data = cachedResponse(body = ByteArray(32))
            val metadata = assertNotNull(HttpCacheMetadataCodec(1_024).encode(data))
            val bytes = metadata.size + data.body.size + CACHE_RECORD_PREFIX_BYTES
            val policy =
                smallCachePolicy().copy(
                    maxTotalBytes = bytes,
                    maxBodyBytes = data.body.size,
                    maxMetadataBytes = metadata.size,
                )
            val cache = cacheOwner(policy)
            cache.publicStorage.store(data.url, data)
            assertNotNull(cache.publicStorage.find(data.url, emptyMap()))
            assertEquals(HttpCacheSnapshot(1, bytes, 1), cache.snapshot())
        }

    @Test
    fun oneByteOverEachBoundaryIsRejectedWithoutRetention() =
        runTest {
            val data = cachedResponse(body = ByteArray(32))
            val metadata = assertNotNull(HttpCacheMetadataCodec(1_024).encode(data))
            val bytes = metadata.size + data.body.size + CACHE_RECORD_PREFIX_BYTES
            val policies =
                listOf(
                    smallCachePolicy().copy(maxTotalBytes = bytes - 1),
                    smallCachePolicy().copy(maxBodyBytes = data.body.size - 1),
                    smallCachePolicy().copy(maxMetadataBytes = metadata.size - 1),
                )
            policies.forEach { policy ->
                val disk = RecordingCachePersistence()
                val cache = cacheOwner(policy, disk)
                cache.publicStorage.store(data.url, data)
                assertEquals(HttpCacheSnapshot(0, 0, 0), cache.snapshot())
                assertEquals(0, disk.writeCalls)
            }
        }

    @Test
    fun manyUrlsShareOneByteBudgetAcrossBothNamespaces() =
        runTest {
            val policy = smallCachePolicy().copy(maxTotalBytes = 2_048)
            val cache = cacheOwner(policy)
            repeat(80) { index ->
                val data = cachedResponse("https://metadata.test/$index", ByteArray(400))
                val storage = if (index % 2 == 0) cache.publicStorage else cache.privateStorage
                storage.store(data.url, data)
                cache.assertWithin(policy)
            }
            assertNull(cache.publicStorage.find(Url("https://metadata.test/0"), emptyMap()))
            assertNotNull(cache.privateStorage.find(Url("https://metadata.test/79"), emptyMap()))
        }

    @Test
    fun tinyBodiesStillObeyTheTotalEntryLimit() =
        runTest {
            val policy = smallCachePolicy().copy(maxTotalBytes = 100_000, maxEntries = 3, maxVariantsPerUrl = 3)
            val cache = cacheOwner(policy)
            repeat(20) { index ->
                val data = cachedResponse("https://metadata.test/$index", byteArrayOf(1))
                cache.publicStorage.store(data.url, data)
            }
            assertEquals(3, cache.snapshot().entries)
            cache.assertWithin(policy)
        }

    @Test
    fun variantsOfOneUrlShareFourSlotsIncludingBothNamespaces() =
        runTest {
            val policy = smallCachePolicy()
            val cache = cacheOwner(policy)
            repeat(24) { index ->
                val data = cachedResponse(vary = mapOf("Accept-Language" to "language-$index"))
                val storage = if (index % 2 == 0) cache.publicStorage else cache.privateStorage
                storage.store(data.url, data)
                cache.assertWithin(policy)
            }
            val url = cachedResponse().url
            assertEquals(4, cache.snapshot().entries)
            assertEquals(2, cache.publicStorage.findAll(url).size)
            assertEquals(2, cache.privateStorage.findAll(url).size)
        }

    @Test
    fun replacementUpdatesChargeAndCopiesTheCallersBodyAndVary() =
        runTest {
            val cache = cacheOwner()
            val old = cachedResponse(body = ByteArray(40), vary = mapOf("Language" to "en"))
            cache.publicStorage.store(old.url, old)
            val before = cache.snapshot()
            val bytes = ByteArray(24) { 7 }
            val vary = mutableMapOf("Language" to "en")
            val replacement = cachedResponse(body = bytes, vary = vary)
            cache.publicStorage.store(replacement.url, replacement)
            bytes.fill(0)
            vary["Language"] = "changed"
            val hit = assertNotNull(cache.publicStorage.find(old.url, old.varyKeys))
            assertContentEquals(ByteArray(24) { 7 }, hit.body)
            assertEquals(mapOf("Language" to "en"), hit.varyKeys)
            assertEquals(HttpCacheSnapshot(1, before.bytes - 16, 1), cache.snapshot())
        }

    @Test
    fun metadataUtf8ByteCountIncludesLargeHeadersAndVaryValues() =
        runTest {
            val headers =
                Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                    append(HttpHeaders.CacheControl, "max-age=60")
                    append("X-Metadata", "é".repeat(600))
                }
            val cache = cacheOwner()
            val headerHeavy = cachedResponse(headers = headers)
            val varyHeavy = cachedResponse(vary = mapOf("Language" to "é".repeat(600)))
            cache.publicStorage.store(headerHeavy.url, headerHeavy)
            cache.privateStorage.store(varyHeavy.url, varyHeavy)
            assertEquals(HttpCacheSnapshot(0, 0, 0), cache.snapshot())
        }

    @Test
    fun lossyUtf8VaryIdentityIsRejectedRatherThanAliasingAnotherRequest() =
        runTest {
            val cache = cacheOwner()
            val data = cachedResponse(vary = mapOf("Edition" to "\uD800"))
            cache.publicStorage.store(data.url, data)
            assertNull(cache.publicStorage.find(data.url, data.varyKeys))
            assertNull(cache.publicStorage.find(data.url, mapOf("Edition" to "?")))
            assertEquals(HttpCacheSnapshot(0, 0, 0), cache.snapshot())
        }
}
