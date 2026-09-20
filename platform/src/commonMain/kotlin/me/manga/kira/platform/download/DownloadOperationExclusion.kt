package me.manga.kira.platform.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Existing operation/recovery ownership refused a non-destructive exclusive attempt. Retry later. */
class DownloadOperationBusy : IllegalStateException("Download operation ownership is still active")

/**
 * One graph-owned exclusion between download/file operations and selection/removal writers.
 * This is not source-selection readiness, a queue snapshot, or a native-session drain.
 *
 * Acquire [withOperation] before capturing any locator, row, manifest or file input; retain it
 * through real producer termination and owned cleanup. Acquire [withExclusive] outside Room and
 * retain it through commit/rollback. An exclusive attempt fails immediately while an operation is
 * owned, so it cannot put cancellation/recovery behind a writer that needs those operations to end.
 */
class DownloadOperationExclusion {
    // StateFlow's compareAndSet is common/thread-safe. All ownership transitions are one CAS;
    // native retain/release never suspend, and no coroutine/user callback runs under a lock here.
    private val state = MutableStateFlow(State())

    /**
     * Runs structured work under a borrowed operation handle. Do not release the supplied handle.
     * Nested calls retain the same graph's current operation rather than making a fresh admission.
     * An application-scope/native child must [Operation.retain] BEFORE launch/handoff and release
     * its own handle only on actual completion, including a lazy coroutine cancelled before start.
     * A cancelled join, task.cancel(), or empty queue does not settle that child.
     */
    suspend fun <T> withOperation(block: suspend (Operation) -> T): T {
        val context = currentCoroutineContext()
        check(context[Exclusive] == null) { "Cannot enter an operation from an exclusive writer" }
        val inherited = context[Operation]
        check(inherited == null || inherited.owner === this) { "Different download exclusion instance" }
        val operation = inherited?.retain() ?: acquireOperation()
        try {
            return withContext(operation) { block(operation) }
        } finally {
            operation.release()
        }
    }

    /**
     * Atomically acquires only an unowned gate; otherwise throws [DownloadOperationBusy]. There is
     * no waiting writer or time budget. Any inherited operation/exclusive context is a programming
     * error, including foreign or released handles: never upgrade or shadow an ancestor's owner.
     * While actually exclusive, fresh operations suspend before capture. Never call from Room.
     */
    suspend fun <T> withExclusive(block: suspend () -> T): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        check(context[Operation] == null && context[Exclusive] == null) { "Exclusive ownership cannot be nested" }
        val exclusive = acquireExclusive()
        try {
            return withContext(exclusive) { block() }
        } finally {
            releaseExclusive(exclusive)
        }
    }

    /**
     * Verifies this graph's still-live exclusive context at a nested writer boundary (for example,
     * inside Room's transaction context). This assertion neither acquires nor extends ownership.
     * Absent, foreign or stale contexts are programming errors; cancellation is preserved.
     */
    suspend fun requireExclusive() {
        val context = currentCoroutineContext()
        context.ensureActive()
        val exclusive = context[Exclusive]
        check(exclusive != null && state.value.exclusive === exclusive) { "Exclusive ownership is not held" }
    }

    /**
     * Independently owned continuation of one admitted operation; also its coroutine context.
     * Native callback threads may retain/release synchronously. A retained child may finish work
     * derived from the original capture, not carry an old row into a later unowned operation.
     */
    class Operation internal constructor(internal val owner: DownloadOperationExclusion) :
        AbstractCoroutineContextElement(Key) {
        /** Creates a distinct live handle before asynchronous handoff. A released handle is invalid. */
        fun retain(): Operation = checkNotNull(owner.addOperation(this))

        /** Releases only this handle, once; duplicates never release a sibling/native receiver. */
        fun release() = owner.releaseOperation(this)

        companion object Key : CoroutineContext.Key<Operation>
    }

    /**
     * One initial native recovery owner, reserved before this graph's gate is exposed to callers.
     * Bind this and [exclusion] once together; a lazy native transport must claim the reservation.
     */
    class Recovery internal constructor(
        val exclusion: DownloadOperationExclusion,
        operation: Operation,
    ) {
        private val reservation = MutableStateFlow<Operation?>(operation)

        /**
         * Transfers the sole initial handle to its native lifetime owner, once. Claiming does not
         * release it. Only actual native termination and owned cleanup may settle this reservation;
         * a completed census, cancellation request or readiness flag is not that proof.
         */
        fun takeOperation(): Operation {
            val operation = checkNotNull(reservation.value) { "Recovery operation already claimed" }
            check(reservation.compareAndSet(operation, null)) { "Recovery operation already claimed" }
            return operation
        }
    }

    companion object {
        /**
         * Constructs a gate and its initial recovery reservation before publishing either. Use only
         * when a real native lifetime owner will consume it; platforms without one use the constructor.
         */
        fun recovering(): Recovery {
            val exclusion = DownloadOperationExclusion()
            return Recovery(exclusion, checkNotNull(exclusion.addOperation()))
        }
    }

    private suspend fun acquireOperation(): Operation {
        while (true) {
            currentCoroutineContext().ensureActive()
            addOperation()?.let { return it }
            state.first { it.exclusive == null }
        }
    }

    private fun addOperation(parent: Operation? = null): Operation? {
        val operation = Operation(this)
        while (true) {
            val before = state.value
            if (parent != null) check(parent in before.operations) { "Operation handle was already released" }
            if (before.exclusive != null) return null
            val after = State(before.operations + operation)
            if (state.compareAndSet(before, after)) return operation
        }
    }

    private fun releaseOperation(operation: Operation) {
        while (true) {
            val before = state.value
            if (operation !in before.operations) return
            val after = State(before.operations - operation)
            if (state.compareAndSet(before, after)) return
        }
    }

    private fun acquireExclusive(): Exclusive {
        val exclusive = Exclusive()
        while (true) {
            val before = state.value
            if (before.exclusive != null || before.operations.isNotEmpty()) throw DownloadOperationBusy()
            if (state.compareAndSet(before, State(exclusive = exclusive))) return exclusive
        }
    }

    private fun releaseExclusive(exclusive: Exclusive) {
        while (true) {
            val before = state.value
            check(before.exclusive === exclusive) { "Exclusive ownership changed" }
            if (state.compareAndSet(before, State())) return
        }
    }

    private class State(
        val operations: Set<Operation> = emptySet(),
        val exclusive: Exclusive? = null,
    )

    private class Exclusive : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Exclusive>
    }
}
