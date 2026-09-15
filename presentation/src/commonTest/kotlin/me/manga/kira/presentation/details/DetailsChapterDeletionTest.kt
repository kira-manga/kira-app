package me.manga.kira.presentation.details

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.presentation.testing.sampleManga
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsChapterDeletionTest {
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
    fun successRetractsOnlyDeletedChapterAfterReducedSavedSnapshot() =
        runTest {
            val fixture = cachedFixture()
            fixture.select()
            fixture.downloads.publish(mangaA, listOf(download(101, 1, DownloadState.SUCCESS)))
            fixture.onRowDeleted = {
                // Room can publish the reduced projection before its suspend delete returns.
                fixture.savedDetails.value = detailsFor(mangaA, listOf(second))
            }

            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))

            assertEquals(listOf("files:101", "row:101"), fixture.actions.deleteOrder)
            assertEquals(listOf(101L), fixture.deletedChapters)
            val current = fixture.assertLoadedOwner(mangaA)
            assertEquals(listOf(second), assertNotNull(current.details).chapters)
            assertEquals(setOf(SECOND_CHAPTER_URL), current.selectedChapterUrls)
            assertTrue(current.chapterDownloads.isEmpty(), "the deleted row must lose live progress too")
            assertTrue(fixture.fetchRequests.isEmpty(), "deletion must not turn a cache-only open into a fetch")

            fixture.savedDetails.value = detailsFor(mangaA, listOf(first, second.copy(isRead = false)))
            assertEquals(listOf(second.copy(isRead = false)), assertNotNull(fixture.vm.state.value.details).chapters)
        }

    @Test
    fun lastRowDeletionStaysEmptyAcrossEmptyAndStaleSavedSnapshots() =
        runTest {
            val fixture = cachedFixture(listOf(first))
            fixture.select()
            fixture.onRowDeleted = { fixture.savedDetails.value = detailsFor(mangaA, emptyList()) }

            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            assertEmptySelectionAndChapters(fixture)
            fixture.savedDetails.value = detailsFor(mangaA, listOf(first))
            assertEmptySelectionAndChapters(fixture)
            fixture.savedDetails.value = detailsFor(mangaA, emptyList())
            assertEmptySelectionAndChapters(fixture)
            fixture.savedDetails.value = detailsFor(mangaA, listOf(first.copy(isBookmarked = true)))
            assertEmptySelectionAndChapters(fixture)

            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            assertEmptySelectionAndChapters(fixture)
            assertTrue(fixture.fetchRequests.isEmpty(), "same-identity intent replay is not rediscovery")
        }

    @Test
    fun cleanupFailureKeepsChapterAndSelectionAndSkipsRowDeletion() =
        runTest {
            val fixture = cachedFixture(listOf(first))
            fixture.select()
            fixture.actions.failingDeletes += 101L
            val effects = mutableListOf<DetailsEffect>()
            backgroundScope.launch(dispatcher) { fixture.vm.effects.collect { effects += it } }

            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))

            assertEquals(listOf("files:101"), fixture.actions.deleteOrder)
            assertTrue(fixture.deletedChapters.isEmpty())
            assertRetained(fixture)
            assertIs<AppError.Unexpected>(assertIs<DetailsEffect.ShowError>(effects.single()).error)
        }

    @Test
    fun rowDeletionFailureDoesNotRetractTheChapterOrSelection() =
        runTest {
            val fixture = cachedFixture(listOf(first))
            fixture.select()
            fixture.failingRowDeletes += 101L

            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))

            assertEquals(listOf("files:101", "row:101"), fixture.actions.deleteOrder)
            assertTrue(fixture.deletedChapters.isEmpty())
            assertRetained(fixture)
        }

    @Test
    fun pendingResolutionAndRowDeletionAreNotOptimisticRemoval() =
        runTest {
            val fixture = cachedFixture(listOf(first))
            fixture.select()
            val resolution = fixture.pauseResolution()
            val deletion = CompletableDeferred<Unit>().also { fixture.deleteGate = it }
            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            assertTrue(fixture.actions.deleteOrder.isEmpty())
            assertRetained(fixture)

            resolution.complete(Unit)
            runCurrent()
            assertEquals(listOf("files:101", "row:101"), fixture.actions.deleteOrder)
            assertTrue(fixture.deletedChapters.isEmpty())
            assertRetained(fixture)

            deletion.complete(Unit)
            runCurrent()
            assertEquals(listOf(101L), fixture.deletedChapters)
            assertEmptySelectionAndChapters(fixture)
        }

    @Test
    fun delayedADeletionNeverPrunesLoadedBWithTheSameMetadataAndChapterUrl() =
        runTest {
            val fixture = fixture(listOf(first))
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            val deletion = CompletableDeferred<Unit>().also { fixture.deleteGate = it }
            fixture.vm.submit(DetailsIntent.OnDeleteChapter(first))
            assertEquals(listOf("files:101", "row:101"), fixture.actions.deleteOrder)

            fixture.vm.submit(DetailsIntent.OnEnter(mangaB))
            fixture.select()
            fixture.downloads.publish(mangaB, listOf(download(202, 2, DownloadState.SUCCESS)))
            val before = fixture.assertLoadedOwner(mangaB)
            deletion.complete(Unit)
            runCurrent()

            assertEquals(listOf(101L), fixture.deletedChapters)
            assertEquals(before, fixture.vm.state.value, "A completion cannot alter any of B's presentation")
            fixture.assertResolution(mangaA, listOf(CHAPTER_URL), emptyList())
        }

    private fun assertRetained(fixture: DetailsOwnerFixture) {
        val current = fixture.assertLoadedOwner(mangaA)
        assertEquals(listOf(first), assertNotNull(current.details).chapters)
        assertEquals(setOf(CHAPTER_URL), current.selectedChapterUrls)
    }

    private fun assertEmptySelectionAndChapters(fixture: DetailsOwnerFixture) {
        val current = fixture.assertLoadedOwner(mangaA)
        assertTrue(assertNotNull(current.details).chapters.isEmpty())
        assertTrue(current.selectedChapterUrls.isEmpty())
    }

    private fun cachedFixture(chapters: List<Chapter> = listOf(first, second)): DetailsOwnerFixture =
        fixture(chapters).also {
            it.savedDetails.value = detailsFor(mangaA, chapters)
            it.vm.submit(DetailsIntent.OnEnter(mangaA))
        }

    private fun fixture(chapters: List<Chapter>): DetailsOwnerFixture =
        DetailsOwnerFixture(mapOf(mangaA.url to 101L, mangaB.url to 202L), dispatcher, chapters)
            .also { store.put("details", it.vm) }
}
