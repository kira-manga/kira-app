package me.manga.kira.data.remote.ktor.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileHttpCachePersistenceTest {
    @Test
    fun manyRecordsObeyDiskAndLiveBudgetsWithOnlyOneBoundedStagingFile() =
        runTest {
            withCacheDirectory { fixture ->
                val policy = smallCachePolicy().copy(maxTotalBytes = 2_048)
                val observer = StagingBudgetObserver(fixture.fileSystem, fixture, policy)
                val cache = cacheOwner(policy, fixture.persistence(policy, observer))
                repeat(STAGING_RECORD_COUNT) { index ->
                    val data = cachedResponse("https://metadata.test/$index", ByteArray(STAGING_BODY_BYTES))
                    val storage = if (index % 2 == 0) cache.publicStorage else cache.privateStorage
                    storage.store(data.url, data)
                    fixture.assertMatches(cache, policy)
                }
                assertEquals(STAGING_RECORD_COUNT, observer.moves)
            }
        }

    @Test
    fun publicPrivateAndVaryRecordsSurviveRestartWithoutNamespaceCollision() =
        runTest {
            withCacheDirectory { fixture ->
                val policy = smallCachePolicy()
                val cache = cacheOwner(policy, fixture.persistence(policy))
                val first = cachedResponse(body = byteArrayOf(1), vary = mapOf("Edition" to "mobile"))
                val second = cachedResponse(body = byteArrayOf(2), vary = first.varyKeys)
                cache.publicStorage.store(first.url, first)
                cache.privateStorage.store(second.url, second)
                assertEquals(2, fixture.records().size)
                assertEquals(setOf("public", "private"), fixture.records().map { it.parent?.name }.toSet())
                val restarted = cacheOwner(policy, fixture.persistence(policy))
                assertContentEquals(
                    first.body,
                    assertNotNull(restarted.publicStorage.find(first.url, first.varyKeys)).body,
                )
                assertContentEquals(
                    second.body,
                    assertNotNull(restarted.privateStorage.find(second.url, second.varyKeys)).body,
                )
                restarted.publicStorage.removeAll(first.url)
                assertEquals(
                    "private",
                    fixture
                        .records()
                        .single()
                        .parent
                        ?.name,
                )
                assertNotNull(restarted.privateStorage.find(second.url, second.varyKeys))
                fixture.assertMatches(restarted, policy)
            }
        }

    @Test
    fun restartReappliesSmallerAggregateEntryAndVariantLimits() =
        runTest {
            withCacheDirectory { fixture ->
                val oldPolicy =
                    smallCachePolicy().copy(
                        maxTotalBytes = 20_000,
                        maxEntries = RESTART_RECORD_COUNT,
                        maxVariantsPerUrl = 10,
                    )
                val cache = cacheOwner(oldPolicy, fixture.persistence(oldPolicy))
                repeat(RESTART_RECORD_COUNT) { index ->
                    val data =
                        cachedResponse(
                            "https://metadata.test/${index % 2}",
                            ByteArray(RESTART_BODY_BYTES),
                            mapOf("V" to "$index"),
                        )
                    val storage =
                        if (index % RESTART_PRIVATE_CADENCE == 0) cache.privateStorage else cache.publicStorage
                    storage.store(data.url, data)
                }
                assertEquals(RESTART_RECORD_COUNT, fixture.records().size)
                val policy = smallCachePolicy().copy(maxTotalBytes = 2_048, maxEntries = 3, maxVariantsPerUrl = 2)
                val restarted = cacheOwner(policy, fixture.persistence(policy))
                fixture.assertMatches(restarted, policy)
                assertTrue(restarted.snapshot().entries > 0)
            }
        }

    @Test
    fun lruEvictionAndExpiryPhysicallyRemoveTheCorrectRecords() =
        runTest {
            withCacheDirectory { fixture ->
                var now = CACHE_TEST_NOW
                val policy = smallCachePolicy().copy(maxEntries = 2, maxVariantsPerUrl = 2)
                val cache = cacheOwner(policy, fixture.persistence(policy), clock = { now })
                val first = cachedResponse("https://metadata.test/first", expires = now + 1)
                val second = cachedResponse("https://metadata.test/second", expires = now + 1)
                cache.publicStorage.store(first.url, first)
                val firstFile = fixture.records().single()
                cache.privateStorage.store(second.url, second)
                val secondFile = fixture.records().single { it != firstFile }
                cache.publicStorage.find(first.url, emptyMap())
                val third = cachedResponse("https://metadata.test/third", expires = now + 1)
                cache.publicStorage.store(third.url, third)
                assertTrue(fixture.fileSystem.exists(firstFile))
                assertFalse(fixture.fileSystem.exists(secondFile))
                now++
                assertNull(cache.publicStorage.find(first.url, emptyMap()))
                assertEquals(0, fixture.records().size)
                fixture.assertMatches(cache, policy)
            }
        }

    @Test
    fun concurrentStoresAndClearStayBoundedAndNeverTouchDurableDownloadSentinel() =
        runTest {
            withCacheDirectory { fixture ->
                val sentinel = File(fixture.home, "chapter-page.jpg").apply { writeText("durable download") }
                val policy = smallCachePolicy()
                val observer = StagingBudgetObserver(fixture.fileSystem, fixture, policy)
                val cache =
                    ManagedHttpCache(
                        policy,
                        fixture.persistence(policy, observer),
                        { CACHE_TEST_NOW },
                        Dispatchers.Default,
                    )
                coroutineScope {
                    repeat(CONCURRENT_STORE_COUNT) { index ->
                        launch(Dispatchers.Default) {
                            if (index % CONCURRENT_CLEAR_CADENCE == 0) cache.clear()
                            val data =
                                cachedResponse(
                                    "https://metadata.test/${index % CONCURRENT_URL_COUNT}",
                                    ByteArray(CONCURRENT_BODY_BYTES),
                                    mapOf("V" to "$index"),
                                )
                            val storage = if (index % 2 == 0) cache.publicStorage else cache.privateStorage
                            storage.store(data.url, data)
                            cache.assertWithin(policy)
                        }
                    }
                }
                fixture.assertMatches(cache, policy)
                cache.clear()
                fixture.assertMatches(cache, policy)
                assertEquals(0, fixture.records().size)
                assertEquals("durable download", sentinel.readText())
            }
        }
}

private const val STAGING_RECORD_COUNT = 50
private const val STAGING_BODY_BYTES = 400
private const val RESTART_RECORD_COUNT = 20
private const val RESTART_BODY_BYTES = 300
private const val RESTART_PRIVATE_CADENCE = 3
private const val CONCURRENT_STORE_COUNT = 80
private const val CONCURRENT_CLEAR_CADENCE = 7
private const val CONCURRENT_URL_COUNT = 4
private const val CONCURRENT_BODY_BYTES = 200
