package me.manga.kira.presentation.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.Chapter
import me.manga.kira.presentation.testing.readerChapter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Paired domain-port paths, complementing the real-Room SavedChapterOrderTest without a UI harness. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSavedChapterOrderTest {
    private val dispatcher = StandardTestDispatcher()
    private val fixtures = mutableListOf<ReaderActiveActionFixture>()
    private val chapters = listOf("18", "17", "16").map(::readerChapter)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun networkAndSavedListsKeepNextAndPreviousDirection() =
        runTest(dispatcher) {
            val cases = listOf(ReaderIntent.OnNextChapter to chapters[2], ReaderIntent.OnPrevChapter to chapters[0])
            for (fromSaved in listOf(false, true)) {
                for ((intent, target) in cases) {
                    val fixture = fixture(fromSaved)

                    fixture.dispatch(intent)

                    assertReplacement(fixture, target)
                    assertEquals(listOf(chapters[1], target), fixture.pages.requested.map { it.second })
                    assertEquals(listOf(fixture.manga to chapters[1]), fixture.pages.cleared)
                    assertEquals(listOf(fixture.manga to chapters[1].url), fixture.markRead.marked)
                    assertSource(fixture, fromSaved)
                }
            }
        }

    @Test
    fun networkAndSavedListsAppendOlderChapterWithoutReplacingFeed() =
        runTest(dispatcher) {
            for (fromSaved in listOf(false, true)) {
                val fixture = fixture(fromSaved)
                val initial = fixture.vm.state.value

                fixture.dispatch(ReaderIntent.OnAppendNextChapter)

                val appended = fixture.vm.state.value
                assertEquals(initial.pages + activeActionPages(chapters[2]), appended.pages)
                assertEquals(listOf("ch/17", "ch/16"), appended.loadedChapterUrls)
                assertEquals(listOf("ch/17", "ch/17", "ch/16", "ch/16"), appended.pageChapters)
                assertEquals(initial.chapter, appended.chapter)
                assertEquals(initial.currentPageIndex, appended.currentPageIndex)
                assertEquals("ch/17", appended.activeChapterUrl)
                assertTrue(fixture.pages.cleared.isEmpty(), "append must retain the current chapter's pages")
                assertEquals(listOf(chapters[1], chapters[2]), fixture.pages.requested.map { it.second })
                assertEquals(listOf(fixture.manga to chapters[1].url), fixture.markRead.marked)

                fixture.dispatch(ReaderIntent.OnAppendNextChapter)
                assertEquals(appended.pages, fixture.vm.state.value.pages)
                assertEquals(2, fixture.pages.requested.size, "the oldest chapter has no further append target")
                assertSource(fixture, fromSaved)
            }
        }

    private fun fixture(fromSaved: Boolean): ReaderActiveActionFixture =
        ReaderActiveActionFixture(dispatcher.scheduler, chapters = chapters).also {
            fixtures += it
            if (fromSaved) it.savedDetails.value = activeActionDetails(it.manga, chapters)
            it.dispatch(ReaderIntent.OnEnter(it.manga, chapters[1]))
            assertEquals(listOf("18", "17", "16"), it.vm.state.value.chapters.map(Chapter::number))
            assertEquals(1, it.vm.state.value.currentChapterIndex)
            assertSource(it, fromSaved)
        }

    private fun assertReplacement(
        fixture: ReaderActiveActionFixture,
        target: Chapter,
    ) {
        val state = fixture.vm.state.value
        assertEquals(target, state.chapter)
        assertEquals(target.url, state.activeChapterUrl)
        assertEquals(chapters.indexOf(target), state.currentChapterIndex)
        assertEquals(listOf(target.url), state.loadedChapterUrls)
        assertEquals(activeActionPages(target), state.pages)
    }

    private fun assertSource(
        fixture: ReaderActiveActionFixture,
        fromSaved: Boolean,
    ) {
        assertEquals(if (fromSaved) emptyList() else listOf(fixture.manga), fixture.details.requested)
    }
}
