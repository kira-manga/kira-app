package me.manga.kira.ui.reader

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import kotlinx.coroutines.awaitCancellation
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressHandle
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private const val OWNED_PAGE_URL = "https://reader.test/owned-page.png"

@OptIn(ExperimentalTestApi::class)
class ReaderPageProgressTest {
    @Test
    fun retryExecutesTheRealPainterAgainWithTheSameCapturedOwner() {
        val owner = PageProgressHandle(OWNED_PAGE_URL)
        val executions = CopyOnWriteArrayList<ImageRequest>()
        PageProgressImages { request ->
            executions += request
            ErrorResult(null, request, IllegalStateException("retry fixture"))
        }.use {
            runComposeUiTest {
                var retryLabel = ""
                setContent {
                    KiraTheme(darkTheme = false) {
                        retryLabel = stringResource(Res.string.retry)
                        ReaderPageItem(
                            page = Page(OWNED_PAGE_URL, emptyMap()),
                            screenHeightDb = 200.dp,
                            onOpenInWebView = {},
                            progress = PageDownloadProgress.Idle,
                            progressHandle = owner,
                            modifier = Modifier.size(400.dp, 600.dp),
                        )
                    }
                }
                waitUntil(timeoutMillis = 5_000) {
                    executions.size == 1 && retryLabel.isNotEmpty() &&
                        onAllNodesWithText(retryLabel).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText(retryLabel).performClick()
                waitUntil(timeoutMillis = 5_000) { executions.size == 2 }
                executions.forEach { request ->
                    assertSame(owner, request.pageProgressHandle)
                    assertEquals(OWNED_PAGE_URL, request.data)
                    assertNull(request.memoryCacheKey)
                    assertNull(request.diskCacheKey)
                }
            }
        }
    }

    @Test
    fun replacingOnlyTheOwnerCancelsOldPainterAndExecutesNewSameUrlRequest() {
        val first = PageProgressHandle(OWNED_PAGE_URL)
        val second = PageProgressHandle(OWNED_PAGE_URL)
        var owner by mutableStateOf(first)
        var mounted by mutableStateOf(true)
        val executions = CopyOnWriteArrayList<ImageRequest>()
        val cancelled = CopyOnWriteArrayList<PageProgressHandle?>()
        PageProgressImages { request ->
            executions += request
            try {
                awaitCancellation()
            } finally {
                cancelled += request.pageProgressHandle
            }
        }.use {
            runComposeUiTest {
                setContent {
                    KiraTheme(darkTheme = false) {
                        if (mounted) ReaderPageItem(
                            page = Page(OWNED_PAGE_URL, emptyMap()),
                            screenHeightDb = 200.dp,
                            onOpenInWebView = {},
                            progress = PageDownloadProgress.Idle,
                            progressHandle = owner,
                            modifier = Modifier.size(400.dp, 600.dp),
                        )
                    }
                }
                waitUntil(timeoutMillis = 5_000) { executions.size == 1 }
                runOnIdle { owner = second }
                waitUntil(timeoutMillis = 5_000) { executions.size == 2 && first in cancelled }
                assertSame(first, executions[0].pageProgressHandle)
                assertSame(second, executions[1].pageProgressHandle)
                assertEquals(executions[0].data, executions[1].data)
                assertEquals(executions[0].memoryCacheKeyExtras, executions[1].memoryCacheKeyExtras)
                runOnIdle { mounted = false }
                waitUntil(timeoutMillis = 5_000) { second in cancelled }
            }
        }
        assertEquals(setOf(first, second), cancelled.toSet())
    }
}

@OptIn(DelicateCoilApi::class)
private class PageProgressImages(execute: suspend (ImageRequest) -> ImageResult) : Closeable {
    private val previous = SingletonImageLoader.get(PlatformContext.INSTANCE)
    private val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
        .memoryCache(null)
        .diskCache(null)
        .components { add(Interceptor { execute(it.request) }) }
        .build()

    init {
        SingletonImageLoader.setUnsafe(loader)
    }

    override fun close() {
        SingletonImageLoader.setUnsafe(previous)
        loader.shutdown()
    }
}
