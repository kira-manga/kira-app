package me.manga.kira.presentation.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerPage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelActiveNavigationTest {
    private val dispatcher = StandardTestDispatcher()
    private val fixtures = mutableListOf<ReaderActiveActionFixture>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun previousFromAppendedChapterReplacesAnchorAndRebindsChapterWork() =
        runTest(dispatcher) {
            val env = fixture()
            val (anchor, active) = env.chapters
            env.enterAndAppend()
            env.dispatch(ReaderIntent.OnPageChanged(3))
            assertEquals(listOf(active.url to 1), env.resume.saved)
            env.resume.positions[anchor.url] = 99
            val oldUrls =
                env.vm.state.value.pages
                    .map { it.url }
            env.progress.report(oldUrls.last(), PageDownloadProgress.Complete)
            runCurrent()
            assertEquals(mapOf(oldUrls.last() to PageDownloadProgress.Complete), env.vm.state.value.pageProgress)
            val cancellations = env.progress.cancelled.size

            env.dispatch(ReaderIntent.OnPrevChapter)

            assertOnlyChapter(env, anchor)
            assertEquals(1, env.vm.state.value.currentPageIndex, "saved position clamps to the new page count")
            assertEquals(listOf(anchor.url, anchor.url), env.resume.loaded)
            assertEquals(listOf(anchor, active, anchor), env.pages.requested.map { it.second })
            assertEquals(listOf(env.manga to active), env.pages.cleared, "retain only the target's extracted pages")
            assertEquals(listOf(env.manga to anchor.url, env.manga to active.url), env.markRead.marked)
            assertEquals(listOf(env.manga to anchor.url, env.manga to active.url, env.manga to anchor.url), env.bookmark.observed)
            assertEquals(listOf(anchor.url, active.url, anchor.url), env.history.recorded.map { it.second })
            assertEquals(
                oldUrls.sorted(),
                env.progress.cancelled
                    .drop(cancellations)
                    .sorted(),
            )
            assertEquals(listOf(env.manga), env.details.requested, "same-manga replacement retains the chapter list")
        }

    @Test
    fun previousAndNextChooseTheActiveNeighborEvenWhenItIsAlreadyLoaded() =
        runTest(dispatcher) {
            val cases =
                listOf(
                    Triple(ReaderIntent.OnPrevChapter, 2, 1),
                    Triple(ReaderIntent.OnNextChapter, 1, 2),
                )
            for ((intent, activeIndex, targetIndex) in cases) {
                val env = fixture()
                env.enterAndAppend(count = 2)
                val active = env.chapters[activeIndex]
                val target = env.chapters[targetIndex]
                env.dispatch(
                    ReaderIntent.OnPageChanged(
                        env.vm.state.value.pageChapters
                            .indexOf(active.url),
                    ),
                )
                val marks = env.markRead.marked.size
                env.dispatch(intent)
                assertOnlyChapter(env, target)
                assertEquals(listOf(env.manga to active.url), env.markRead.marked.drop(marks))
                assertEquals(env.manga to target, env.pages.requested.last())
                assertEquals(env.chapters.filterNot { it == target }.map { env.manga to it }, env.pages.cleared)
                assertEquals(env.manga to target.url, env.bookmark.observed.last())
                assertEquals(env.manga.title to target.url, env.history.recorded.last())
            }
        }

    @Test
    fun sameMangaRouteReplayDoesNotRewindAppendedFeedOrExplicitNavigation() =
        runTest(dispatcher) {
            val env = fixture()
            env.enterAndAppend()
            env.dispatch(ReaderIntent.OnPageChanged(2))
            assertIgnored(env, ReaderIntent.OnEnter(env.manga, env.chapters.first()))
            env.dispatch(ReaderIntent.OnNextChapter)
            assertOnlyChapter(env, env.chapters.last())
            assertIgnored(env, ReaderIntent.OnEnter(env.manga, env.chapters.first()))
            assertIgnored(
                env,
                ReaderIntent.OnEnter(env.manga.copy(url = "https://x/relocated"), env.chapters.first()),
            )
        }

    @Test
    fun differentMangaEntryReplacesTheFeedAndReloadsItsChapterList() =
        runTest(dispatcher) {
            val env = fixture()
            env.enterAndAppend()
            val other = env.manga.copy(title = "Other manga")
            val chapters = listOf(readerChapter("other-1"), readerChapter("other-2"))
            val gate = CompletableDeferred<Unit>()
            env.details.lists[other] = chapters
            env.details.gates[other] = gate
            env.pages.results[chapters.first().url] = flowOf(AppResult.Success(activeActionPages(chapters.first())))

            env.dispatch(ReaderIntent.OnEnter(other, chapters.first()))

            assertEquals(other, env.vm.state.value.manga)
            assertOnlyChapter(env, chapters.first())
            assertTrue(
                env.vm.state.value.chapters
                    .isEmpty(),
                "do not retain the previous manga's navigation list",
            )
            assertEquals(env.chapters.take(2).map { env.manga to it }, env.pages.cleared)
            assertEquals(listOf(env.manga, other), env.details.requested)
            assertEquals(other to chapters.first(), env.pages.requested.last())
            assertEquals(other.title to chapters.first().url, env.history.recorded.last())
            gate.complete(Unit)
            runCurrent()
            assertEquals(chapters, env.vm.state.value.chapters)
            assertTrue(env.vm.state.value.canGoNext)
            assertFalse(env.vm.state.value.canGoPrev)
        }

    @Test
    fun pagedNavigationRetainsPreviousNextEndAndLoadingGuards() =
        runTest(dispatcher) {
            val env = fixture(ReadingMode.DEFAULT)
            val pending = MutableSharedFlow<AppResult<List<Page>>>(extraBufferCapacity = 1)
            val (first, second, last) = env.chapters
            env.pages.results[second.url] = pending
            env.dispatch(ReaderIntent.OnEnter(env.manga, first))
            assertEquals(ReadingMode.DEFAULT, env.vm.state.value.readingMode)
            assertIgnored(env, ReaderIntent.OnPrevChapter)
            env.dispatch(ReaderIntent.OnNextChapter)
            assertEquals(second, env.vm.state.value.chapter)
            assertTrue(env.vm.state.value.isLoading)
            assertTrue(
                env.vm.state.value.pages
                    .isEmpty(),
            )
            assertIgnored(env, ReaderIntent.OnPrevChapter, ReaderIntent.OnNextChapter)
            assertTrue(pending.tryEmit(AppResult.Success(activeActionPages(second))))
            runCurrent()
            assertOnlyChapter(env, second)
            env.dispatch(ReaderIntent.OnNextChapter)
            assertOnlyChapter(env, last)
            assertIgnored(env, ReaderIntent.OnNextChapter)
            env.pages.results[second.url] = flowOf(AppResult.Success(activeActionPages(second)))
            env.dispatch(ReaderIntent.OnPrevChapter)
            assertOnlyChapter(env, second)
            assertEquals(listOf(env.manga to first.url, env.manga to second.url, env.manga to last.url), env.markRead.marked)
        }

    @Test
    fun previousToAnchorCancelsPendingAppendAndOldProgressBeforeReplacementArrives() =
        runTest(dispatcher) {
            val env = fixture()
            env.enterAndAppend()
            env.dispatch(ReaderIntent.OnPageChanged(2))
            val append = MutableSharedFlow<AppResult<List<Page>>>(extraBufferCapacity = 1)
            val replacement = MutableSharedFlow<AppResult<List<Page>>>(extraBufferCapacity = 1)
            var appendCancelled = false
            env.pages.results[env.chapters.last().url] =
                append.onCompletion { appendCancelled = it is CancellationException }
            env.pages.results[env.chapters.first().url] = replacement
            env.dispatch(ReaderIntent.OnAppendNextChapter)
            assertEquals(1, append.subscriptionCount.value)

            env.dispatch(ReaderIntent.OnPrevChapter)

            assertTrue(appendCancelled)
            assertEquals(0, append.subscriptionCount.value)
            assertTrue(env.vm.state.value.isLoading)
            assertTrue(
                env.vm.state.value.pages
                    .isEmpty(),
            )
            assertTrue(
                env.vm.state.value.pageProgress
                    .isEmpty(),
            )
            assertTrue(env.progress.activeUrls.isEmpty())
            assertEquals(listOf(env.manga to env.chapters[1]), env.pages.cleared)
            assertLateWorkIgnored(env, append, PageDownloadProgress.Started)
            assertTrue(replacement.tryEmit(AppResult.Success(activeActionPages(env.chapters.first()))))
            runCurrent()
            assertOnlyChapter(env, env.chapters.first())
            assertNewProgressObserved(env)
            assertLateWorkIgnored(env, append, PageDownloadProgress.Failed)
        }

    @Test
    fun manualWebRecoveryForwardsTheRequestedChapterAndSourceAsAnEffect() =
        runTest(dispatcher) {
            val env = fixture()
            env.dispatch(ReaderIntent.OnOpenInWebView("ch/2", "active-source"))
            assertEquals(ReaderEffect.OpenChapterInWebView("ch/2", "active-source"), env.vm.effects.first())
        }

    private fun fixture(mode: ReadingMode = ReadingMode.WEBTOON): ReaderActiveActionFixture =
        ReaderActiveActionFixture(dispatcher.scheduler, mode).also { fixtures += it }

    private fun assertOnlyChapter(
        env: ReaderActiveActionFixture,
        chapter: Chapter,
    ) {
        val state = env.vm.state.value
        val pages = activeActionPages(chapter)
        assertEquals(chapter, state.chapter)
        assertEquals(chapter.url, state.activeChapterUrl)
        assertEquals(pages, state.pages)
        assertEquals(List(pages.size) { chapter.url }, state.pageChapters)
        assertEquals(listOf(chapter.url), state.loadedChapterUrls)
        assertTrue(state.pageProgress.isEmpty(), "prior progress is cleared; fresh Idle emissions stay filtered")
        assertEquals(pages.map { it.url }.toSet(), env.progress.activeUrls)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    private fun assertIgnored(
        env: ReaderActiveActionFixture,
        vararg intents: ReaderIntent,
    ) {
        val state = env.vm.state.value
        val calls = env.callCounts()
        intents.forEach { env.dispatch(it) }
        assertEquals(state, env.vm.state.value)
        assertEquals(calls, env.callCounts())
    }

    private fun assertLateWorkIgnored(
        env: ReaderActiveActionFixture,
        append: MutableSharedFlow<AppResult<List<Page>>>,
        status: PageDownloadProgress,
    ) {
        val before = env.vm.state.value
        env.progress.report(activeActionPages(env.chapters[1]).first().url, status)
        assertTrue(append.tryEmit(AppResult.Success(listOf(readerPage("late-append")))))
        dispatcher.scheduler.runCurrent()
        assertEquals(before, env.vm.state.value, "old append/progress cannot repopulate either reset or loaded state")
    }

    private fun assertNewProgressObserved(env: ReaderActiveActionFixture) {
        val url = activeActionPages(env.chapters.first()).first().url
        env.progress.report(url, PageDownloadProgress.Complete)
        dispatcher.scheduler.runCurrent()
        assertEquals(mapOf(url to PageDownloadProgress.Complete), env.vm.state.value.pageProgress)
    }
}
