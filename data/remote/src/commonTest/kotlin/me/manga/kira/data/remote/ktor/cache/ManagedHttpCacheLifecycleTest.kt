package me.manga.kira.data.remote.ktor.cache

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ManagedHttpCacheLifecycleTest {
    @Test
    fun publicAndPrivateRecordsWithIdenticalVaryDoNotOverwriteEachOther() =
        runTest {
            val disk = RecordingCachePersistence()
            val cache = cacheOwner(persistence = disk)
            val publicData = cachedResponse(body = byteArrayOf(1), vary = mapOf("Language" to "en"))
            val privateData = cachedResponse(body = byteArrayOf(2), vary = publicData.varyKeys)
            cache.publicStorage.store(publicData.url, publicData)
            cache.privateStorage.store(privateData.url, privateData)
            assertContentEquals(
                publicData.body,
                assertNotNull(cache.publicStorage.find(publicData.url, publicData.varyKeys)).body,
            )
            assertContentEquals(
                privateData.body,
                assertNotNull(cache.privateStorage.find(privateData.url, privateData.varyKeys)).body,
            )
            assertEquals(2, disk.records.size)
            cache.publicStorage.removeAll(publicData.url)
            assertNull(cache.publicStorage.find(publicData.url, publicData.varyKeys))
            assertNotNull(cache.privateStorage.find(privateData.url, privateData.varyKeys))
            assertEquals(
                setOf(CacheNamespace.PRIVATE),
                disk.records.keys
                    .map { it.namespace }
                    .toSet(),
            )
        }

    @Test
    fun findAndRemoveUseExactVaryIdentityNotSubsetMatching() =
        runTest {
            val cache = cacheOwner()
            val data = cachedResponse(vary = mapOf("Language" to "en", "Edition" to "mobile"))
            val subset = mapOf("Language" to "en")
            cache.publicStorage.store(data.url, data)
            assertNull(cache.publicStorage.find(data.url, subset))
            assertNull(cache.publicStorage.find(data.url, emptyMap()))
            cache.publicStorage.remove(data.url, subset)
            assertNotNull(cache.publicStorage.find(data.url, data.varyKeys))
            cache.publicStorage.remove(data.url, data.varyKeys)
            assertNull(cache.publicStorage.find(data.url, data.varyKeys))
            assertEquals(HttpCacheSnapshot(0, 0, 0), cache.snapshot())
        }

    @Test
    fun readsPromoteLruAcrossTheSharedNamespaceBudget() =
        runTest {
            val policy = smallCachePolicy().copy(maxEntries = 2, maxVariantsPerUrl = 2)
            val disk = RecordingCachePersistence()
            val cache = cacheOwner(policy, disk)
            val first = cachedResponse("https://metadata.test/first")
            val second = cachedResponse("https://metadata.test/second")
            val third = cachedResponse("https://metadata.test/third")
            cache.publicStorage.store(first.url, first)
            cache.privateStorage.store(second.url, second)
            assertNotNull(cache.publicStorage.find(first.url, emptyMap()))
            cache.publicStorage.store(third.url, third)
            assertNull(cache.privateStorage.find(second.url, emptyMap()))
            assertNotNull(cache.publicStorage.find(first.url, emptyMap()))
            assertEquals(
                setOf(first.url, third.url),
                disk.records.keys
                    .map { it.url }
                    .toSet(),
            )
        }

    @Test
    fun expirySweepsAllResidentBodiesAndTheirExactDiskRecords() =
        runTest {
            var now = CACHE_TEST_NOW
            val disk = RecordingCachePersistence()
            val cache = cacheOwner(persistence = disk, clock = { now })
            val data = cachedResponse(expires = now + 1)
            cache.publicStorage.store(data.url, data)
            cache.privateStorage.store(data.url, data)
            now++
            assertNull(cache.publicStorage.find(data.url, emptyMap()))
            assertEquals(HttpCacheSnapshot(0, 0, 0), cache.snapshot())
            assertEquals(0, disk.records.size)
            now--
            assertNull(
                cache.privateStorage.find(data.url, emptyMap()),
                "expired entries cannot reappear after clock rollback",
            )
        }

    @Test
    fun clearDropsLiveAndPersistedEntriesAndLaterStoresCanRepopulate() =
        runTest {
            val disk = RecordingCachePersistence()
            val cache = cacheOwner(persistence = disk)
            val data = cachedResponse()
            cache.publicStorage.store(data.url, data)
            cache.privateStorage.store(data.url, data)
            cache.clear()
            assertEquals(1, disk.clearCalls)
            assertEquals(0, disk.records.size)
            assertNull(cache.publicStorage.find(data.url, emptyMap()))
            assertNull(cache.privateStorage.find(data.url, emptyMap()))
            cache.publicStorage.store(data.url, data)
            assertNotNull(cache.publicStorage.find(data.url, emptyMap()))
        }

    @Test
    fun concurrentStoresReadsAndClearPreserveAllAggregateInvariants() =
        runTest {
            val policy = smallCachePolicy()
            val disk = RecordingCachePersistence()
            val cache = cacheOwner(policy, disk)
            coroutineScope {
                repeat(100) { index ->
                    launch {
                        if (index % 9 == 0) cache.clear()
                        val data =
                            cachedResponse(
                                "https://metadata.test/${index % 5}",
                                ByteArray(300),
                                mapOf("Edition" to "$index"),
                            )
                        val storage = if (index % 2 == 0) cache.publicStorage else cache.privateStorage
                        storage.store(data.url, data)
                        storage.findAll(data.url)
                        cache.assertWithin(policy)
                    }
                }
            }
            cache.clear()
            assertEquals(HttpCacheSnapshot(0, 0, 0), cache.snapshot())
            assertEquals(0, disk.records.size)
        }
}
