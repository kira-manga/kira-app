package me.manga.kira.presentation.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerManga
import me.manga.kira.presentation.testing.readerPage
import me.manga.kira.presentation.testing.readerTestEnv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #5 continuous reader — the owner-flagged guarantees that bookmark, history, and resume follow the
 * ACTIVE visible chapter (not the anchor), the slider seek maps within-chapter position to the right
 * absolute page, and the active-chapter follow-up fires ONLY when the active chapter URL changes.
 *
 * Setup mirrors [ReaderViewModelAppendTest]: enter chapter 1, append chapter 2, then scroll across.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelActiveChapterTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    private val chapters = listOf(readerChapter("1"), readerChapter("2"))

    /** Enter ch1 (pages a,b) then append ch2 (pages c,d). Feed: ch1[0,1], ch2[2,3]. */
    private fun enterAndAppend() = readerTestEnv(chapterList = chapters).also { env ->
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("a"), readerPage("b"))))
        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("c"), readerPage("d"))))
        env.vm.submit(ReaderIntent.OnAppendNextChapter)
    }

    @Test
    fun sliderSeek_withinActiveChapter_mapsToAbsolutePage() = runTest {
        val env = enterAndAppend()
        // Move into chapter 2 (absolute page 2 = ch2 page 1).
        env.vm.submit(ReaderIntent.OnPageChanged(2))
        val s = env.vm.state.value
        assertEquals(listOf(2, 3), s.activeChapterPageIndices)

        // Slider "seek to within-chapter position 1" → absolute index activeChapterPageIndices[1] = 3.
        env.vm.submit(ReaderIntent.OnPageChanged(s.activeChapterPageIndices[1]))
        val s2 = env.vm.state.value
        assertEquals(3, s2.currentPageIndex, "within-chapter seek resolves to the right absolute page")
        assertEquals(2, s2.activeChapterPageNumber, "HUD shows page 2 of chapter 2")
        assertEquals("ch/2", s2.activeChapterUrl)
    }

    @Test
    fun resume_savesAgainstActiveChapter_withWithinChapterIndex() = runTest {
        val env = enterAndAppend()
        env.vm.submit(ReaderIntent.OnPageChanged(2)) // ch2, first page

        assertTrue(
            env.readProgress.saved.contains("ch/2" to 0),
            "resume saved for the ACTIVE chapter (ch/2) with within-chapter index 0; saved=${env.readProgress.saved}",
        )
        // It must NOT have saved the flat index (2) nor keyed it to the anchor chapter.
        assertTrue(env.readProgress.saved.none { it == "ch/1" to 2 })
    }

    @Test
    fun bookmarkAndHistory_followActiveChapter_onlyWhenChapterUrlChanges() = runTest {
        val env = enterAndAppend()
        // After enter, the anchor chapter was observed + recorded once.
        assertEquals("ch/1", env.bookmark.observed.first())
        assertTrue(env.history.recorded.any { it.second == "ch/1" })

        // Cross the boundary into chapter 2 → re-observe + record for ch/2 exactly once.
        env.vm.submit(ReaderIntent.OnPageChanged(2))
        assertEquals("ch/2", env.bookmark.observed.last(), "bookmark observer re-points to the active chapter")
        assertEquals(1, env.history.recorded.count { it.second == "ch/2" }, "history recorded for ch/2 once")
        val observedCh2 = env.bookmark.observed.count { it == "ch/2" }

        // A further scroll WITHIN chapter 2 (page 3) must NOT re-observe or re-record (guard).
        env.vm.submit(ReaderIntent.OnPageChanged(3))
        assertEquals(observedCh2, env.bookmark.observed.count { it == "ch/2" }, "no re-observe on same-chapter scroll")
        assertEquals(1, env.history.recorded.count { it.second == "ch/2" }, "no re-record on same-chapter scroll")
    }

    @Test
    fun toggleBookmark_targetsActiveVisibleChapter_notAnchor() = runTest {
        val env = enterAndAppend()
        env.vm.submit(ReaderIntent.OnPageChanged(2)) // active = ch/2

        env.vm.submit(ReaderIntent.OnToggleBookmark)
        assertEquals("ch/2", env.bookmark.toggled.last(), "bookmark toggles the chapter in view, not the anchor")
    }

    @Test
    fun singleAppend_addsExactlyOneChapter_noChain() = runTest {
        val env = enterAndAppend()
        // enterAndAppend already appended ch2; the single OnAppendNextChapter loaded exactly one chapter.
        assertEquals(listOf("ch/1", "ch/2"), env.vm.state.value.loadedChapterUrls)
    }

    @Test
    fun toggleBookmark_notInLibrary_emitsShowNotInLibrary() = runTest {
        // #15 — toggling a chapter whose manga isn't in the library is a store no-op; the VM must
        // surface ShowNotInLibrary so the screen can nudge the user to add to Library first.
        val env = enterAndAppend()
        env.bookmark.inLibrary = false
        val effects = mutableListOf<ReaderEffect>()
        val job = launch(dispatcher) { env.vm.effects.collect { effects += it } }
        env.vm.submit(ReaderIntent.OnToggleBookmark)
        job.cancel()
        assertTrue(
            effects.any { it is ReaderEffect.ShowNotInLibrary },
            "not-in-library bookmark toggle emits ShowNotInLibrary: $effects",
        )
    }

    @Test
    fun toggleBookmark_inLibrary_doesNotEmitShowNotInLibrary() = runTest {
        // #15 — the in-library path (default) must NOT show the hint.
        val env = enterAndAppend()
        env.bookmark.inLibrary = true
        val effects = mutableListOf<ReaderEffect>()
        val job = launch(dispatcher) { env.vm.effects.collect { effects += it } }
        env.vm.submit(ReaderIntent.OnToggleBookmark)
        job.cancel()
        assertTrue(
            effects.none { it is ReaderEffect.ShowNotInLibrary },
            "in-library bookmark toggle is silent: $effects",
        )
    }
}
