package me.manga.kira.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.core.logging.Logger
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.appFailure
import me.manga.kira.core.result.appSuccess
import me.manga.kira.platform.backup.BackupAcquisitionBusy
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.backup.backupImportError
import org.koin.compose.koinInject
import kotlin.coroutines.cancellation.CancellationException

/** Main-thread picker lifetime; claimed paths belong to the importer, never to screen disposal. */
internal class BackupImportPickerSession(
    private val scope: CoroutineScope,
    private val staging: BackupImportStaging,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
) {
    private var callback: ((AppResult<String?>) -> Unit)? = null
    private var copyJob: Job? = null
    private var disposed = false
    private val handedOff = mutableSetOf<String>()

    fun begin(onResult: (AppResult<String?>) -> Unit): Boolean {
        if (disposed) return false
        if (callback != null || copyJob?.isActive == true) {
            scope.launch { deliver(onResult, appFailure(backupImportError(BackupAcquisitionBusy()))) }
            return false
        }
        callback = onResult
        return true
    }

    fun cancelPick() = completeWithoutCopy(appSuccess(null))

    fun failPick(failure: Throwable) = completeWithoutCopy(appFailure(backupImportError(failure)))

    /** [copy] runs on IO and must bound the provider stream using the supplied checkpoint. */
    fun acquire(copy: (checkpoint: () -> Unit) -> String) {
        if (disposed || copyJob?.isActive == true) return
        val receiver = callback ?: return
        callback = null
        copyJob = scope.launch { copyAndDeliver(copy, receiver) }
    }

    fun dispose() {
        disposed = true
        callback = null
        copyJob?.cancel()
        val pending = handedOff.toList()
        handedOff.clear()
        // The composition scope may already be cancelled. This detached work only releases
        // previously registered cache capabilities; it never performs an import or live mutation.
        scope.launch(NonCancellable + io) { pending.forEach { discard(it) } }
    }

    private suspend fun copyAndDeliver(
        copy: (checkpoint: () -> Unit) -> String,
        receiver: (AppResult<String?>) -> Unit,
    ) {
        var path: String? = null
        var delivered = false
        try {
            val result = takeSnapshot(copy) { path = it }
            if (disposed) return
            path?.let { handedOff += it }
            delivered = deliver(receiver, result)
        } finally {
            if (!delivered) path?.let { owned ->
                handedOff -= owned
                withContext(NonCancellable + io) { discard(owned) }
            }
        }
    }

    private suspend fun takeSnapshot(
        copy: (checkpoint: () -> Unit) -> String,
        captured: (String) -> Unit,
    ): AppResult<String> =
        try {
            val path = withContext(io) {
                val context = currentCoroutineContext()
                // Capture INSIDE IO: cancellation on the return dispatch must not lose custody.
                copy { context.ensureActive() }.also(captured)
            }
            appSuccess(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            appFailure(backupImportError(failure))
        }

    private fun completeWithoutCopy(result: AppResult<String?>) {
        if (disposed || copyJob?.isActive == true) return
        val receiver = callback ?: return
        callback = null
        scope.launch { if (!disposed) deliver(receiver, result) }
    }

    private fun deliver(receiver: (AppResult<String?>) -> Unit, result: AppResult<String?>): Boolean =
        try {
            receiver(result)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            logger.e(LOG_TAG, "Backup picker callback failed")
            false
        }

    private fun discard(path: String) {
        try {
            staging.discardPending(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // No source URI/path or provider exception text is sent to telemetry.
            logger.w(LOG_TAG, "Backup picker cache cleanup failed")
        }
    }

    private companion object {
        const val LOG_TAG = "BackupImportPicker"
    }
}

@Composable
internal fun rememberBackupImportPickerSession(): BackupImportPickerSession {
    val scope = rememberCoroutineScope()
    val staging: BackupImportStaging = koinInject()
    val logger: Logger = koinInject()
    val session = remember(scope, staging, logger) { BackupImportPickerSession(scope, staging, platformIoDispatcher, logger) }
    DisposableEffect(session) { onDispose { session.dispose() } }
    return session
}
