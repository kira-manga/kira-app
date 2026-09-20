package me.manga.kira.presentation.details

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.Chapter
import me.manga.kira.presentation.testing.sampleManga
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Paired consumer paths; the real saved projection is covered separately by SavedChapterOrderTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelSavedChapterOrderTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val manga = sampleManga(title = "Order", url = "https://order.test/manga")
    private val chapters =
        listOf("18", "17", "16").map { number ->
            Chapter(number, "Chapter $number", "chapter/$number", null, false, false)
        }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun networkAndSavedOpenShareDefaultAscendingAndResume() =
        runTest(dispatcher) {
            for (fromSaved in listOf(false, true)) {
                val fixture = fixture(fromSaved)
                assertEquals(fromSaved, fixture.vm.state.value.isInLibrary)
                assertEquals(ChapterSortType.ID, fixture.vm.state.value.chapterSort)
                assertFalse(fixture.vm.state.value.sortAscending)
                assertOrderAndResume(fixture, listOf("18", "17", "16"))

                fixture.vm.submit(DetailsIntent.OnToggleSortDirection)
                runCurrent()

                assertTrue(fixture.vm.state.value.sortAscending)
                assertOrderAndResume(fixture, listOf("16", "17", "18"))
                assertEquals(if (fromSaved) emptyList() else listOf(manga), fixture.fetchRequests)
            }
        }

    private fun fixture(fromSaved: Boolean): DetailsOwnerFixture =
        DetailsOwnerFixture(emptyMap(), dispatcher, chapters).also {
            store.put("details-$fromSaved", it.vm)
            if (fromSaved) it.savedDetails.value = detailsFor(manga, chapters)
            it.vm.submit(DetailsIntent.OnEnter(manga))
            dispatcher.scheduler.runCurrent()
        }

    private suspend fun assertOrderAndResume(
        fixture: DetailsOwnerFixture,
        expectedNumbers: List<String>,
    ) {
        val state = fixture.assertLoadedOwner(manga)
        assertEquals(expectedNumbers, state.displayChapters.map(Chapter::number))
        val resume = assertNotNull(state.firstUnreadChapter)
        assertEquals("chapter/16", resume.url, "Resume stays on the oldest unread chapter in either direction")
        fixture.vm.submit(DetailsIntent.OnChapterClick(resume))
        dispatcher.scheduler.runCurrent()
        assertEquals(
            DetailsEffect.NavigateToReader(manga, chapters.last()),
            assertIs<DetailsEffect.NavigateToReader>(fixture.vm.effects.first()),
        )
    }
}
