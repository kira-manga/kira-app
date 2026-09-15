package me.manga.kira.ui.reader

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.reader.ReaderIntent
import me.manga.kira.presentation.reader.ReaderState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.np_reader_mode_dialog_title
import me.manga.kira.ui.generated.resources.np_reader_mode_revert
import me.manga.kira.ui.generated.resources.np_reader_share
import me.manga.kira.ui.generated.resources.reader_show_controls
import me.manga.kira.ui.generated.resources.reader_toggle_bookmark
import me.manga.kira.ui.generated.resources.reading_mode
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertTrue

internal const val READER_CHROME_ROOT = "app97-reader"
internal const val READER_FRAME_MS = 16L
internal const val READER_SETTLE_MS = 32L
private const val PAGE_PREFIX = "app97://page/"
private const val PAGE_COUNT = 5
private const val PATTERN_SIZE = 400
private const val PATTERN_BACKGROUND = 0xFF10B060.toInt()
private const val PATTERN_STRIPE = 0xFFC020C0.toInt()
private const val PATTERN_STRIPE_LEFT = 160f
private const val PATTERN_STRIPE_RIGHT = 240f
private const val STRIPE_MIN_DIVISOR = 6
private const val STRIPE_MAX_DIVISOR = 4
private val fixtureManga = Manga("app97", "en", "Gesture fixture", "app97://manga", "", null, emptyList())
private val fixtureChapter = Chapter("Chapter 1", "1", "app97://chapter", null, false, false)

@OptIn(ExperimentalTestApi::class)
internal fun runReaderChromeTest(block: ReaderChromeTestFixture.() -> Unit) {
    // runComposeUiTest disposes composition before use closes our loader and bitmap, even on failure.
    ReaderChromeImages().use { images ->
        runComposeUiTest {
            mainClock.autoAdvance = false
            ReaderChromeTestFixture(this, images.completed).apply {
                install()
                block()
            }
        }
    }
}

internal fun loadedReaderState(
    mode: ReadingMode,
    visible: Boolean = false,
): ReaderState =
    ReaderState(
        manga = fixtureManga,
        chapter = fixtureChapter,
        chapters = listOf(fixtureChapter),
        pages = List(PAGE_COUNT) { Page("$PAGE_PREFIX$it", emptyMap()) },
        pageChapters = List(PAGE_COUNT) { fixtureChapter.url },
        loadedChapterUrls = listOf(fixtureChapter.url),
        readingMode = mode,
        isUiVisible = visible,
    )

internal fun emptyReaderStates(): List<ReaderState> =
    listOf(
        ReaderState(isLoading = true, isUiVisible = false),
        ReaderState(error = AppError.Network.NoConnectivity(), isUiVisible = false),
        ReaderState(isUiVisible = false),
    )

