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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #4: the reader must never fail silently. A chapter that resolves to ZERO pages must surface a
 * retryable error (state.error set + a ShowError effect), not land in a loaded-empty-no-error state
 * that the UI renders as a blank/black screen. A non-empty result must still load normally, and a
 * streaming-shaped sequence (Success([p1]) → Success([p1,p2])) must NOT be tripped by the empty gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelEmptyPagesTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    @Test
    fun emptyPages_surfaceErrorAndEffect_notSilentBlank() = runTest {
        val env = readerTestEnv(chapterList = listOf(readerChapter("1")))
        env.pages.result = flowOf(AppResult.Success(emptyList()))
        val effects = mutableListOf<ReaderEffect>()
        val job = launch(dispatcher) { env.vm.effects.collect { effects += it } }

        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))

        val s = env.vm.state.value
        assertFalse(s.isLoading, "loading must clear")
        assertFalse(s.hasPages, "no pages")
        assertNotNull(s.error, "a zero-page chapter must set an error (never silent blank)")
        assertTrue(
            effects.any { it is ReaderEffect.ShowError },
            "a zero-page chapter must emit ShowError: $effects",
        )
        job.cancel()
    }

    @Test
    fun nonEmptyPages_loadNormally_noError() = runTest {
        val env = readerTestEnv(chapterList = listOf(readerChapter("1")))
        env.pages.result = flowOf(AppResult.Success(listOf(readerPage("p/1"), readerPage("p/2"))))

        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))

        val s = env.vm.state.value
        assertEquals(2, s.pages.size)
        assertTrue(s.hasPages)
        assertEquals(null, s.error, "a non-empty result must NOT be flagged as an error")
    }

    @Test
    fun streamingShapedSuccess_doesNotTripEmptyGate() = runTest {
        val env = readerTestEnv(chapterList = listOf(readerChapter("1")))
        // Streaming-style: a growing cumulative list across emissions; neither emission is empty.
        env.pages.result = flowOf(
            AppResult.Success(listOf(readerPage("p/1"))),
            AppResult.Success(listOf(readerPage("p/1"), readerPage("p/2"))),
        )
        val effects = mutableListOf<ReaderEffect>()
        val job = launch(dispatcher) { env.vm.effects.collect { effects += it } }

        env.vm.submit(ReaderIntent.OnEnter(readerManga(), readerChapter("1")))

        val s = env.vm.state.value
        assertEquals(2, s.pages.size, "final cumulative page list wins")
        assertEquals(null, s.error)
        assertTrue(effects.none { it is ReaderEffect.ShowError }, "streaming growth never trips empty-as-error")
        job.cancel()
    }
}
