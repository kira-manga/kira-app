@file:Suppress("MagicNumber")

package me.manga.kira.platform.backup

import me.manga.kira.platform.filesystem.AppFileSystem
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.ForwardingSource
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.use
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackupImportStagingTest {
    private val system = FileSystem.SYSTEM
    private val root = Files.createTempDirectory("backup-staging-").toString().toPath()
    private val cache = root / "cache"

    @AfterTest
    fun close() = system.deleteRecursively(root)

    @Test
    fun actualProviderBytesAreBoundedDespiteAbsentOrFalseSizesAndSourcesAreClosed() {
        val staging = staging()
        for (declared in listOf(null, -1L, 0L, 1L, 4L)) {
            val exact = TrackedSource("1234".encodeToByteArray())
            val path = staging.stage(declared, {}) { exact }
            assertTrue(exact.closed)
            staging.claimOrCopy(path) {}.use { input ->
                assertEquals("1234", system.read(input.path) { readUtf8() })
            }
            val over = TrackedSource("12345".encodeToByteArray())
            assertFailsWith<BackupImportLimitExceeded> { staging.stage(declared, {}) { over } }
            assertTrue(over.closed)
            assertNoSnapshots()
        }
    }

    @Test
    fun declaredOversizeRejectsWithoutOpeningProviderAndReleasesAdmissionSlot() {
        val staging = staging()
        var opened = false
        assertFailsWith<BackupImportLimitExceeded> {
            staging.stage(5, {}) {
                opened = true
                Buffer()
            }
        }
        assertFalse(opened)
        val path = staging.stage(null, {}) { Buffer().writeUtf8("1234") }
        staging.discardPending(path)
        assertNoSnapshots()
    }

    @Test
    fun handoffIsOneShotAndPickerDisposalCannotDeleteImporterOwnedInput() {
        val staging = staging()
        val path = staging.stage(null, {}) { Buffer().writeUtf8("1234") }
        assertFailsWith<BackupAcquisitionBusy> { staging.stage(null, {}) { Buffer() } }
        val input = staging.claimOrCopy(path) {}
        staging.discardPending(path)
        assertTrue(system.exists(input.path))
        assertFailsWith<BackupAcquisitionBusy> { staging.claimOrCopy(path) {} }
        input.close()
        val next = staging.stage(null, {}) { Buffer().writeUtf8("next") }
        input.close() // A stale owner may not release the successor's slot.
        assertFailsWith<BackupAcquisitionBusy> { staging.stage(null, {}) { Buffer() } }
        staging.discardPending(next)
        assertNoSnapshots()
    }

    @Test
    fun aPathInTheStagingTreeIsNotAnOwnershipCapability() {
        val staging = staging()
        val original = cache / "backup_import" / "user-original.zip"
        system.createDirectories(requireNotNull(original.parent))
        system.write(original) { writeUtf8("1234") }
        staging.discardPending(original.toString())
        staging.claimOrCopy(original.toString()) {}.use { input ->
            assertNotEquals(original, input.path)
            system.write(original) { writeUtf8("edit") }
            assertEquals("1234", system.read(input.path) { readUtf8() })
            assertFailsWith<BackupAcquisitionBusy> { staging.stage(null, {}) { Buffer() } }
        }
        assertEquals("edit", system.read(original) { readUtf8() })
        assertEquals(listOf(original), system.listRecursively(cache).filter { system.metadata(it).isRegularFile }.toList())
    }

    @Test
    fun cancellationAfterAPartialWriteClosesTheProviderAndCleansOnlyOwnedFiles() {
        val staging = staging(limit = 10_000)
        val retained = cache / "backup_import" / "retained.zip"
        system.createDirectories(requireNotNull(retained.parent))
        system.write(retained) { writeUtf8("retained") }
        val source = TrackedSource(ByteArray(9000))
        val cancellation = CancellationException("cancel after first chunk")
        var checkpoints = 0
        val thrown = assertFailsWith<CancellationException> {
            staging.stage(null, { if (++checkpoints == 3) throw cancellation }) { source }
        }
        assertSame(cancellation, thrown)
        assertTrue(source.closed)
        assertEquals(listOf(retained), system.list(cache / "backup_import"))
        assertEquals("retained", system.read(retained) { readUtf8() })
        val next = staging.stage(null, {}) { Buffer().writeUtf8("ok") }
        staging.discardPending(next)
    }

    @Test
    fun providerReadFailureCleansPartialAndReleasesTheSlot() {
        val staging = staging(limit = 10_000)
        var closed = false
        var reads = 0
        val source = object : ForwardingSource(Buffer().write(ByteArray(9000))) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (++reads == 2) throw IOException("provider unavailable")
                return super.read(sink, byteCount)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }
        assertFailsWith<IOException> { staging.stage(null, {}) { source } }
        assertTrue(closed)
        assertNoSnapshots()
        val next = staging.stage(null, {}) { Buffer().writeUtf8("ok") }
        staging.discardPending(next)
    }

    @Test
    fun storageFailureAfterAPartialWriteClosesProviderAndRemovesPartial() {
        var written = false
        val fullDisk = object : ForwardingFileSystem(system) {
            override fun sink(file: Path, mustCreate: Boolean): Sink =
                object : ForwardingSink(super.sink(file, mustCreate)) {
                    override fun write(source: Buffer, byteCount: Long) {
                        if (written) throw IOException("disk full")
                        super.write(source, byteCount)
                        written = true
                    }
                }
        }
        val source = TrackedSource(ByteArray(9000))
        assertFailsWith<IOException> { staging(limit = 10_000, fs = fullDisk).stage(null, {}) { source } }
        assertTrue(written)
        assertTrue(source.closed)
        assertNoSnapshots()
    }

    private fun staging(limit: Long = 4, fs: FileSystem = system): BackupImportStaging =
        BackupImportStaging(
            object : AppFileSystem {
                override val filesDir: Path = root / "files"
                override val cacheDir: Path = cache
                override fun fileSystem(): FileSystem = fs
            },
            BackupImportPolicy(archive = BackupZipLimits(maxArchiveBytes = limit)),
        )

    private fun assertNoSnapshots() {
        if (system.exists(cache)) assertFalse(system.listRecursively(cache).any { system.metadata(it).isRegularFile })
    }

    private class TrackedSource(bytes: ByteArray) : ForwardingSource(Buffer().write(bytes)) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
