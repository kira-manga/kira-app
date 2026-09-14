package me.manga.kira.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Size
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressHandle
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.action_open_in_browser
import me.manga.kira.ui.generated.resources.failed_to_load_image
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.reader.internal.applyReaderDecoderHints
import me.manga.kira.ui.reader.internal.readerDecodeMaxWidthPx
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun ReaderPageItem(
    page: Page,
    screenHeightDb: Dp,
    onOpenInWebView: () -> Unit,
    progress: PageDownloadProgress,
    progressHandle: PageProgressHandle?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    contentScale: ContentScale = ContentScale.FillWidth,
) {
    // Coil's Compose model equality ignores request Extras. An ownership-only change must still
    // cancel the old painter and execute a fresh request, WITHOUT changing either cache key.
    key(progressHandle) {
        val request = rememberReaderPageRequest(page, progressHandle)
        SubcomposeAsyncImage(
            model = request,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            loading = { ReaderPageLoading(screenHeightDb, progress) },
            error = {
                val errorPainter = painter
                ReaderPageError(screenHeightDb, onRetry = { errorPainter.restart() }, onOpenInWebView = onOpenInWebView)
            },
        )
    }
}

@Composable
private fun rememberReaderPageRequest(
    page: Page,
    progressHandle: PageProgressHandle?,
): ImageRequest {
    val context = LocalPlatformContext.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    return remember(page.url, page.headers, windowWidthPx, progressHandle) {
        val headers =
            NetworkHeaders
                .Builder()
                .apply {
                    page.headers.forEach { (key, value) -> add(key, value) }
                }.build()
        // Keep height unrestricted: a two-axis cap collapses tall strips to blurry narrow
        // bitmaps. Width alone is capped at window width × zoom headroom to bound decode RAM.
        ImageRequest
            .Builder(context)
            .data(page.url)
            .httpHeaders(headers)
            .pageProgressHandle(progressHandle)
            .maxBitmapSize(
                readerDecodeMaxWidthPx(windowWidthPx)
                    ?.let { Size(Dimension.Pixels(it), Dimension.Undefined) }
                    ?: Size(Dimension.Undefined, Dimension.Undefined),
            ).applyReaderDecoderHints()
            .build()
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ReaderPageLoading(
    screenHeightDb: Dp,
    progress: PageDownloadProgress,
) {
    val fraction = (progress as? PageDownloadProgress.InProgress)?.fraction
    Box(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = screenHeightDb),
        contentAlignment = Alignment.Center,
    ) {
        if (fraction != null) {
            CircularProgressIndicator(progress = { fraction.coerceIn(0f, 1f) })
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ReaderPageError(
    screenHeightDb: Dp,
    onRetry: () -> Unit,
    onOpenInWebView: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = screenHeightDb),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(stringResource(Res.string.failed_to_load_image), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ReaderBorderedPrimaryButton(stringResource(Res.string.retry), onRetry)
                ReaderBorderedPrimaryButton(stringResource(Res.string.action_open_in_browser), onOpenInWebView)
            }
        }
    }
}

/** Existing native-parity error-button geometry, unchanged by progress ownership. */
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ReaderBorderedPrimaryButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(38.dp).defaultMinSize(minHeight = 0.dp),
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 28.dp),
    ) {
        Text(text)
    }
}
