package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.Job
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One report/reply/edit/status lane. A second action never cancels or replaces uncertain work. */
@OptIn(ExperimentalAtomicApi::class)
@Suppress("TooManyFunctions")
internal class ReportWorkOwner {
    private val state = AtomicReference<ReportWorkState>(ReportWorkState.Idle)

    fun begin(job: Job): ReportWork? {
        if (!job.isActive) return null
        val work = ReportWork(job, this)
        return if (state.compareAndSet(ReportWorkState.Idle, ReportWorkState.Active(work))) work else null
    }

    fun isCurrent(work: ReportWork): Boolean {
        val current = state.load()
        return current is ReportWorkState.Active && current.work === work && work.job.isActive
    }

    fun openForUnsentCancellation(work: ReportWork): Boolean =
        when (val current = state.load()) {
            is ReportWorkState.Active -> current.work === work
            is ReportWorkState.Cancelled -> current.work === work
            else -> false
        }

    /** The caller already retained the previous typed result before admitting another exact action. */
    fun beginAction(work: ReportWork): Boolean {
        val current = state.load() as? ReportWorkState.Active ?: return false
        return current.work === work &&
            work.job.isActive &&
            state.compareAndSet(current, ReportWorkState.Active(work))
    }

    /** This CAS is the application/close linearization point; there is no later callback or publication. */
    fun apply(
        work: ReportWork,
        slot: PendingComplaintSlot,
        application: ReportActionState,
    ): Boolean {
        val current = state.load() as? ReportWorkState.Active ?: return false
        return if (current.work !== work || !work.job.isActive) {
            false
        } else {
            val previous = current.applied
            if (previous != null) {
                previous.slot.sameAs(slot) &&
                    previous.application.sameAs(application) &&
                    state.compareAndSet(current, current)
            } else {
                state.compareAndSet(current, ReportWorkState.Active(work, ReportAppliedState(slot, application)))
            }
        }
    }

    fun application(work: ReportWork): ReportActionState? =
        when (val current = state.load()) {
            is ReportWorkState.Active -> current.applied?.application?.takeIf { current.work === work }
            is ReportWorkState.Cancelled -> current.applied?.application?.takeIf { current.work === work }
            else -> null
        }

    fun cancel(work: ReportWork) {
        while (true) {
            val current = state.load()
            if (current !is ReportWorkState.Active || current.work !== work) return
            if (state.compareAndSet(current, ReportWorkState.Cancelled(work, current.applied))) {
                work.job.cancel()
                return
            }
        }
    }

    fun cancelCurrent() {
        val current = state.load() as? ReportWorkState.Active ?: return
        cancel(current.work)
    }

    /** Caller releases only after execute/response cleanup. This method never touches a pending slot. */
    fun finish(work: ReportWork) {
        while (true) {
            val current = state.load()
            val matches =
                when (current) {
                    is ReportWorkState.Active -> current.work === work
                    is ReportWorkState.Cancelled -> current.work === work
                    else -> false
                }
            if (!matches || state.compareAndSet(current, ReportWorkState.Idle)) return
        }
    }

    fun close() {
        when (val previous = state.exchange(ReportWorkState.Closed)) {
            is ReportWorkState.Active -> previous.work.job.cancel()
            is ReportWorkState.Cancelled -> previous.work.job.cancel()
            else -> Unit
        }
    }
}

internal class ReportWork(
    val job: Job,
    private val owner: ReportWorkOwner,
) {
    fun isCurrent(): Boolean = owner.isCurrent(this)

    fun openForUnsentCancellation(): Boolean = owner.openForUnsentCancellation(this)

    fun beginAction(): Boolean = owner.beginAction(this)

    fun apply(
        slot: PendingComplaintSlot,
        application: ReportActionState,
    ): Boolean = owner.apply(this, slot, application)

    fun application(): ReportActionState? = owner.application(this)

    fun cancel() = owner.cancel(this)

    fun finish() = owner.finish(this)

    override fun toString(): String = "ReportWork(redacted)"
}

private class ReportAppliedState(
    val slot: PendingComplaintSlot,
    val application: ReportActionState,
)

private sealed interface ReportWorkState {
    data object Idle : ReportWorkState

    data object Closed : ReportWorkState

    class Active(
        val work: ReportWork,
        val applied: ReportAppliedState? = null,
    ) : ReportWorkState

    class Cancelled(
        val work: ReportWork,
        val applied: ReportAppliedState?,
    ) : ReportWorkState
}
