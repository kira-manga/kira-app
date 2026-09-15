package me.manga.kira.data.remote.ktor.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ManagedHttpCacheFailureTest {
    @Test
    fun writeFailureDisablesFurtherGrowthUntilAnExplicitSuccessfulClear() =
        runTest {
            val disk = RecordingCachePersistence()
            val cache = cacheOwner(persistence = disk)
            val first = cachedResponse()
            cache.publicStorage.store(first.url, first)
            disk.writeFailure = IOException("write unavailable")
            val second = cachedResponse("https://metadata.test/second")
            cache.privateStorage.store(second.url, second)
            assertNull(cache.publicStorage.find(first.url, emptyMap()))
            repeat(10) { cache.privateStorage.store(second.url, second) }
            assertEquals(2, disk.writeCalls)
            assertEquals(1, disk.records.size)
            disk.writeFailure = null
            cache.clear()
            cache.privateStorage.store(second.url, second)
            assertEquals(3, disk.writeCalls)
            assertNotNull(cache.privateStorage.find(second.url, emptyMap()))
        }

    @Test
    fun failedClearIsReportedDropsLiveHitsAndKeepsWritesDisabled() =
        runTest {
            val disk = RecordingCachePersistence()
            val cache = cacheOwner(persistence = disk)
            val data = cachedResponse()
            cache.publicStorage.store(data.url, data)
            disk.clearFailure = IOException("delete unavailable")
            assertFailsWith<IOException> { cache.clear() }
            assertNull(cache.publicStorage.find(data.url, emptyMap()))
            cache.publicStorage.store(data.url, data)
            assertEquals(1, disk.writeCalls)
            assertEquals(1, disk.records.size)
            disk.clearFailure = null
            cache.clear()
            assertEquals(2, disk.clearCalls)
            assertEquals(0, disk.records.size)
        }

    @Test
    fun loadFailureDoesNotRetryAndAccumulateRecordsOnEveryRequest() =
        runTest {
            val disk = RecordingCachePersistence().apply { loadFailure = IOException("read unavailable") }
            val cache = cacheOwner(persistence = disk)
            val data = cachedResponse()
            repeat(10) { cache.publicStorage.store(data.url, data) }
            assertEquals(1, disk.loadCalls)
            assertEquals(0, disk.writeCalls)
            assertEquals(HttpCacheSnapshot(0, 0, 0), cache.snapshot())
        }

    @Test
    fun failedEvictionDisablesWritesInsteadOfAllowingDiskGrowth() =
        runTest {
            val policy = smallCachePolicy().copy(maxEntries = 1, maxVariantsPerUrl = 1)
            val disk = RecordingCachePersistence()
            val cache = cacheOwner(policy, disk)
            val first = cachedResponse()
            cache.publicStorage.store(first.url, first)
            disk.removeFailure = IOException("eviction unavailable")
            val next = cachedResponse("https://metadata.test/next")
            repeat(10) { cache.publicStorage.store(next.url, next) }
            assertEquals(1, disk.writeCalls)
            assertEquals(1, disk.records.size)
            assertNull(cache.publicStorage.find(first.url, emptyMap()))
        }

    @Test
    fun cancellationIsNotConvertedIntoACacheMissOrSuccessfulClear() =
        runTest {
            val disk = RecordingCachePersistence().apply { loadFailure = CancellationException("cancel load") }
            val cache = cacheOwner(persistence = disk)
            val data = cachedResponse()
            assertFailsWith<CancellationException> { cache.publicStorage.store(data.url, data) }
            disk.loadFailure = null
            cache.publicStorage.store(data.url, data)
            assertNotNull(cache.publicStorage.find(data.url, emptyMap()))
            disk.clearFailure = CancellationException("cancel clear")
            assertFailsWith<CancellationException> { cache.clear() }
            assertNull(cache.publicStorage.find(data.url, emptyMap()))
        }
}
