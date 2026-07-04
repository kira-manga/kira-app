package me.manga.kira.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.presentation.reader.ReaderViewModel
import me.manga.kira.ui.reader.ReaderScreen
import org.jetbrains.compose.resources.stringResource
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.reading_mode
import me.manga.kira.composeapp.generated.resources.reading_mode_default
import me.manga.kira.composeapp.generated.resources.reading_mode_rtl
import me.manga.kira.composeapp.generated.resources.reading_mode_ltr
import me.manga.kira.composeapp.generated.resources.reading_mode_vertical
import me.manga.kira.composeapp.generated.resources.reading_mode_webtoon
import me.manga.kira.composeapp.generated.resources.reading_mode_continuous
import me.manga.kira.composeapp.generated.resources.retry
import me.manga.kira.composeapp.generated.resources.failed_to_load_image
import me.manga.kira.composeapp.generated.resources.action_open_in_browser
import me.manga.kira.composeapp.generated.resources.reader_native_next_chapter
import me.manga.kira.composeapp.generated.resources.reader_native_last_chapter
import me.manga.kira.composeapp.generated.resources.reader_native_finished_prefix
import me.manga.kira.composeapp.generated.resources.reader_native_couldnt_load_chapter
import me.manga.kira.composeapp.generated.resources.reader_native_add_to_library_first
import platform.UIKit.UIViewController

/**
 * iOS: choose the native Swift reader (flag ON + Swift factory registered) or the Compose reader.
 *
 * Default falls through to [ReaderScreen] so the live behavior is unchanged until the native reader is
 * explicitly enabled and verified. The native path reuses the SAME route-scoped [ReaderViewModel] (so
 * all shared state/logic and lifecycle are identical) and embeds the Swift VC via `UIKitViewController`.
 */
@Composable
internal actual fun ReaderHostSwitch(
    viewModel: ReaderViewModel,
    manga: Manga,
    chapter: Chapter,
    onNavigateBack: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    onSharePage: (ImageBitmap) -> Unit,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit,
) {
    if (IosReaderFlags.NATIVE_READER_ENABLED && ReaderNativeBridge.hasFactory()) {
        NativeReaderHost(
            viewModel = viewModel,
            manga = manga,
            chapter = chapter,
            onNavigateBack = onNavigateBack,
            onOpenInWebView = onOpenInWebView,
            onSolveCloudflareChallenge = onSolveCloudflareChallenge,
        )
    } else {
        ReaderScreen(
            viewModel = viewModel,
            manga = manga,
            chapter = chapter,
            onNavigateBack = onNavigateBack,
            onOpenInWebView = onOpenInWebView,
            onSharePage = onSharePage,
            onSolveCloudflareChallenge = onSolveCloudflareChallenge,
            onReportProgress = onReportProgress,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun NativeReaderHost(
    viewModel: ReaderViewModel,
    manga: Manga,
    chapter: Chapter,
    onNavigateBack: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember(viewModel) {
        ReaderNativeSession(
            viewModel = viewModel,
            scope = scope,
            onNavigateBack = onNavigateBack,
            onOpenInWebView = onOpenInWebView,
            onSolveCloudflare = onSolveCloudflareChallenge,
        )
    }
    // Resolve the reader's localized strings from compose-resources (matching the Compose reader's
    // translations) and hand them to the Swift UI. Resolved here because `stringResource` needs a
    // Composable scope; the assignment is idempotent across recompositions.
    ReaderNativeBridge.setStrings(
        IosReaderStrings(
            readingMode = stringResource(Res.string.reading_mode),
            modeDefault = stringResource(Res.string.reading_mode_default),
            modeRtl = stringResource(Res.string.reading_mode_rtl),
            modeLtr = stringResource(Res.string.reading_mode_ltr),
            modeVertical = stringResource(Res.string.reading_mode_vertical),
            modeWebtoon = stringResource(Res.string.reading_mode_webtoon),
            modeContinuous = stringResource(Res.string.reading_mode_continuous),
            retry = stringResource(Res.string.retry),
            failedToLoadImage = stringResource(Res.string.failed_to_load_image),
            openInWebView = stringResource(Res.string.action_open_in_browser),
            nextChapter = stringResource(Res.string.reader_native_next_chapter),
            lastChapter = stringResource(Res.string.reader_native_last_chapter),
            finishedPrefix = stringResource(Res.string.reader_native_finished_prefix),
            couldntLoadChapter = stringResource(Res.string.reader_native_couldnt_load_chapter),
            addToLibraryFirst = stringResource(Res.string.reader_native_add_to_library_first),
        ),
    )
    // Kotlin drives OnEnter (it holds the domain models); Swift never constructs Manga/Chapter.
    LaunchedEffect(session, manga, chapter) {
        session.onEnter(manga, chapter)
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }
    UIKitViewController(
        factory = { ReaderNativeBridge.create(session) ?: UIViewController() },
        modifier = Modifier.fillMaxSize(),
        // Migrated off the deprecated androidx.compose.ui.interop.UIKitViewController; preserves the
        // old defaults accessibilityEnabled=true (isNativeAccessibilityEnabled=true) and
        // interactive=true (default Cooperative interactionMode).
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true),
    )
}
