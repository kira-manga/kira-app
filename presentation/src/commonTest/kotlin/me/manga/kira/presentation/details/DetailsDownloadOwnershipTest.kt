package me.manga.kira.presentation.details

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.testing.sampleManga
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsDownloadOwnershipTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val mangaA = sampleManga(api = "source", title = "Same title", url = "https://owner.test/a")
    private val mangaB = mangaA.copy(url = "https://owner.test/b")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun sharedChapterUrlUsesOnlyBForProgressCompletionRunningCancelAndCloudflareRetry() =
        runTest {
            val fixture = fixture()
            val effects = mutableListOf<DetailsEffect>()
            backgroundScope.launch(dispatcher) { fixture.vm.effects.collect { effects += it } }
            val b = download(202, 2, DownloadState.RUNNING, progress = 37)
            val a = download(101, 1, DownloadState.SUCCESS, sizeBytes = 90_000)
            // A is deliberately last in the global URL map, but is not a download owned by this screen.
            fixture.downloads.publish(mangaB, listOf(b))
            fixture.downloads.publish(mangaA, listOf(a))
            fixture.vm.submit(DetailsIntent.OnEnter(mangaB))
            fixture.downloads.publish(mangaB, listOf(b))

            val progress =
                fixture.vm.state.value.chapterDownloads
                    .getValue(CHAPTER_URL)
            assertEquals(202L, progress.chapterId)
            assertEquals(2L, progress.mangaId)
            assertEquals(37, progress.progress)
            assertEquals(0, fixture.vm.state.value.downloadedChapterCount)
            fixture.vm.submit(DetailsIntent.OnCancelChapterDownload(sharedChapter()))
            assertEquals(listOf(202L to 2L), fixture.actions.runningCancelled)
            assertTrue(fixture.actions.queuedCancelled.isEmpty())

            fixture.downloads.publish(mangaB, listOf(b.copy(state = DownloadState.QUEUED)))
            fixture.vm.submit(DetailsIntent.OnCancelChapterDownload(sharedChapter()))
            assertEquals(listOf(202L), fixture.actions.queuedCancelled)
            assertEquals(listOf(mangaB to CHAPTER_URL), fixture.resolver.singleRequests)
            fixture.downloads.publish(mangaB, listOf(b.copy(state = DownloadState.SUCCESS, sizeBytes = 4_096)))
            assertEquals(1, fixture.vm.state.value.downloadedChapterCount)
            assertEquals(
                4_096L,
                fixture.vm.state.value.chapterDownloads
                    .getValue(CHAPTER_URL)
                    .sizeBytes,
            )

            val failed =
                b.copy(
                    state = DownloadState.FAILED,
                    errorMsg = DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL,
                )
            fixture.downloads.publish(mangaA, listOf(a.copy(state = failed.state, errorMsg = failed.errorMsg)))
            assertTrue(effects.isEmpty(), "A's challenge must not open a solver for B")
            fixture.downloads.publish(mangaB, listOf(failed))
            assertEquals(
                listOf<DetailsEffect>(DetailsEffect.SolveCloudflareChallenge(mangaB.url, mangaB.api)),
                effects,
            )
            assertTrue(
                fixture.vm.state.value.chapterDownloads
                    .isEmpty(),
                "FAILED is not active or completed",
            )
            fixture.vm.submit(DetailsIntent.OnRetry)
            assertEquals(listOf(Triple(202L, mangaB.title, mangaB.api)), fixture.actions.enqueued)
            assertTrue(fixture.resolver.singleRequests.all { it.first == mangaB })
            assertEquals(0, fixture.downloads.globalSubscriptions)
            assertFalse(effects.any { it is DetailsEffect.ShowError })
        }

    @Test
    fun exactUrlReentryCancelsOldStreamClearsCacheAndDoesNotCarryCloudflareWorkToTheNewOwner() =
        runTest {
            val fixture = fixture()
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            fixture.downloads.publish(mangaA, listOf(download(101, 1, DownloadState.RUNNING)))
            assertEquals(
                101L,
                fixture.vm.state.value.chapterDownloads
                    .getValue(CHAPTER_URL)
                    .chapterId,
            )

            // Metadata is identical: the exact parent URL must still establish a new owner.
            fixture.vm.submit(DetailsIntent.OnEnter(mangaB))
            assertEquals(
                mangaB.url,
                fixture.vm.state.value.manga
                    ?.url,
            )
            assertTrue(mangaA.url in fixture.downloads.cancelledOwners)
            assertTrue(fixture.downloads.scopedOwners.any { it.url == mangaB.url })
            assertTrue(
                fixture.vm.state.value.chapterDownloads
                    .isEmpty(),
                "clear A before B's first queue emission",
            )
            fixture.downloads.publish(mangaA, listOf(download(101, 1, DownloadState.SUCCESS, sizeBytes = 90_000)))
            assertTrue(
                fixture.vm.state.value.chapterDownloads
                    .isEmpty(),
                "late A emissions cannot repaint B",
            )

            fixture.downloads.publish(
                mangaB,
                listOf(
                    download(202, 2, DownloadState.FAILED)
                        .copy(errorMsg = DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL),
                ),
            )
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            assertTrue(mangaB.url in fixture.downloads.cancelledOwners)
            assertTrue(
                fixture.vm.state.value.chapterDownloads
                    .isEmpty(),
            )
            fixture.vm.submit(DetailsIntent.OnRetry)
            assertTrue(fixture.actions.enqueued.isEmpty(), "B's pending challenge retry must not become an A enqueue")
            fixture.downloads.publish(mangaB, listOf(download(202, 2, DownloadState.RUNNING)))
            assertTrue(
                fixture.vm.state.value.chapterDownloads
                    .isEmpty(),
            )
            fixture.downloads.publish(mangaA, listOf(download(303, 3, DownloadState.SUCCESS, sizeBytes = 2_048)))
            val reentered =
                fixture.vm.state.value.chapterDownloads
                    .getValue(CHAPTER_URL)
            assertEquals(303L, reentered.chapterId)
            assertEquals(3L, reentered.mangaId)
            assertEquals(2_048L, reentered.sizeBytes)
            assertEquals(0, fixture.downloads.globalSubscriptions)
        }

    @Test
    fun selectedActionsCaptureOwnerBeforeLaunchAndChapterDeletionRequiresSuccessfulFileDeletion() =
        runTest {
            val queued = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(queued)
            val second = sharedChapter().copy(number = "2", url = SECOND_CHAPTER_URL)
            val fixture = fixture(queued, listOf(sharedChapter(), second))
            val vm = fixture.vm
            vm.submit(DetailsIntent.OnEnter(mangaA))
            runCurrent()
            vm.submit(DetailsIntent.OnChapterLongClick(sharedChapter()))
            vm.submit(DetailsIntent.OnSelectionToggle(second))
            runCurrent()

            // The handler runs for A, but its launchSafely body runs after the B entry handler.
            vm.submit(DetailsIntent.OnDownloadSelected)
            vm.submit(DetailsIntent.OnEnter(mangaB))
            runCurrent()
            assertEquals(
                setOf(101L, 102L),
                fixture.actions.enqueued
                    .map { it.first }
                    .toSet(),
            )
            assertEquals(2, fixture.actions.enqueued.size)
            val downloadRequest = fixture.resolver.bulkRequests.single()
            assertEquals(mangaA, downloadRequest.first)
            assertEquals(setOf(CHAPTER_URL, SECOND_CHAPTER_URL), downloadRequest.second.toSet())
            assertTrue(fixture.resolver.singleRequests.isEmpty(), "selected URLs must resolve as one scoped batch")

            fixture.chapters = fixture.chapters.map { it.copy(isDownloaded = true) }
            vm.submit(DetailsIntent.OnEnter(mangaA))
            vm.submit(DetailsIntent.OnSelectionClear)
            vm.submit(DetailsIntent.OnChapterLongClick(sharedChapter()))
            vm.submit(DetailsIntent.OnSelectionToggle(second))
            runCurrent()
            vm.submit(DetailsIntent.OnDeleteSelectedDownloads)
            vm.submit(DetailsIntent.OnEnter(mangaB))
            runCurrent()
            assertEquals(setOf(101L, 102L), fixture.actions.fileDeleteAttempts.toSet())
            assertEquals(2, fixture.actions.fileDeleteAttempts.size)
            assertEquals(2, fixture.resolver.bulkRequests.size)
            assertTrue(fixture.resolver.bulkRequests.all { it.first == mangaA })
            assertTrue(fixture.deletedChapters.isEmpty(), "download deletion must not delete chapter rows")

            vm.submit(DetailsIntent.OnEnter(mangaA))
            runCurrent()
            fixture.actions.failingDeletes += 101L
            vm.submit(DetailsIntent.OnDeleteChapter(sharedChapter()))
            vm.submit(DetailsIntent.OnEnter(mangaB))
            runCurrent()
            assertEquals(101L, fixture.actions.fileDeleteAttempts.last())
            assertTrue(fixture.deletedChapters.isEmpty(), "failed file deletion must preserve A's chapter row")

            vm.submit(DetailsIntent.OnDeleteChapter(sharedChapter()))
            vm.submit(DetailsIntent.OnEnter(mangaA))
            runCurrent()
            assertEquals(listOf(202L), fixture.deletedChapters)
            assertEquals(listOf("files:202", "row:202"), fixture.actions.deleteOrder.takeLast(2))
        }

    private fun fixture(
        testDispatcher: CoroutineDispatcher = dispatcher,
        chapters: List<Chapter> = listOf(sharedChapter()),
    ): DetailsOwnerFixture =
        DetailsOwnerFixture(
            idsByMangaUrl = mapOf(mangaA.url to 101L, mangaB.url to 202L),
            dispatcher = testDispatcher,
            chapters = chapters,
        ).also { store.put("details", it.vm) }
}
