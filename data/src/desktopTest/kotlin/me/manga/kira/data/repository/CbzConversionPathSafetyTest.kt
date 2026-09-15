package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import me.manga.kira.platform.filesystem.chapterDir
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Normalized cleanup authority is only over recorded immediate loose children of this owner. */
class CbzConversionPathSafetyTest {
    @Test fun foreignAbsoluteInputIsNotConversionOrCleanupAuthority() = refusedSource("external")
    @Test fun anotherChapterInputIsNotConversionOrCleanupAuthority() = refusedSource("other-owner")
    @Test fun sourceSymlinkCannotEscapeTheOwnedChapter() = refusedSource("symlink")
    @Test fun mixedCbzAndLooseInputsCannotGrantArchiveDeletionAuthority() = refusedSource("archive")
    @Test fun restoredGenerationInputCannotBeReclaimedAsALoosePage() = refusedSource("restored")

    private fun refusedSource(kind: String) = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val pages = installValidPages(original)
        val (archive, bytes) = installPreviousArchive(original)
        val invalid = invalidSource(kind, original, archive)
        val requested = original.saved.copy(localImagePaths = listOf(pages.keys.first().toString(), invalid.toString()))
        db.backupDao().updateChapterRow(requested)
        val writer = CbzCallerWriter { _, _ -> error("invalid source admission reached writer") }
        val repository = settingsConverter(writer)
        assertTrue(repository.compressExistingDownloads().isSuccess)
        assertTrue(writer.requests.isEmpty())
        assertEquals(0, repository.observeCbzConversion().first().convertedChapters)
        assertEquals(1, repository.observeCbzConversion().first().failedChapters)
        assertEquals(requested, saved(original))
        assertEquals(original.download, download(original))
        assertNull(artifactRuntime.dao.get(original.saved.id))
        assertContentEquals(bytes, fs.read(archive) { readByteArray() })
        if (kind != "archive") assertContentEquals(CBZ_CALLER_PNG, fs.read(invalid) { readByteArray() })
        if (kind == "symlink") assertTrue(fs.metadata(invalid).symlinkTarget != null)
    }

    private suspend fun DownloadRecoveryFixture.invalidSource(kind: String, original: RetainedDownload, archive: Path): Path {
        val directory = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id)
        if (kind == "archive") return archive
        if (kind == "other-owner") {
            val other = seed(isDownloaded = false)
            installValidPages(other)
            return other.saved.localImagePaths.first().toPath()
        }
        val external = appFileSystem.cacheDir / "untouched.png"
        fs.createDirectories(appFileSystem.cacheDir)
        fs.write(external) { write(CBZ_CALLER_PNG) }
        if (kind == "external") return external
        if (kind == "symlink") {
            val link = directory / "link.png"
            fs.createSymlink(link, external)
            return link
        }
        val restored = directory / "_restored/11111111-1111-4111-8111-111111111111/page.png"
        fs.createDirectories(restored.parent!!)
        fs.write(restored) { write(CBZ_CALLER_PNG) }
        return restored
    }
}
