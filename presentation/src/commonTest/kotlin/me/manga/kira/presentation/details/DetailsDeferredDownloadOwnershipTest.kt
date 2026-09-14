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
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.presentation.testing.sampleManga
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsDeferredDownloadOwnershipTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val mangaA = sampleManga(api = "source", title = "Same title", url = "https://owner.test/a")
    private val mangaB = mangaA.copy(url = "https://owner.test/b")
    private val second = sharedChapter().copy(number = "2", url = SECOND_CHAPTER_URL)
    private val urls = listOf(CHAPTER_URL, SECOND_CHAPTER_URL)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun pendingAActionsIgnoreLoadedBCompletionAcrossResolution() =
        runTest {
            EnqueueKind.entries.forEach { kind ->
                val fixture = fixture()
                val b = if (kind == EnqueueKind.ALL) mangaB.copy(api = "source-b", title = "B title") else mangaB
                val savedB = kind != EnqueueKind.SELECTED
                fixture.chaptersByMangaUrl[b.url] = listOf(sharedChapter().copy(isDownloaded = savedB))
                fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
                assertFalse(fixture.assertLoadedOwner(mangaA).isChapterDownloaded(CHAPTER_URL))
                val gate = fixture.pauseResolution()
                enqueue(fixture, kind)
                assertResolutionPaused(fixture, kind)
                fixture.vm.submit(DetailsIntent.OnEnter(b))
                if (!savedB) fixture.downloads.publish(b, listOf(download(202, 2, DownloadState.SUCCESS)))
                val landed = fixture.assertLoadedOwner(b)
                assertTrue(landed.isChapterDownloaded(CHAPTER_URL), "B must really be complete before releasing A")
                assertEquals(
                    savedB,
                    landed.details
                        ?.chapters
                        ?.single()
                        ?.isDownloaded,
                )
                assertEquals(b.title, landed.details?.title)
                assertEquals(b.api, landed.details?.api)
                gate.complete(Unit)
                runCurrent()
                fixture.assertEnqueued(mangaA, if (kind == EnqueueKind.SINGLE) listOf(101L) else listOf(101L, 102L))
                assertEquals(0, fixture.downloads.globalSubscriptions)
            }
        }

    @Test
    fun capturedSuccessStillSkipsEnqueueAndDeletesAAfterBLoadsPending() =
        runTest {
            val fixture = fixture()
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            fixture.downloads.publish(mangaA, listOf(download(101, 1, DownloadState.SUCCESS)))
            val a = fixture.assertLoadedOwner(mangaA)
            assertTrue(a.isChapterDownloaded(CHAPTER_URL))
            assertFalse(assertNotNull(a.details).chapters.first().isDownloaded, "live SUCCESS precedes saved completion")
            val gate = fixture.pauseResolution()
            enqueueAllKinds(fixture)
            fixture.select(listOf(sharedChapter()))
            fixture.vm.submit(DetailsIntent.OnDeleteSelectedDownloads)
            fixture.vm.submit(DetailsIntent.OnDeleteAllDownloads)
            fixture.assertResolution(
                mangaA,
                listOf(CHAPTER_URL),
                listOf(listOf(SECOND_CHAPTER_URL), urls, listOf(CHAPTER_URL), listOf(CHAPTER_URL)),
            )
            fixture.assertEnqueued(mangaA, emptyList())
            assertTrue(fixture.actions.fileDeleteAttempts.isEmpty())
            fixture.vm.submit(DetailsIntent.OnEnter(mangaB))
            fixture.downloads.publish(mangaB, emptyList())
            assertFalse(fixture.assertLoadedOwner(mangaB).isChapterDownloaded(CHAPTER_URL))
            gate.complete(Unit)
            runCurrent()
            fixture.assertEnqueued(mangaA, listOf(102L, 102L))
            assertEquals(listOf(101L, 101L), fixture.actions.fileDeleteAttempts)
            assertTrue(fixture.deletedChapters.isEmpty())
        }

    @Test
    fun lateSuccessDuringLoadedRefreshIsRecheckedAfterSingleSelectedAndBulkResolution() =
        runTest {
            val fixture = fixture()
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            val gate = fixture.pauseResolution()
            enqueueAllKinds(fixture)
            fixture.assertResolution(mangaA, listOf(CHAPTER_URL), listOf(urls, urls))
            fixture.assertEnqueued(mangaA, emptyList())
            val refresh = CompletableDeferred<Unit>()
            fixture.fetchGate = refresh
            fixture.vm.submit(DetailsIntent.OnRetry)
            fixture.downloads.publish(mangaA, listOf(download(101, 1, DownloadState.SUCCESS)))
            val latest = fixture.vm.state.value
            assertEquals(mangaA.url, latest.manga?.url)
            assertTrue(latest.isLoading, "cached details remain authoritative during a refresh")
            assertFalse(assertNotNull(latest.details).chapters.first().isDownloaded)
            assertTrue(latest.isChapterDownloaded(CHAPTER_URL))
            gate.complete(Unit)
            runCurrent()
            fixture.assertEnqueued(mangaA, listOf(102L, 102L))
            refresh.complete(Unit)
        }

    @Test
    fun fullDeletionAfterResolutionStartsPermitsSingleAndAllDespiteCapturedTrueFlags() =
        runTest {
            val downloaded = sharedChapter().copy(isDownloaded = true)
            val fixture = fixture(listOf(downloaded))
            fixture.savedDetails.value = detailsFor(mangaA, listOf(downloaded))
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            fixture.downloads.publish(mangaA, listOf(download(101, 1, DownloadState.SUCCESS)))
            assertTrue(fixture.assertLoadedOwner(mangaA).isChapterDownloaded(CHAPTER_URL))
            val gate = fixture.pauseResolution()
            fixture.vm.submit(DetailsIntent.OnDownloadChapter(downloaded))
            fixture.vm.submit(DetailsIntent.OnDownloadAllClick)
            fixture.assertResolution(mangaA, listOf(CHAPTER_URL), listOf(listOf(CHAPTER_URL)))
            fixture.assertEnqueued(mangaA, emptyList())
            fixture.savedDetails.value = detailsFor(mangaA, listOf(sharedChapter()))
            fixture.downloads.publish(mangaA, emptyList())
            val deleted = fixture.assertLoadedOwner(mangaA)
            assertFalse(assertNotNull(deleted.details).chapters.single().isDownloaded)
            assertTrue(deleted.chapterDownloads.isEmpty())
            assertFalse(deleted.isChapterDownloaded(CHAPTER_URL))
            gate.complete(Unit)
            runCurrent()
            fixture.assertEnqueued(mangaA, listOf(101L, 101L))
        }

    @Test
    fun fullDeletionDuringResolutionPreventsStaleSelectedAndAllDeletes() =
        runTest {
            val fixture = fixture()
            fixture.savedDetails.value = detailsFor(mangaA, listOf(sharedChapter().copy(isDownloaded = true), second))
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            fixture.downloads.publish(mangaA, listOf(download(101, 1, DownloadState.SUCCESS)))
            val gate = fixture.pauseResolution()
            fixture.select(listOf(sharedChapter()))
            fixture.vm.submit(DetailsIntent.OnDeleteSelectedDownloads)
            fixture.vm.submit(DetailsIntent.OnDeleteAllDownloads)
            fixture.assertResolution(mangaA, emptyList(), listOf(listOf(CHAPTER_URL), listOf(CHAPTER_URL)))
            assertTrue(fixture.actions.fileDeleteAttempts.isEmpty())
            fixture.savedDetails.value = detailsFor(mangaA, fixture.chapters)
            fixture.downloads.publish(mangaA, emptyList())
            assertFalse(fixture.assertLoadedOwner(mangaA).isChapterDownloaded(CHAPTER_URL))
            gate.complete(Unit)
            runCurrent()
            assertTrue(fixture.actions.fileDeleteAttempts.isEmpty(), "completion was removed while resolution waited")
            assertTrue(fixture.deletedChapters.isEmpty())
        }

    @Test
    fun bulkRechecksTheNextChapterAfterThePreviousEnqueueSuspends() =
        runTest {
            val fixture = fixture()
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            val firstEnqueue = CompletableDeferred<Unit>()
            fixture.actions.enqueueGates[101L] = firstEnqueue
            fixture.vm.submit(DetailsIntent.OnDownloadAllClick)
            fixture.assertResolution(mangaA, emptyList(), listOf(urls))
            fixture.assertEnqueued(mangaA, listOf(101L))
            fixture.downloads.publish(
                mangaA,
                listOf(download(102, 1, DownloadState.SUCCESS).copy(url = SECOND_CHAPTER_URL)),
            )
            assertTrue(fixture.assertLoadedOwner(mangaA).isChapterDownloaded(SECOND_CHAPTER_URL))
            firstEnqueue.complete(Unit)
            runCurrent()
            fixture.assertEnqueued(mangaA, listOf(101L))
        }

    @Test
    fun bulkDefaultPredicateStillSkipsRawDownloadedAndUnresolvedChapters() =
        runTest {
            val chapters = listOf(sharedChapter().copy(isDownloaded = true), second, sharedChapter().copy(url = "missing"))
            val fixture = fixture(chapters)
            assertTrue(fixture.enqueueAll(mangaA, detailsFor(mangaA, chapters)).isSuccess)
            fixture.assertResolution(mangaA, emptyList(), listOf(chapters.map { it.url }))
            fixture.assertEnqueued(mangaA, listOf(102L))
        }

    @Test
    fun sameOwnerLoadingShellCannotEraseCapturedCompletionForDeferredActions() =
        runTest {
            val fixture = fixture()
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            fixture.downloads.publish(mangaA, listOf(download(101, 1, DownloadState.SUCCESS)))
            val gate = fixture.pauseResolution()
            fixture.vm.submit(DetailsIntent.OnDownloadChapter(sharedChapter()))
            fixture.vm.submit(DetailsIntent.OnDownloadAllClick)
            fixture.assertResolution(mangaA, listOf(CHAPTER_URL), listOf(urls))
            fixture.vm.submit(DetailsIntent.OnEnter(mangaB))
            fixture.assertLoadedOwner(mangaB)
            val reload = CompletableDeferred<Unit>()
            fixture.fetchGate = reload
            fixture.vm.submit(DetailsIntent.OnEnter(mangaA))
            val shell = fixture.vm.state.value
            assertEquals(mangaA.url, shell.manga?.url)
            assertTrue(shell.isLoading)
            assertNull(shell.details)
            gate.complete(Unit)
            runCurrent()
            fixture.assertEnqueued(mangaA, listOf(102L))
            reload.complete(Unit)
        }

    private fun enqueueAllKinds(fixture: DetailsOwnerFixture) = EnqueueKind.entries.forEach { enqueue(fixture, it) }

    private fun enqueue(
        fixture: DetailsOwnerFixture,
        kind: EnqueueKind,
    ) {
        when (kind) {
            EnqueueKind.SINGLE -> fixture.vm.submit(DetailsIntent.OnDownloadChapter(sharedChapter()))
            EnqueueKind.SELECTED -> {
                fixture.select()
                fixture.vm.submit(DetailsIntent.OnDownloadSelected)
            }
            EnqueueKind.ALL -> fixture.vm.submit(DetailsIntent.OnDownloadAllClick)
        }
    }

    private fun assertResolutionPaused(
        fixture: DetailsOwnerFixture,
        kind: EnqueueKind,
    ) {
        val single = if (kind == EnqueueKind.SINGLE) listOf(CHAPTER_URL) else emptyList()
        val bulk = if (kind == EnqueueKind.SINGLE) emptyList() else listOf(urls)
        fixture.assertResolution(mangaA, single, bulk)
        fixture.assertEnqueued(mangaA, emptyList())
    }

    private fun fixture(chapters: List<Chapter> = listOf(sharedChapter(), second)): DetailsOwnerFixture =
        DetailsOwnerFixture(
            idsByMangaUrl = mapOf(mangaA.url to 101L, mangaB.url to 202L),
            dispatcher = dispatcher,
            chapters = chapters,
        ).also { store.put("details", it.vm) }
}

private enum class EnqueueKind { SINGLE, SELECTED, ALL }
