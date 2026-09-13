package me.manga.kira.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.reader.ReaderEffect
import me.manga.kira.presentation.reader.ReaderIntent
import me.manga.kira.presentation.reader.ReaderState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.action_open_in_browser
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReaderActiveChapterRecoveryTest {
    @Test
    fun pageErrorRecoveryUsesActiveChapterAndRebindsWithoutRemounting() =
        runRecoveryTest { surface ->
            assertEquals(surface.chapters[1].url, surface.state.activeChapterUrl)
            clickRecoveryAndForwardEffect(surface, surface.chapters[1])
            runOnIdle { surface.state = surface.state.copy(currentPageIndex = 2) }
            clickRecoveryAndForwardEffect(surface, surface.chapters[2])
            runOnIdle {
                assertEquals(surface.chapters.first(), surface.state.chapter, "the navigation anchor stays unchanged")
                assertEquals(1, surface.intents.count { it is ReaderIntent.OnEnter }, "the same screen remains mounted")
            }
        }

    @Test
    fun pageErrorRecoveryFallsBackToNavigationChapterWithoutActiveIdentity() =
        runRecoveryTest(withoutActiveIdentity = true) { surface ->
            assertNull(surface.state.activeChapterUrl)
            clickRecoveryAndForwardEffect(surface, surface.chapters.first())
        }

    private fun runRecoveryTest(
        withoutActiveIdentity: Boolean = false,
        block: suspend ComposeUiTest.(RecoverySurface) -> Unit,
    ) {
        val directory = Files.createTempDirectory("reader-active-recovery-")
        val surface = RecoverySurface(directory, withoutActiveIdentity)
        try {
            runSkikoComposeUiTest(size = Size(VIEWPORT_WIDTH, VIEWPORT_HEIGHT), density = Density(1f)) {
                render(surface)
                block(surface)
            }
        } finally {
            surface.effects.close()
            Files.delete(directory)
        }
    }

    private fun ComposeUiTest.render(surface: RecoverySurface) {
        setContent {
            KiraTheme(darkTheme = false) {
                surface.recoveryLabel = stringResource(Res.string.action_open_in_browser)
                ReaderScreenContent(
                    state = surface.state,
                    effects = surface.effectFlow,
                    manga = surface.manga,
                    chapter = surface.chapters.first(),
                    onIntent = { surface.intents += it },
                    onNavigateBack = {},
                    onOpenInWebView = { url, api -> surface.forwarded += url to api },
                    onSharePage = {},
                    onSolveCloudflareChallenge = { _, _ -> },
                )
            }
        }
        waitForIdle()
        assertTrue(surface.recoveryLabel.isNotBlank())
    }

    private fun ComposeUiTest.clickRecoveryAndForwardEffect(
        surface: RecoverySurface,
        chapter: Chapter,
    ) {
        val request = ReaderIntent.OnOpenInWebView(chapter.url, surface.manga.api)
        val before = runOnIdle { surface.intents.filterIsInstance<ReaderIntent.OnOpenInWebView>() }
        waitUntil(timeoutMillis = IMAGE_ERROR_TIMEOUT_MILLIS) { visibleRecoveryButton(surface.recoveryLabel) != null }
        checkNotNull(visibleRecoveryButton(surface.recoveryLabel))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        runOnIdle {
            assertEquals(before + request, surface.intents.filterIsInstance<ReaderIntent.OnOpenInWebView>())
            assertEquals(before.map { it.url to it.api }, surface.forwarded)
            // Exercise the production effect collector separately; real VM mapping has its own test.
            assertTrue(surface.effects.trySend(ReaderEffect.OpenChapterInWebView(request.url, request.api)).isSuccess)
        }
        waitForIdle()
        runOnIdle { assertEquals((before + request).map { it.url to it.api }, surface.forwarded) }
    }

    private fun ComposeUiTest.visibleRecoveryButton(label: String): SemanticsNodeInteraction? {
        val buttons = onAllNodesWithText(label)
        return (0 until buttons.fetchSemanticsNodes().size)
            .map { buttons[it] }
            .singleOrNull { it.isDisplayed() }
    }

    private class RecoverySurface(
        directory: Path,
        withoutActiveIdentity: Boolean,
    ) {
        val manga =
            Manga(
                api = "reader-recovery-fixture",
                language = "en",
                title = "Reader recovery fixture",
                url = "manga/fixture",
                coverUrl = "",
                rating = null,
                genres = emptyList(),
            )
        val chapters =
            listOf("1", "2", "3").map { number ->
                Chapter(number, "Chapter $number", "chapter/$number", null, false, false)
            }

        // These files deliberately do not exist. Coil's real request fails locally and renders
        // ReaderPageItem's actual image-error slot: no network, preview handler or surrogate button.
        private val pages =
            chapters.indices.map { index ->
                Page(directory.resolve("missing-$index.png").toUri().toString(), emptyMap())
            }
        val effects = Channel<ReaderEffect>(Channel.UNLIMITED)
        val effectFlow = effects.receiveAsFlow()
        val intents = mutableListOf<ReaderIntent>()
        val forwarded = mutableListOf<Pair<String, String>>()
        var recoveryLabel = ""
        var state by mutableStateOf(initialState(withoutActiveIdentity))

        private fun initialState(withoutActiveIdentity: Boolean): ReaderState =
            ReaderState(
                manga = manga,
                chapter = if (withoutActiveIdentity) null else chapters.first(),
                chapters = if (withoutActiveIdentity) emptyList() else chapters,
                pages = if (withoutActiveIdentity) pages.take(1) else pages,
                pageChapters = if (withoutActiveIdentity) emptyList() else chapters.map { it.url },
                loadedChapterUrls = if (withoutActiveIdentity) emptyList() else chapters.map { it.url },
                currentPageIndex = if (withoutActiveIdentity) 0 else 1,
                readingMode = ReadingMode.LEFT_TO_RIGHT,
                isLoading = false,
                isUiVisible = false,
            )
    }

    private companion object {
        const val VIEWPORT_WIDTH = 480f
        const val VIEWPORT_HEIGHT = 720f
        const val IMAGE_ERROR_TIMEOUT_MILLIS = 10_000L
    }
}
