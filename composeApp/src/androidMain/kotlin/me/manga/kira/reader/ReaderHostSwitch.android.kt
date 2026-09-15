package me.manga.kira.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.presentation.reader.ReaderViewModel
import me.manga.kira.ui.reader.ReaderScreen

/** Android: always the shared Compose reader (no native iOS path applies here). */
@Composable
internal actual fun ReaderHostSwitch(
    viewModel: ReaderViewModel,
    manga: Manga,
    chapter: Chapter,
    onNavigateBack: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    onSharePage: (capture: suspend () -> ImageBitmap?) -> Unit,
    isSharing: Boolean,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit,
) {
    ReaderScreen(
        viewModel = viewModel,
        manga = manga,
        chapter = chapter,
        onNavigateBack = onNavigateBack,
        onOpenInWebView = onOpenInWebView,
        onSharePage = onSharePage,
        isSharing = isSharing,
        onSolveCloudflareChallenge = onSolveCloudflareChallenge,
    )
}
