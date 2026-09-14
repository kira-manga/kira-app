package me.manga.kira.di

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.model.library.LibraryRefreshCompleted

/** The owned job has the only completion callback, including cancellation before start. */
internal fun launchLibraryRefreshCompletion(
    scope: CoroutineScope,
    refresh: suspend () -> AppResult<LibraryRefreshCompleted>,
    stampLastSuccess: suspend () -> Unit,
    onComplete: (Boolean) -> Unit,
): Job {
    var completed = false
    val job =
        scope.launch(start = CoroutineStart.LAZY) {
            completed = refreshAndStamp(refresh, stampLastSuccess)
        }
    // Job completion synchronizes the result; no racy check/set shared by two callback owners.
    job.invokeOnCompletion { cause -> onComplete(cause == null && completed) }
    job.start()
    return job
}

private suspend fun refreshAndStamp(
    refresh: suspend () -> AppResult<LibraryRefreshCompleted>,
    stampLastSuccess: suspend () -> Unit,
): Boolean {
    val log = Logger.withTag("LibraryBgRefresh")
    return runCatchingCancellable {
        log.i { "bg refresh started" }
        when (val result = refresh()) {
            is AppResult.Failure -> {
                log.w { "bg refresh failed: ${result.error}" }
                false
            }
            is AppResult.Success -> {
                currentCoroutineContext().ensureActive()
                if (result.value.snapshotSize > 0) stampLastSuccess()
                currentCoroutineContext().ensureActive()
                log.i { "bg refresh done: ${result.value.newChapterCount} new chapters" }
                true
            }
        }
    }.getOrElse { t ->
        log.w(t) { "bg refresh ended without completion" }
        false
    }
}
