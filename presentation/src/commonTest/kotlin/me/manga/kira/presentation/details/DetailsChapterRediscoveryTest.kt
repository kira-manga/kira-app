package me.manga.kira.presentation.details

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsChapterRediscoveryTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val mangaA = sampleManga(api = "source", title = "Same title", url = "https://owner.test/a")
    private val mangaB = mangaA.copy(url = "https://owner.test/b")
    private val first = sharedChapter()
    private val second = first.copy(number = "2", url = SECOND_CHAPTER_URL, isRead = true)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun emptyRefreshRetainsLastKnownGoodWithoutRestoringTheDeletedChapter() =
        runTest {
            val fixture = fixture(listOf(first, second))
            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            fixture.chapters = emptyList()

            fixture.vm.submit(DetailsIntent.OnRetry)
            assertEquals(listOf(second), loadedChapters(fixture))
            assertEquals(1, fixture.fetchRequests.size)
            fixture.savedDetails.value = detailsFor(mangaA, listOf(first.copy(isNew = true), second))
            assertEquals(listOf(second), loadedChapters(fixture))
        }

    @Test
    fun onlyARefreshThatContainsTheDeletedUrlReleasesItsRetraction() =
        runTest {
            val fixture = fixture(listOf(first))
            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            fixture.chapters = listOf(second)
            fixture.vm.submit(DetailsIntent.OnRetry)
            assertEquals(listOf(second), loadedChapters(fixture))
            fixture.savedDetails.value = detailsFor(mangaA, listOf(first, second))
            assertEquals(listOf(second), loadedChapters(fixture))

            fixture.chapters = listOf(first, second)
            fixture.vm.submit(DetailsIntent.OnRetry)
            assertEquals(listOf(first, second), loadedChapters(fixture))
            assertEquals(listOf(first, second), fixture.library.lastPersistedNewChapters)
            fixture.savedDetails.value = detailsFor(mangaA, listOf(first.copy(isRead = true), second))
            assertTrue(loadedChapters(fixture).first().isRead, "rediscovered chapters resume saved-state overlays")
        }

    @Test
    fun deletingLastRowSurvivesEmptyRefreshThenAllowsNonemptyRediscovery() =
        runTest {
            val fixture = fixture(listOf(first))
            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            fixture.chapters = emptyList()
            fixture.vm.submit(DetailsIntent.OnRetry)
            fixture.savedDetails.value = detailsFor(mangaA, listOf(first.copy(isRead = true)))
            assertTrue(loadedChapters(fixture).isEmpty())

            fixture.chapters = listOf(first)
            fixture.vm.submit(DetailsIntent.OnRetry)
            assertEquals(listOf(first), loadedChapters(fixture))
            assertEquals(listOf(first), fixture.library.lastPersistedNewChapters)
        }

    @Test
    fun inFlightRefreshStartedBeforeDeletionCannotRestoreOrRepersistItsChapter() =
        runTest {
            val fixture = fixture(listOf(first, second))
            val fetching = CompletableDeferred<Unit>().also { fixture.fetchGate = it }
            fixture.vm.submit(DetailsIntent.OnRetry)
            assertTrue(fixture.vm.state.value.isLoading)
            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            assertEquals(listOf(second), loadedChapters(fixture))

            fetching.complete(Unit)
            runCurrent()

            assertEquals(listOf(second), loadedChapters(fixture))
            assertEquals(listOf(second), fixture.library.lastPersistedNewChapters)
            fixture.savedDetails.value = detailsFor(mangaA, listOf(first.copy(isRead = true), second))
            assertEquals(listOf(second), loadedChapters(fixture))
            fixture.vm.submit(DetailsIntent.OnRetry)
            assertEquals(listOf(first, second), loadedChapters(fixture))
        }

    @Test
    fun reentryAllowsRediscoveryButAnEarlierRefreshCannotUndoANewerDeletion() =
        runTest {
            val fixture = fixture(listOf(first))
            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            assertTrue(loadedChapters(fixture).isEmpty())
            assertTrue(fixture.fetchRequests.isEmpty())
            val earlierRefresh = CompletableDeferred<Unit>().also { fixture.fetchGate = it }
            fixture.vm.submit(DetailsIntent.OnRetry)
            fixture.fetchGate = null

            // The saved chapter really is gone. The next visit has no cache, so it can fetch anew.
            fixture.savedDetails.value = null
            fixture.vm.submit(DetailsIntent.OnEnter(mangaB))
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            assertEquals(listOf(first), loadedChapters(fixture))
            assertEquals(listOf(mangaA.url, mangaB.url, mangaA.url), fixture.fetchRequests.map { it.url })
            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            assertTrue(loadedChapters(fixture).isEmpty())
            earlierRefresh.complete(Unit)
            runCurrent()
            assertTrue(loadedChapters(fixture).isEmpty(), "the first visit's refresh cannot undo the second deletion")
        }

    private fun loadedChapters(fixture: DetailsOwnerFixture): List<Chapter> =
        assertNotNull(fixture.vm.state.value.details).chapters

    private fun fixture(chapters: List<Chapter>): DetailsOwnerFixture =
        DetailsOwnerFixture(mapOf(mangaA.url to 101L, mangaB.url to 202L), dispatcher, chapters).also {
            store.put("details", it.vm)
            it.savedDetails.value = detailsFor(mangaA, chapters)
            it.onRowDeleted = { _ ->
                it.savedDetails.value = detailsFor(mangaA, chapters.filterNot { chapter -> chapter.url == CHAPTER_URL })
            }
            it.vm.submit(DetailsIntent.OnEnter(mangaA))
        }
}
