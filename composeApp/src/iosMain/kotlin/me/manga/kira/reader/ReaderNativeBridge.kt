package me.manga.kira.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.reader.ReaderEffect
import me.manga.kira.presentation.reader.ReaderFeedItem
import me.manga.kira.presentation.reader.ReaderIntent
import me.manga.kira.presentation.reader.ReaderState
import me.manga.kira.presentation.reader.ReaderViewModel
import me.manga.kira.presentation.reader.buildReaderFeed
import platform.UIKit.UIViewController

/**
 * Swift-facing bridge for the native iOS reader (`IOS_NATIVE_READER.md`).
 *
 * Mirrors the `di/IosBackgroundBridge.kt` precedent: the native Swift UI cannot be instantiated from
 * Kotlin, so Swift registers a [viewControllerFactory] at launch; the Compose host
 * ([ReaderHostSwitch]) calls it to embed the native VC via `UIKitViewController`. Everything crossing
 * the boundary is a stdlib type or a DTO **defined in this (always-exported) `:composeApp` framework**,
 * so Swift never needs `:presentation`/`:domain` to be exported.
 *
 * The native UI is a pure renderer of shared state: it observes [IosReaderSnapshot] and dispatches
 * intents via [ReaderNativeSession]; ALL list/append/resume/index/history logic stays in the shared
 * `ReaderViewModel`.
 */
object ReaderNativeBridge {
    private var factory: ((ReaderNativeSession) -> UIViewController)? = null

    /**
     * Called once from Swift at app launch (after Koin bootstrap) to register the native reader VC
     * factory. Until set, [ReaderHostSwitch] falls back to the Compose reader even when
     * [IosReaderFlags.NATIVE_READER_ENABLED] is true.
     */
    fun setViewControllerFactory(factory: (ReaderNativeSession) -> UIViewController) {
        this.factory = factory
    }

    fun hasFactory(): Boolean = factory != null

    /** Builds the native reader VC for [session]; null if Swift never registered a factory. */
    internal fun create(session: ReaderNativeSession): UIViewController? = factory?.invoke(session)

    /**
     * Localized strings for the native reader, resolved from compose-resources by [ReaderHostSwitch]
     * (a `@Composable`, so it can call `stringResource`) and read by the Swift UI. Null until the native
     * reader is first shown — Swift falls back to English. Reusing the shared resources keeps the native
     * reader's wording identical to the Compose reader across all locales.
     */
    var strings: IosReaderStrings? = null
        private set

    fun setStrings(strings: IosReaderStrings) { this.strings = strings }
}

/** Localized native-reader strings (resolved from compose-resources). See [ReaderNativeBridge.strings]. */
data class IosReaderStrings(
    val readingMode: String,
    val modeDefault: String,
    val modeRtl: String,
    val modeLtr: String,
    val modeVertical: String,
    val modeWebtoon: String,
    val modeContinuous: String,
    val retry: String,
    val failedToLoadImage: String,
    val openInWebView: String,
    val nextChapter: String,
    val lastChapter: String,
    val finishedPrefix: String,
    val couldntLoadChapter: String,
    val addToLibraryFirst: String,
)

/** Flat, Swift-friendly page DTO — only stdlib types cross the boundary. `url` may be `https://` (use
 *  [headers]) or a local `file://`/path string (downloaded; bypass network). */
