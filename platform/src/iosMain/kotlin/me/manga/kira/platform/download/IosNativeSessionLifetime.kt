package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionTask

/**
 * Owns a whole native session until its real invalidation callback, not merely an empty census.
 * Per-task and per-event children can outlive both the census and an engine's cancellation request.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosNativeSessionLifetime(
    private val ownership: DownloadOperationExclusion.Operation,
) {
    private val lock = NSLock()
    private val tasks = mutableMapOf<ULong, NativeTask>()
    private val accesses = mutableSetOf<Access>()
    private val census = CompletableDeferred<Unit>()
    private val invalidation = CompletableDeferred<Unit>()
    private var session: NSURLSession? = null
    private var censusFinished = false
    private var closing = false
    private var invalidated = false
    private var settled = false

    fun bindSession(session: NSURLSession) = locked {
        check(this.session == null)
        this.session = session
    }

    fun tryAccess(): Access? = locked {
        if (closing || invalidated) null else Access(this).also { accesses += it }
    }

    fun registerCreatedTask(task: NSURLSessionTask, operation: DownloadOperationExclusion.Operation) {
        val unused = locked {
            check(!closing && !invalidated && accesses.isNotEmpty())
            if (task.taskIdentifier in tasks) operation else {
                tasks[task.taskIdentifier] = NativeTask(operation)
                null
            }
        }
        unused?.release()
    }

    /** Observation only adds ownership; absence in a later snapshot can never remove a task. */
    fun observeTasks(observed: List<NSURLSessionTask>) = locked {
        if (!invalidated) observed.forEach { task ->
            tasks.getOrPut(task.taskIdentifier) { NativeTask(ownership.retain()) }
        }
    }

    fun finishCensus(observed: List<NSURLSessionTask>) {
        observeTasks(observed)
        val accepted = locked {
            if (invalidated) false else {
                censusFinished = true
                true
            }
        }
        if (accepted) census.complete(Unit)
        requestIdleInvalidation()
    }

    suspend fun awaitCensus() = census.await()

    suspend fun awaitInvalidation() = invalidation.await()

    fun isSettled(): Boolean = locked { settled }

    /** Called synchronously before any delegate handler may stage or hand off a file. */
    fun beginCallback(task: NSURLSessionTask, terminal: Boolean = false): DownloadOperationExclusion.Operation? =
        locked {
            if (invalidated) return@locked null
            val record = tasks.getOrPut(task.taskIdentifier) { NativeTask(ownership.retain()) }
            if (record.terminalStarted) return@locked null
            if (terminal) record.terminalStarted = true
            record.operation.retain()
        }

    /** Only the actual didComplete callback's finally may finish this task, never task.cancel(). */
    fun finishTask(task: NSURLSessionTask) {
        val completed = locked {
            val record = tasks.getValue(task.taskIdentifier)
            check(record.terminalStarted)
            if (record.finished) null else record.also { it.finished = true }.operation
        }
        completed?.release()
        requestIdleInvalidation()
    }

    /** Apple calls this after the last task-related delegate delivery for finishTasksAndInvalidate. */
    fun didBecomeInvalid(error: NSError?) {
        val valid = locked {
            if (invalidated) return
            invalidated = true
            settled = closing && error == null && censusFinished && accesses.isEmpty() && tasks.values.all { it.finished }
            settled
        }
        if (valid) {
            ownership.release()
            invalidation.complete(Unit)
        } else {
            // Unexpected errors/missing terminal receipts preserve exclusion, not an invented drain.
            val failure = IllegalStateException("Background session invalidated without settled native ownership")
            census.completeExceptionally(failure)
            invalidation.completeExceptionally(failure)
            BgDownloadLog.warn("session.invalidation.unsettled")
        }
    }

    private fun releaseAccess(access: Access) {
        locked { accesses.remove(access) }
        requestIdleInvalidation()
    }

    private fun requestIdleInvalidation() {
        val idle = locked {
            if (!censusFinished || closing || invalidated || accesses.isNotEmpty() || tasks.values.any { !it.finished }) {
                return@locked null
            }
            session?.also { closing = true }
        }
        // This is a request, not completion. Never invalidateAndCancel or release the root here.
        idle?.finishTasksAndInvalidate()
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    class Access internal constructor(private val lifetime: IosNativeSessionLifetime) {
        fun release() = lifetime.releaseAccess(this)
    }

    private class NativeTask(val operation: DownloadOperationExclusion.Operation) {
        var terminalStarted = false
        var finished = false
    }
}
