package me.manga.kira.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.manga.kira.core.platform.encodeImageBitmapToPng
import me.manga.kira.platform.image.ScreenshotProvider
import org.koin.compose.koinInject

/** One Reader share across route instances, admitted before any snapshot is allocated. */
internal class ReaderShareCoordinator(
    private val onFailure: (Throwable) -> Unit = { failure ->
        Logger.withTag("ReaderShare").e(failure) { "Could not share the Reader viewport" }
    },
) {
    private val admission = Mutex()
    private val busy = MutableStateFlow(false)
    val isSharing = busy.asStateFlow()

    fun <T : Any> tryShare(
        owner: CoroutineScope,
        capture: suspend () -> T?,
        share: suspend (T) -> Unit,
    ): Job? {
        if (owner.coroutineContext[Job]?.isActive != true || !admission.tryLock()) return null
        busy.value = true
        val operation = try {
            owner.launch { captureAndShare(capture, share) }
        } catch (failure: Throwable) {
            release()
            throw failure
        }
        // Also runs if cancellation prevented the body from starting. Unlike a cancellation
        // handler, completion waits for a blocking encoder (and its structured children) to exit.
        operation.invokeOnCompletion { release() }
        return operation
    }

    private suspend fun <T : Any> captureAndShare(
        capture: suspend () -> T?,
        share: suspend (T) -> Unit,
    ) {
        try {
            currentCoroutineContext().ensureActive()
            val snapshot = capture() ?: return
            currentCoroutineContext().ensureActive()
            share(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }

    private fun release() {
        // Publish idle before unlocking: an older completion must not clear a newer busy state.
        busy.value = false
        admission.unlock()
    }
}

/** Main-confined route attachment; owns cancellation, never a bitmap or an encoded byte array. */
internal class ReaderSharing(
    private val scope: CoroutineScope,
    private val lifecycle: Lifecycle,
    private val coordinator: ReaderShareCoordinator,
    private val screenshots: ScreenshotProvider,
) {
    private val activeJob = MutableStateFlow<Job?>(null)
    private var closed = false
    val isSharing = coordinator.isSharing

    fun request(capture: suspend () -> ImageBitmap?) {
        if (!canShare()) return
        val operation = coordinator.tryShare(
            owner = scope,
            capture = { if (canShare()) capture() else null },
            share = ::shareBitmap,
        ) ?: return
        activeJob.value = operation
        operation.invokeOnCompletion { activeJob.compareAndSet(operation, null) }
    }

    fun cancel() {
        activeJob.value?.cancel()
    }

    fun close() {
        closed = true
        cancel()
    }

    private fun canShare(): Boolean = !closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private suspend fun shareBitmap(bitmap: ImageBitmap) {
        // One encode only. The existing encoder still has its PNG backing buffer + returned copy.
        val bytes = withContext(Dispatchers.Default) { encodeImageBitmapToPng(bitmap) } ?: return
        currentCoroutineContext().ensureActive()
        if (canShare()) screenshots.shareBitmapBytes(bytes, READER_SHARE_TITLE)
    }
}

@Composable
internal fun rememberReaderSharing(owner: LifecycleOwner): ReaderSharing {
    val coordinator: ReaderShareCoordinator = koinInject()
    val screenshots: ScreenshotProvider = koinInject()
    // Preserve Compose's frame clock for the lazy two-frame capture.
    val scope = rememberCoroutineScope()
    val sharing = remember(owner, scope, coordinator, screenshots) {
        ReaderSharing(scope, owner.lifecycle, coordinator, screenshots)
    }
    DisposableEffect(owner, sharing) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> sharing.cancel()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            sharing.close()
            owner.lifecycle.removeObserver(observer)
        }
    }
    return sharing
}

private const val READER_SHARE_TITLE = "Share screenshot"
