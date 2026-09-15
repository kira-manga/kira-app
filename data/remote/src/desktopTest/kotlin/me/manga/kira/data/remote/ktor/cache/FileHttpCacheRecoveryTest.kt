package me.manga.kira.data.remote.ktor.cache

import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.IOException
import okio.Path
import okio.Source
import okio.buffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileHttpCacheRecoveryTest {
    @Test
    fun oversizedAndNegativeDeclaredLengthsAreRejectedBeforeAnyBodyRead() =
        runTest {
            withCacheDirectory { fixture ->
                val policy = smallCachePolicy()
                val directory = fixture.root / "public"
                fixture.fileSystem.createDirectories(directory)
                val lengths = listOf(0 to policy.maxBodyBytes + 1, Int.MAX_VALUE to 0, -1 to 0, 0 to Int.MAX_VALUE)
                lengths.forEachIndexed { index, (metadataSize, bodySize) ->
                    val recordName =
                        index.toString(RECORD_ID_RADIX).padStart(RECORD_DIGEST_HEX_LENGTH, '0') + ".khc"
                    val path = directory / recordName
                    fixture.fileSystem.sink(path).buffer().use { sink ->
                        sink.writeInt(CACHE_RECORD_MAGIC)
                        sink.writeInt(metadataSize)
                        sink.writeInt(bodySize)
                        if (index == 0) sink.write(ByteArray(bodySize))
                    }
                }
                val guarded = PrefixOnlyFileSystem(fixture.fileSystem)
                val cache = cacheOwner(policy, fixture.persistence(policy, guarded))
                assertEquals(0, cache.snapshot().entries)
                assertEquals(lengths.size, guarded.sourceCalls)
                assertEquals(lengths.size * CACHE_RECORD_PREFIX_BYTES, guarded.readBytes)
                assertEquals(0, fixture.records().size)
            }
        }

    @Test
    fun validRecordUnderWrongIdentityIsDeletedRatherThanServed() =
        runTest {
            withCacheDirectory { fixture ->
                val policy = smallCachePolicy()
                val cache = cacheOwner(policy, fixture.persistence(policy))
                val data = cachedResponse()
                cache.publicStorage.store(data.url, data)
                val original = fixture.records().single()
                val wrong = fixture.root / "public" / ("f".repeat(RECORD_DIGEST_HEX_LENGTH) + ".khc")
                fixture.fileSystem.atomicMove(original, wrong)
                val restarted = cacheOwner(policy, fixture.persistence(policy))
                assertEquals(0, restarted.snapshot().entries)
                assertFalse(fixture.fileSystem.exists(wrong))
            }
        }

    @Test
    fun startupRemovesOnlyOwnedStagingAndLegacyFlatCacheFiles() =
        runTest {
            withCacheDirectory { fixture ->
                val policy = smallCachePolicy()
                fixture.fileSystem.createDirectories(fixture.root)
                val staging = fixture.root / ".record.staging"
                val legacy = fixture.root / "a".repeat(RECORD_DIGEST_HEX_LENGTH)
                val unknown = fixture.root / "unrelated-note.txt"
                listOf(staging, legacy, unknown).forEach { path ->
                    fixture.fileSystem
                        .sink(path)
                        .buffer()
                        .use { it.writeUtf8("sentinel") }
                }
                val outside = File(fixture.home, "chapter.jpg").apply { writeText("download") }
                val cache = cacheOwner(policy, fixture.persistence(policy))
                fixture.assertMatches(cache, policy)
                assertFalse(fixture.fileSystem.exists(staging))
                assertFalse(fixture.fileSystem.exists(legacy))
                cache.clear()
                assertTrue(fixture.fileSystem.exists(unknown))
                assertEquals("download", outside.readText())
            }
        }

    @Test
    fun failedMoveAndCleanupLeaveAtMostOneStagingRecordAndDisableFurtherWrites() =
        runTest {
            withCacheDirectory { fixture ->
                val policy = smallCachePolicy()
                val failing = FailedStagingFileSystem(fixture.fileSystem)
                val cache = cacheOwner(policy, fixture.persistence(policy, failing))
                val data = cachedResponse(body = ByteArray(FAILED_WRITE_BODY_BYTES))
                repeat(FAILED_WRITE_ATTEMPTS) { cache.publicStorage.store(data.url, data) }
                assertEquals(1, failing.moves)
                assertEquals(0, fixture.records().size)
                val staging = fixture.root / ".record.staging"
                assertTrue(fixture.fileSystem.exists(staging))
                val stagingLimit = CACHE_RECORD_PREFIX_BYTES + policy.maxBodyBytes + policy.maxMetadataBytes
                assertTrue((fixture.fileSystem.metadata(staging).size ?: 0L) <= stagingLimit)
                assertFailsWith<IOException> { cache.clear() }
                failing.fail = false
                cache.clear()
                cache.publicStorage.store(data.url, data)
                assertEquals(2, failing.moves)
                assertNotNull(cache.publicStorage.find(data.url, emptyMap()))
                fixture.assertMatches(cache, policy)
            }
        }

    @Test
    fun unsafeNamespaceFailsClosedWithoutClearingAnUnrelatedPath() =
        runTest {
            withCacheDirectory { fixture ->
                fixture.fileSystem.createDirectories(fixture.root)
                val occupied = fixture.root / "public"
                fixture.fileSystem
                    .sink(occupied)
                    .buffer()
                    .use { it.writeUtf8("not a cache directory") }
                val policy = smallCachePolicy()
                val cache = cacheOwner(policy, fixture.persistence(policy))
                assertEquals(0, cache.snapshot().entries)
                assertFailsWith<IOException> { cache.clear() }
                assertEquals(
                    "not a cache directory",
                    fixture.fileSystem
                        .source(occupied)
                        .buffer()
                        .use { it.readUtf8() },
                )
            }
        }
}

private const val RECORD_ID_RADIX = 16
private const val RECORD_DIGEST_HEX_LENGTH = 64
private const val CACHE_RECORD_MAGIC = 0x4B484331 // KHC1 fixture, independent of the production encoder.
private const val FAILED_WRITE_BODY_BYTES = 400
private const val FAILED_WRITE_ATTEMPTS = 10

private class PrefixOnlyFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    var sourceCalls = 0
    var readBytes = 0L

    override fun source(file: Path): Source {
        sourceCalls++
        return object : ForwardingSource(super.source(file)) {
            private var remaining = CACHE_RECORD_PREFIX_BYTES

            override fun read(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                check(remaining > 0) { "Rejected record must not read its body" }
                val count = super.read(sink, minOf(byteCount, remaining))
                if (count > 0) {
                    remaining -= count
                    readBytes += count
                }
                return count
            }
        }
    }
}

private class FailedStagingFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    var fail = true
    var moves = 0

    override fun atomicMove(
        source: Path,
        target: Path,
    ) {
        moves++
        if (fail) throw IOException("atomic move unavailable")
        super.atomicMove(source, target)
    }

    override fun delete(
        path: Path,
        mustExist: Boolean,
    ) {
        if (fail && path.name == ".record.staging" && delegate.exists(path)) throw IOException("cleanup unavailable")
        super.delete(path, mustExist)
    }
}
