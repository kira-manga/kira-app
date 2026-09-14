package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageImageFormat
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageInvalidReason
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

/** Protocol tests use a strict fixture inspector; real native image fixtures live in :platform. */
class ValidatedPageRosterTest {
    private val fs = FileSystem.SYSTEM
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "page-roster-${Random.nextLong().toULong()}"
    private val policy = PageBytePolicy(4)
    private val manifest = DownloadManifest(1, 2, "fixture", List(2) { ManifestPage(it, "page-$it", emptyMap()) })
    private val inspector = object : PageMediaInspector {
        override fun inspect(encoded: ByteArray): PageInspection =
            if (encoded.contentEquals("good".encodeToByteArray())) PageInspection.Valid(PageImageMetadata(PageImageFormat.PNG, 1, 1))
            else PageInspection.Invalid(PageInvalidReason.INCOMPLETE_OR_CORRUPT)

        override fun inspect(path: Path): PageInspection = inspect(fs.read(path) { readByteArray() })
    }

    @AfterTest
    fun cleanup() = fs.deleteRecursively(directory, mustExist = false)

    @Test
    fun invalidOversizedAndStrayIndicesCannotShrinkIntoACompleteRoster() {
        write("image_0.png", "good")
        write("image_1.png", "HTML")
        write("image_1.jpg", "oversized")
        write("image_99.png", "good")
        write("image_1.partial", "good")
        val roster = inspectPageRoster(fs, directory, manifest, inspector, policy)
        assertEquals(setOf(0), roster.indices)
        assertNull(roster.completePaths)
        assertEquals(listOf(1), BackgroundReconciler.plan(manifest, roster.indices, emptySet(), 3).toEnqueue)
    }

    @Test
    fun completePathsAreBoundToEveryExpectedIndexAndSortedNumerically() {
        write("image_1.png", "good")
        write("image_0.png", "good")
        assertEquals(listOf("image_0.png", "image_1.png"), inspectPageRoster(fs, directory, manifest, inspector, policy).completePaths?.map { it.substringAfterLast('/') })
    }

    @Test
    fun duplicateValidAlternatesAreAmbiguousNotTwoPagesOrArbitraryOldBytes() {
        write("image_0.png", "good")
        write("image_0.jpg", "good")
        write("image_1.png", "good")
        val roster = inspectPageRoster(fs, directory, manifest, inspector, policy)
        assertEquals(setOf(1), roster.indices)
        assertNull(roster.completePaths)
    }

    @Test
    fun emptyDuplicateAndGappedManifestsCannotAuthorizeCompleteness() {
        for (pages in listOf(emptyList(), listOf(manifest.pages[0], manifest.pages[0]), listOf(manifest.pages[1]))) {
            val invalid = manifest.copy(pages = pages)
            assertFailsWith<IllegalArgumentException> { inspectPageRoster(fs, directory, invalid, inspector, policy) }
            assertTrue(!BackgroundReconciler.plan(invalid, setOf(0, 1), emptySet(), 3).isComplete)
        }
    }

    private fun write(name: String, bytes: String) {
        fs.createDirectories(directory)
        fs.write(directory / name) { writeUtf8(bytes) }
    }
}
