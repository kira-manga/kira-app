package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.core.result.AppResult
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One owned load/decode; replacement waits for all old execute/finally blocks, not just cancel(). */
@OptIn(ExperimentalAtomicApi::class)
internal class ComplaintHistoryLoads {
    private val replacement = Mutex()
    private val state = AtomicReference<HistoryLoadState>(HistoryLoadState.Idle)

    suspend fun <T> run(load: suspend (ComplaintHistoryWork) -> AppResult<T>): AppResult<T> =
        coroutineScope {
            val work = ComplaintHistoryWork(currentCoroutineContext().job, this@ComplaintHistoryLoads)
            try {
                if (enter(work)) load(work) else historyUnavailable()
            } finally {
                val current = state.load()
                if (current is HistoryLoadState.Active && current.work === work) {
                    state.compareAndSet(current, HistoryLoadState.Idle)
                }
                work.finished.complete(Unit)
            }
        }

    private suspend fun enter(work: ComplaintHistoryWork): Boolean =
        replacement.withLock {
            val previous = state.load()
            if (previous is HistoryLoadState.Active) {
                previous.work.cancel()
                previous.work.finished.await()
            }
            currentCoroutineContext().ensureActive()
            state.compareAndSet(HistoryLoadState.Idle, HistoryLoadState.Active(work))
        }

    fun isCurrent(work: ComplaintHistoryWork): Boolean {
        val current = state.load()
        return current is HistoryLoadState.Active && current.work === work && work.job.isActive
    }

    /** Called only by the coordinator's final checked, no-I/O publication critical section. */
    fun publish(work: ComplaintHistoryWork): Boolean {
        val current = state.load()
        return current is HistoryLoadState.Active &&
            current.work === work &&
            work.job.isActive &&
            state.compareAndSet(current, HistoryLoadState.Idle)
    }

    fun close() {
        val previous = state.exchange(HistoryLoadState.Closed)
        if (previous is HistoryLoadState.Active) previous.work.cancel()
    }
}

internal class ComplaintHistoryWork(
    val job: Job,
    private val loads: ComplaintHistoryLoads,
) {
    val finished = CompletableDeferred<Unit>()

    fun isCurrent(): Boolean = loads.isCurrent(this)

    fun publish(): Boolean = loads.publish(this)

    fun cancel() = job.cancel()
}

private sealed interface HistoryLoadState {
    data object Idle : HistoryLoadState

    data object Closed : HistoryLoadState

    class Active(
        val work: ComplaintHistoryWork,
    ) : HistoryLoadState
}