data class IosReaderPage(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * One row of the continuous reader feed (image OR chapter boundary), the Swift-friendly projection of
 * [ReaderFeedItem] from the shared, unit-tested [buildReaderFeed]. The native webtoon controller renders
 * these so chapter boundaries appear as inline panels between chapters — byte-identical to the Compose
 * reader. The native side derives the page↔feed index maps from [pageIndex] on the image rows.
 */
data class IosReaderFeedRow(
    /** true ⇒ a chapter-boundary panel; false ⇒ an image row. */
    val isBoundary: Boolean,
    // Image-row fields (valid when !isBoundary):
    val url: String,
    val headers: Map<String, String>,
    /** Absolute index into [IosReaderSnapshot.pages]; -1 for a boundary row. */
    val pageIndex: Int,
    // Boundary-row fields (valid when isBoundary):
    /** Label of the chapter that just ended. */
    val finishedLabel: String,
    /** Label of the next chapter, or `null` when this is the last chapter (terminal panel). */
    val nextLabel: String?,
)

/**
 * Flat, Swift-friendly projection of `ReaderState`. Only stdlib types + DTOs defined here, so the
 * generated Obj-C header is self-contained. The native UI renders this and never touches Compose.
 */
data class IosReaderSnapshot(
    val isLoading: Boolean,
    val isInitialLoading: Boolean,
    val hasError: Boolean,
    val pages: List<IosReaderPage>,
    /** Interleaved feed (image + boundary rows) for continuous modes — drives the inline chapter panels. */
    val feedRows: List<IosReaderFeedRow>,
    /** Owning chapter URL per page (same length/order as [pages]) — drives boundary cards + active segment. */
    val pageChapters: List<String>,
    val currentPageIndex: Int,
    /** `ReadingMode.name` (WEBTOON / CONTINUOUS_VERTICAL / DEFAULT / VERTICAL / LEFT_TO_RIGHT / RIGHT_TO_LEFT). */
    val readingMode: String,
    val isUiVisible: Boolean,
    val isBookmarked: Boolean,
    val mangaTitle: String,
    val chapterLabel: String,
    /** URL of the chapter currently in view + the source api — used to open the chapter in the WebView
     *  (the whole chapter shares one Cloudflare challenge, matching the Compose reader). */
    val activeChapterUrl: String,
    val sourceApi: String,
    val canGoNext: Boolean,
    val canGoPrev: Boolean,
    /** 1-based page number within the active chapter segment (for the HUD). */
    val activeChapterPageNumber: Int,
    val activeChapterPageCount: Int,
    /** Feed index of the active chapter's FIRST page — maps a within-chapter scrubber position
     *  (0-based) back to an absolute [pages] index: `feedIndex = activeChapterStartIndex + sliderValue`. */
    val activeChapterStartIndex: Int,
    /** Bumps only when [pages]/[feedRows] actually change (append / chapter jump), NOT on page-scroll.
     *  Lets the Swift host skip the O(n) page/feed re-mapping for the common scroll-only snapshot. */
    val feedSignature: Int,
)

/**
 * Per-reader session handed to Swift. Wraps the shared [ReaderViewModel]: streams [IosReaderSnapshot]s
 * and routes one-shot effects (nav effects to the Compose host's Kotlin callbacks; UI-feedback effects
 * to Swift callbacks), and exposes intent methods. Created by [ReaderHostSwitch] (iOS actual).
 * Composition closes the current renderer on native-slot disposal, including a temporary WebView
 * handoff; the remembered session can then start a replacement attachment.
 */
class ReaderNativeSession internal constructor(
    private val viewModel: ReaderViewModel,
    private val scope: CoroutineScope,
    private val onNavigateBack: () -> Unit,
    // Keep this callback name distinct from the public onOpenInWebView() method invoked by Swift.
    // When both had the same name, handleEffect() resolved the method instead of this function
    // property and recursively submitted OpenChapterInWebView forever on the main thread.
    private val onOpenInWebViewEffect: (url: String, api: String) -> Unit,
    private val onSolveCloudflare: (url: String, api: String) -> Unit,
) {
    private val lifecycle =
        ReaderNativeLifecycle(
            scope = scope,
            onResumed = { viewModel.submit(ReaderIntent.OnScreenResumed) },
            onPaused = { viewModel.submit(ReaderIntent.OnScreenPaused) },
        )

    private val feedProjection = ReaderNativeFeedProjection()

    /**
     * Swift registers its UI callbacks and starts observation. [onSnapshot] fires with the current
     * state immediately (StateFlow replay) and on every change. Safe to call once from the native VC's
     * `viewDidLoad`. The returned attachment owns this VC's collectors and lifecycle input; the VC
     * detaches only that attachment on teardown, never the remembered destination's whole session.
     */
    fun start(
        onSnapshot: (IosReaderSnapshot) -> Unit,
        onShowNotInLibrary: () -> Unit,
        onShowError: () -> Unit,
    ): ReaderNativeAttachment {
        val attachment = lifecycle.attach()
        attachment.scope.launch {
            viewModel.state.collect { onSnapshot(it.toSnapshot()) }
        }
        attachment.scope.launch {
            viewModel.effects.collect { handleEffect(it, onShowNotInLibrary, onShowError) }
        }
        return attachment
    }

    private fun handleEffect(
        effect: ReaderEffect,
        onShowNotInLibrary: () -> Unit,
        onShowError: () -> Unit,
    ) {
        if (dispatchOpenInWebViewEffect(effect, onOpenInWebViewEffect)) return
        when (effect) {
            is ReaderEffect.NavigateBack -> onNavigateBack()
            is ReaderEffect.OpenChapterInWebView -> Unit
            is ReaderEffect.SolveCloudflareChallenge -> onSolveCloudflare(effect.url, effect.api)
            is ReaderEffect.ShowNotInLibrary -> onShowNotInLibrary()
            is ReaderEffect.ShowError -> onShowError()
            // Native chrome shares the on-screen page directly (it already holds the decoded image), so
            // the ShareCurrentPage effect is a no-op on the native path.
            is ReaderEffect.ShareCurrentPage -> Unit
        }
    }

    // --- Intents (Kotlin host calls onEnter; Swift calls the rest) ---
    fun onEnter(manga: Manga, chapter: Chapter) = viewModel.submit(ReaderIntent.OnEnter(manga, chapter))
    fun onPageChanged(index: Int) = viewModel.submit(ReaderIntent.OnPageChanged(index))
    fun onAppendNextChapter() = viewModel.submit(ReaderIntent.OnAppendNextChapter)
    fun onNextChapter() = viewModel.submit(ReaderIntent.OnNextChapter)
    fun onPrevChapter() = viewModel.submit(ReaderIntent.OnPrevChapter)
    fun onUiToggle() = viewModel.submit(ReaderIntent.OnUiToggle)
    fun onToggleBookmark() = viewModel.submit(ReaderIntent.OnToggleBookmark)
    fun onRetry() = viewModel.submit(ReaderIntent.OnRetry)
    fun onBackClick() = viewModel.submit(ReaderIntent.OnBackClick)
    fun onOpenInWebView(url: String, api: String) = viewModel.submit(ReaderIntent.OnOpenInWebView(url, api))

    /** Swift passes a `ReadingMode.name`; unknown values are ignored. */
    fun onReadingModeChanged(modeName: String) {
        val mode = ReadingMode.entries.firstOrNull { it.name == modeName } ?: return
        viewModel.submit(ReaderIntent.OnReadingModeChanged(mode))
    }

    /** Destination disposal: end any active span, then cancel its current renderer's collectors. */
    fun close() {
        lifecycle.close()
    }

    private fun ReaderState.toSnapshot(): IosReaderSnapshot {
        val feed = feedProjection.project(this)
        return IosReaderSnapshot(
            isLoading = isLoading,
            isInitialLoading = isInitialLoading,
            hasError = error != null,
            pages = feed.pages,
            feedRows = feed.rows,
            pageChapters = pageChapters,
            currentPageIndex = currentPageIndex,
            readingMode = readingMode.name,
            isUiVisible = isUiVisible,
            isBookmarked = isBookmarked,
            mangaTitle = manga?.title.orEmpty(),
            chapterLabel = (activeChapter ?: chapter)?.let { it.name.ifBlank { it.number } }.orEmpty(),
            activeChapterUrl = activeChapterUrl ?: chapter?.url.orEmpty(),
            sourceApi = manga?.api.orEmpty(),
            canGoNext = canGoNext,
            canGoPrev = canGoPrev,
            activeChapterPageNumber = activeChapterPageNumber,
            activeChapterPageCount = activeChapterPageCount,
            activeChapterStartIndex = activeChapterPageIndices.firstOrNull() ?: 0,
            feedSignature = feed.revision,
        )
    }
}

/**
 * Dispatches the native reader's WebView effect directly to the host navigation callback.
 *
 * This intentionally lives outside [ReaderNativeSession]: the Swift-facing session also exposes a
 * method named `onOpenInWebView`, and resolving that method from the effect collector would submit
 * the same intent again, creating an unbounded main-thread loop.
 */
internal fun dispatchOpenInWebViewEffect(
    effect: ReaderEffect,
    onOpenInWebViewEffect: (url: String, api: String) -> Unit,
): Boolean {
    if (effect !is ReaderEffect.OpenChapterInWebView) return false
    onOpenInWebViewEffect(effect.url, effect.api)
    return true
}