@OptIn(ExperimentalTestApi::class)
internal class ReaderChromeTestFixture(
    val test: ComposeUiTest,
    private val completed: MutableSet<String>,
) {
    var state by mutableStateOf(ReaderState(isLoading = true, isUiVisible = false))
    private var generation by mutableStateOf(0)
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var previousVisibility: Boolean? = null
    val intents = mutableListOf<ReaderIntent>()
    var visibleSince = 0L
        private set
    var doubleTapTimeout = 0L
        private set
    var revealLabel = ""
    var backLabel = ""
    var retryLabel = ""
    var settingsLabel = ""
    var bookmarkLabel = ""
    var shareLabel = ""
    var modeDialogLabel = ""
    var revertLabel = ""
    val root get() = test.onNodeWithTag(READER_CHROME_ROOT, useUnmergedTree = true)
    val toggleCount get() = intents.count { it == ReaderIntent.OnUiToggle }
    val context get() = "${state.readingMode}/$direction/loading=${state.isLoading}/error=${state.error}"
    private val lifecycleOwner =
        object : LifecycleOwner {
            override val lifecycle =
                LifecycleRegistry.createUnsafe(this).apply {
                    currentState = Lifecycle.State.RESUMED
                }
        }

    fun install() = test.setContent { KiraTheme(darkTheme = false) { content() } }

    fun reset(
        initial: ReaderState,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        test.runOnIdle {
            state = initial
            direction = layoutDirection
            generation++
            previousVisibility = null
            completed.clear()
            intents.clear()
        }
        advance(READER_SETTLE_MS)
    }

    fun replace(initial: ReaderState) {
        test.runOnIdle { state = initial }
        advance(READER_SETTLE_MS)
    }

    fun advance(milliseconds: Long) {
        test.mainClock.advanceTimeBy(milliseconds)
        test.waitForIdle()
    }

    fun awaitPage() {
        val url = state.pages[state.currentPageIndex].url
        test.waitUntil("Successful Coil page for $context", timeoutMillis = 5_000) {
            advance(READER_FRAME_MS)
            url in completed
        }
        advance(READER_SETTLE_MS)
        val page = stripe()
        assertTrue(
            page.hasGreenBackground &&
                page.width in (page.viewportWidth / STRIPE_MIN_DIVISOR)..(page.viewportWidth / STRIPE_MAX_DIVISOR),
            "Actual successful green page with a 20%-width magenta stripe must be painted: $context",
        )
    }

    private fun accept(intent: ReaderIntent) {
        intents += intent
        // Only existing UI-observation responses are modeled; no VM or network is started.
        state =
            when (intent) {
                ReaderIntent.OnUiToggle -> state.copy(isUiVisible = !state.isUiVisible)
                is ReaderIntent.OnPageChanged -> state.copy(currentPageIndex = intent.pageIndex)
                else -> state
            }
    }

    @Composable
    private fun content() {
        val visible = state.isUiVisible
        SideEffect {
            if (visible && previousVisibility != true) visibleSince = test.mainClock.currentTime
            previousVisibility = visible
        }
        readLabels()
        CompositionLocalProvider(
            LocalLayoutDirection provides direction,
            LocalLifecycleOwner provides lifecycleOwner,
        ) {
            key(generation) { reader() }
        }
    }

    @Composable
    private fun reader() {
        ReaderScreenContent(
            state = state,
            effects = emptyFlow(),
            manga = fixtureManga,
            chapter = fixtureChapter,
            onIntent = ::accept,
            onNavigateBack = {},
            onOpenInWebView = { _, _ -> },
            onSharePage = {},
            onSolveCloudflareChallenge = { _, _ -> },
            modifier = Modifier.size(width = 400.dp, height = 640.dp).testTag(READER_CHROME_ROOT),
        )
    }

    @Composable
    private fun readLabels() {
        doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
        revealLabel = stringResource(Res.string.reader_show_controls)
        backLabel = stringResource(Res.string.back)
        retryLabel = stringResource(Res.string.retry)
        settingsLabel = stringResource(Res.string.reading_mode)
        bookmarkLabel = stringResource(Res.string.reader_toggle_bookmark)
        shareLabel = stringResource(Res.string.np_reader_share)
        modeDialogLabel = stringResource(Res.string.np_reader_mode_dialog_title)
        revertLabel = stringResource(Res.string.np_reader_mode_revert)
    }
}

@OptIn(DelicateCoilApi::class)
private class ReaderChromeImages : Closeable {
    val completed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val previous = SingletonImageLoader.get(PlatformContext.INSTANCE)
    private val bitmap = patternedBitmap()
    private val image = bitmap.asImage(shareable = true)
    private val loader =
        ImageLoader
            .Builder(PlatformContext.INSTANCE)
            .memoryCache(null)
            .diskCache(null)
            .components {
                add(
                    object : Interceptor {
                        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                            require(
                                chain.request.data
                                    .toString()
                                    .startsWith(PAGE_PREFIX),
                            )
                            // Successful real Coil rendering, not an error/loading placeholder or fake tap box.
                            completed += chain.request.data.toString()
                            return SuccessResult(image, chain.request, DataSource.MEMORY)
                        }
                    },
                )
            }.build()

    init {
        SingletonImageLoader.setUnsafe(loader)
    }

    override fun close() {
        SingletonImageLoader.setUnsafe(previous)
        try {
            loader.shutdown()
        } finally {
            bitmap.close()
        }
    }

    private fun patternedBitmap(): Bitmap =
        Bitmap().apply {
            check(allocN32Pixels(PATTERN_SIZE, PATTERN_SIZE))
            erase(PATTERN_BACKGROUND)
            Canvas(this).use { canvas ->
                Paint().use { paint ->
                    paint.color = PATTERN_STRIPE
                    canvas.drawRect(
                        Rect.makeLTRB(PATTERN_STRIPE_LEFT, 0f, PATTERN_STRIPE_RIGHT, PATTERN_SIZE.toFloat()),
                        paint,
                    )
                }
            }
            setImmutable()
        }
}
