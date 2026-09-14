package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Filesystem/roster protocol checks. Real decoder recovery lives in ChapterPageRecoveryTest. */
class DownloadedPageFilesTest {
    private val system = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "page-files-${Random.nextLong().toULong()}"
    private val files =
        object : AppFileSystem {
            override val filesDir: Path = root / "current"
            override val cacheDir: Path = root / "cache"

            override fun fileSystem(): FileSystem = system
        }
    private val inspector = RecoveryFixtureInspector(system)
    private val directory = files.chapterDir(1, 2)

    @AfterTest
    fun cleanup() = system.deleteRecursively(root, mustExist = false)

    @Test
    fun oneBadOrMissingMemberNeverReturnsAFilteredSubset() =
        runTest {
            val good = write(directory / "0.jpg", recoveryTestPng())
            val bad = write(directory / "1.jpg", "<html>challenge</html>".encodeToByteArray())
            val resolver = DownloadedPageFiles(files, inspector)
            assertNull(resolver.resolve(1, 2, listOf(good.toString(), bad.toString())))
            assertNull(resolver.resolve(1, 2, listOf(good.toString(), (directory / "missing.jpg").toString())))
            assertTrue(system.exists(good))
            assertTrue(system.exists(bad), "resolution must not delete originals or conceal recoverable input")
        }

    @Test
    fun currentContainerIsPreferredButInvalidCurrentCanUseTheStoredImage() =
        runTest {
            val stored = write(root / "old" / "0.jpg", recoveryTestPng())
            val current = write(directory / "0.jpg", recoveryTestPng())
            val resolver = DownloadedPageFiles(files, inspector)
            assertEquals(listOf(current), resolver.resolve(1, 2, listOf(stored.toString())))
            write(current, "bad".encodeToByteArray())
            assertEquals(listOf(stored), resolver.resolve(1, 2, listOf(stored.toString())))
            assertEquals("bad", system.read(current) { readUtf8() })
        }

    @Test
    fun blankDuplicateEmptyAndOverBudgetRostersAreUnusable() =
        runTest {
            val good = write(directory / "0.png", recoveryTestPng())
            val resolver = DownloadedPageFiles(files, inspector)
            assertNull(resolver.resolve(1, 2, emptyList()))
            assertNull(resolver.resolve(1, 2, listOf("")))
            assertNull(resolver.resolve(1, 2, listOf(good.toString(), good.toString())))
            assertNull(DownloadedPageFiles(files, inspector, PageBytePolicy(1)).resolve(1, 2, listOf(good.toString())))
            assertTrue(system.exists(good))
        }

    @Test
    fun cancellationFromInspectionPropagatesWithoutDeletingThePage() =
        runTest {
            val good = write(directory / "0.png", recoveryTestPng())
            val cancelled =
                object : PageMediaInspector by inspector {
                    override fun inspect(path: Path): PageInspection = throw CancellationException(
                        "cancelled inspection",
                    )
                }
            assertFailsWith<CancellationException> {
                DownloadedPageFiles(files, cancelled).resolve(1, 2, listOf(good.toString()))
            }
            assertTrue(system.exists(good))
        }

    private fun write(
        path: Path,
        bytes: ByteArray,
    ): Path {
        system.createDirectories(requireNotNull(path.parent))
        system.write(path) { write(bytes) }
        return path
    }
}
