package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.cbz.IosCbzWriter
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.inspectPageArchive
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual native retaining writer + the existing file-backed Room fixture; no OS-kill/BG claim. */
class IosCbzConversionTest {
    @Test
    fun retainedNativePublicationRoomFailureReopenAndLegacyRepairPreserveTheArchive() = runTest {
        val fixture = IosCbzFinalizationFixture()
        try {
            val original = fixture.seedConversion()
            val writer = CountingRetainedIosWriter(IosCbzWriter(fixture.appFileSystem))
            val bytes = failBeforeCommit(fixture, original, writer)
            fixture.reopen()
            original.pages.keys.forEach { fixture.system.delete(it) } // Pre-protocol all-stale persisted shape.
            repairWithoutEncoding(fixture, original, writer, bytes)
        } finally { fixture.close() }
    }

    private suspend fun failBeforeCommit(f: IosCbzFinalizationFixture, original: IosCbzChapter, writer: CountingRetainedIosWriter): ByteArray {
        val mirror = f.conversionMirror(original)
        f.conversionFaults(ConversionCommitFault(f.db.chapterArtifactCommitDao(), before = {
            original.pages.forEach { (path, bytes) -> assertContentEquals(bytes, f.system.read(path) { readByteArray() }) }
            throw IOException("native Room return unavailable before commit")
        }, unreadableOutcome = true))
        val repository = f.settingsConverter(writer)
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(0, repository.observeCbzConversion().first().convertedChapters)
        assertEquals(1, repository.observeCbzConversion().first().failedChapters)
        assertEquals(1, writer.calls)
        assertEquals(original.saved, f.saved(original))
        assertEquals(original.download, f.download(original))
        assertEquals(mirror, f.conversionMirror(original))
        original.pages.forEach { (path, bytes) -> assertContentEquals(bytes, f.system.read(path) { readByteArray() }) }
        val pending = assertNotNull(f.db.chapterArtifactDao().get(original.saved.id))
        assertTrue(pending.retiring && pending.conversionSourceRoster != null)
        assertTrue(inspectPageArchive(f.system, f.archive(original), IosPageMediaInspector()) > 0)
        return f.system.read(f.archive(original)) { readByteArray() }
    }

    private suspend fun repairWithoutEncoding(
        f: IosCbzFinalizationFixture, original: IosCbzChapter, writer: CountingRetainedIosWriter, bytes: ByteArray,
    ) {
        val mirror = f.conversionMirror(original)
        f.conversionFaults(ConversionCommitFault(f.db.chapterArtifactCommitDao(), after = { throw IOException("after real Room commit") }))
        val repository = f.settingsConverter(writer)
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(1, repository.observeCbzConversion().first().convertedChapters)
        assertEquals(0, repository.observeCbzConversion().first().failedChapters)
        assertEquals(1, writer.calls, "All-stale repair adopts only the already-readable canonical archive")
        assertEquals(original.saved.copy(localImagePaths = listOf(f.archive(original).toString())), f.saved(original))
        assertEquals(original.download.copy(sizeBytes = bytes.size.toLong()), f.download(original))
        assertEquals(mirror.copy(localImagePaths = listOf(f.archive(original).toString())), f.conversionMirror(original))
        assertContentEquals(bytes, f.system.read(f.archive(original)) { readByteArray() })
        assertNull(f.db.chapterArtifactDao().get(original.saved.id)?.token)
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertEquals(0, repository.observeCbzConversion().first().totalChapters)
        assertFalse(repository.observeCbzConversion().first().isConverting)
        assertEquals(1, writer.calls)
    }
}

private class CountingRetainedIosWriter(private val native: CbzWriter) : CbzWriter by native {
    var calls = 0
        private set
    override suspend fun createCbzWithSplittingRetainingSources(
        imagePaths: List<Path>, mangaId: Long, chapterId: Long, quality: Int, maxHeight: Int, maxMemoryBytes: Long,
    ): Path {
        calls++
        return native.createCbzWithSplittingRetainingSources(imagePaths, mangaId, chapterId, quality, maxHeight, maxMemoryBytes)
    }
}
