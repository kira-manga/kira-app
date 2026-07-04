package me.manga.kira.platform.cbz

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path
import okio.Path.Companion.toPath

/**
 * Pure-logic regression tests for [PagePathRederivation] (B7, stale absolute source paths after an
 * iOS container-UUID change). Locks the three-step precedence so the manual Yami Compressor can
 * never again silently skip every page, while the background finalize's live paths stay untouched.
 */
class PagePathRederivationTest {

    private val live = "/NEW-UUID/Documents/manga/10/chapter_1".toPath()

    private fun existsIn(vararg present: Path): (Path) -> Boolean = { it in present.toSet() }

    @Test
    fun storedPathThatExists_isUsedAsIs() {
        // The background finalize passes live paths — the rule must be a strict no-op there,
        // even when the chapter-dir candidate ALSO exists (never hijack a valid path).
        val stored = "/NEW-UUID/Documents/manga/10/chapter_1/page_0001.webp".toPath()
        assertEquals(
            stored,
            PagePathRederivation.resolveSourcePage(
                stored = stored,
                chapterDir = live,
                exists = existsIn(stored, live / "page_0001.webp"),
            ),
        )
    }

    @Test
    fun staleStoredPath_isRescuedUnderLiveChapterDir() {
        // B7 core: reinstall/restore changed the container UUID — the stored absolute path is dead
        // but the same filename exists under the live chapter dir.
        val stored = "/OLD-UUID/Documents/manga/10/chapter_1/page_0001.webp".toPath()
        val rescued = live / "page_0001.webp"
        assertEquals(
            rescued,
            PagePathRederivation.resolveSourcePage(stored = stored, chapterDir = live, exists = existsIn(rescued)),
        )
    }

    @Test
    fun missingEverywhere_returnsStoredForWarnAndSkip() {
        // Neither location has the file → return the caller's ORIGINAL path so the downstream
        // warn-and-skip log points at what the caller actually passed.
        val stored = "/OLD-UUID/Documents/manga/10/chapter_1/page_0001.webp".toPath()
        assertEquals(
            stored,
            PagePathRederivation.resolveSourcePage(stored = stored, chapterDir = live, exists = existsIn()),
        )
    }

    @Test
    fun rescueUsesFilenameOnly_notTheStoredDirectoryStructure() {
        // Only the leaf name is re-rooted; the stale directory prefix is discarded wholesale.
        val stored = "/OLD-UUID/some/other/nesting/page_0042.jpg".toPath()
        val rescued = live / "page_0042.jpg"
        assertEquals(
            rescued,
            PagePathRederivation.resolveSourcePage(stored = stored, chapterDir = live, exists = existsIn(rescued)),
        )
    }
}
