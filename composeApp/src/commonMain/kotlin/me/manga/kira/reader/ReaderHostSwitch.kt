package me.manga.kira.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.presentation.reader.ReaderViewModel

/**
 * Reader host indirection between the nav route adapters and the actual reader UI.
 *
 * On Android and Desktop this is a transparent passthrough to the shared Compose
 * `:ui/.../reader/ReaderScreen`. On iOS it chooses — at runtime — between the native Swift reader
 * (when [IosReaderFlags.NATIVE_READER_ENABLED] is on AND Swift registered a factory) and the Compose
 * reader fallback, so the reader can be flipped to native without touching navigation, and rolled back
 * instantly. Both reader-route adapters call this instead of `ReaderScreen` directly.
 *
 * See `IOS_NATIVE_READER.md` for why the native iOS reader exists.
 */
@Composable
internal expect fun ReaderHostSwitch(
    viewModel: ReaderViewModel,
    manga: Manga,
    chapter: Chapter,
    onNavigateBack: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    onSharePage: (ImageBitmap) -> Unit,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit,
)
