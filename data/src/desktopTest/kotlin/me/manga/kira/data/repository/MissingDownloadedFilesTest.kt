package me.manga.kira.data.repository

import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Only existing fixture-owned directories model an iOS container move; no real sandbox access. */
class MissingDownloadedFilesTest {
    @Test
    fun movedIosContainerRetainsCurrentFilesAndRepairsOnlyAfterBothLocationsAreAbsent() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val current = movedContainerFiles()
        val directory = current.chapterDir(original.saved.mangaId, original.saved.id)
        val priorPaths = priorContainerPaths(current, original)
        val moved = original.copy(saved = original.saved.copy(localImagePaths = priorPaths))
        db.backupDao().updateChapterRow(moved.saved)
        fs.createDirectories(directory)
        original.contents.forEach { (path, bytes) -> fs.write(directory / path.toPath().name) { writeUtf8(bytes) } }
        assertEquals(0, repairMissingMetadata(files = current))
        assertEquals(moved.saved, saved(moved))
        fs.deleteRecursively(directory)
        assertEquals(0, repairMissingMetadata(files = current))
        assertMissingMetadata(moved)
        assertRetainedFiles(original)
    }

    @Test
    fun accessiblePriorContainerStillProtectsItsRosterWhenCurrentRootIsMissing() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val current = movedContainerFiles()
        val priorPaths = priorContainerPaths(current, original)
        val moved = original.copy(saved = original.saved.copy(localImagePaths = priorPaths))
        db.backupDao().updateChapterRow(moved.saved)
        priorPaths.forEach { raw ->
            val path = raw.toPath()
            fs.createDirectories(assertNotNull(path.parent))
            fs.write(path) { writeUtf8("retained prior-container bytes") }
        }
        assertEquals(0, repairMissingMetadata(files = current))
        assertEquals(moved.saved, saved(moved))
        assertEquals(moved.download, download(moved))
        priorPaths.forEach { assertEquals("retained prior-container bytes", fs.read(it.toPath()) { readUtf8() }) }
    }

    @Test
    fun foreignAndSymlinkPathsCannotAuthorizeNegativeRepair() = downloadRecoveryTest {
        val foreign = seed(isDownloaded = true)
        val foreignSaved = foreign.saved.copy(localImagePaths = listOf((appFileSystem.filesDir / "unrelated/missing.jpg").toString()))
        db.backupDao().updateChapterRow(foreignSaved)
        val symlinked = seed(isDownloaded = true)
        val link = symlinked.saved.localImagePaths.last().toPath()
        fs.delete(link)
        val target = appFileSystem.filesDir / "absent-target.jpg"
        fs.createSymlink(link, target)
        assertEquals(0, repairMissingMetadata())
        assertEquals(foreignSaved, saved(foreign))
        assertEquals(foreign.download, download(foreign))
        assertEquals(symlinked.saved, saved(symlinked))
        assertEquals(symlinked.download, download(symlinked))
        assertEquals(target, fs.metadata(link).symlinkTarget)
    }
}

private fun DownloadRecoveryFixture.movedContainerFiles(): AppFileSystem = object : AppFileSystem by appFileSystem {
    override val filesDir: Path = appFileSystem.filesDir / "Containers/Data/Application/$CURRENT_CONTAINER/Documents"
}

private fun priorContainerPaths(current: AppFileSystem, original: RetainedDownload): List<String> {
    val containers = assertNotNull(assertNotNull(current.filesDir.parent).parent)
    val directory = containers / PRIOR_CONTAINER / "Documents/manga/${original.saved.mangaId}/chapter_${original.saved.id}"
    return original.saved.localImagePaths.map { (directory / it.toPath().name).toString() }
}

private const val CURRENT_CONTAINER = "11111111-1111-4111-8111-111111111111"
private const val PRIOR_CONTAINER = "22222222-2222-4222-8222-222222222222"
