package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.Job
import me.manga.kira.platform.storage.CredentialCleanupMarker
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One delete-all lifetime, independent of the normal-report lane it must be able to invalidate. */
@OptIn(ExperimentalAtomicApi::class)
internal class InstallationDeletionWorks {
    private val state = AtomicReference<DeletionWorkState>(DeletionWorkState.Idle)

    fun begin(job: Job): InstallationDeletionWork? {
        if (!job.isActive) return null
        val work = InstallationDeletionWork(job, this)
        return if (state.compareAndSet(DeletionWorkState.Idle, DeletionWorkState.Active(work))) work else null
    }

    fun isCurrent(work: InstallationDeletionWork): Boolean {
        val current = state.load() as? DeletionWorkState.Active ?: return false
        return current.work === work && !current.applying && work.job.isActive
    }

    /** The terminal-application/close linearization point, after the coordinator's exact rechecks. */
    fun claimTerminal(
        work: InstallationDeletionWork,
        request: InstallationDeletionRequest,
    ): Boolean {
        val current = state.load() as? DeletionWorkState.Active ?: return false
        return current.work === work &&
            !current.applying &&
            work.job.isActive &&
            request.binding.work === work &&
            state.compareAndSet(current, DeletionWorkState.Active(work, applying = true))
    }

    fun claimMarkedCleanup(
        work: InstallationDeletionWork,
        marker: CredentialCleanupMarker,
    ): Boolean {
        val current = state.load() as? DeletionWorkState.Active ?: return false
        return current.work === work &&
            !current.applying &&
            work.job.isActive &&
            marker.expectedGeneration != null &&
            state.compareAndSet(current, DeletionWorkState.Active(work, applying = true))
    }

    fun cancel(work: InstallationDeletionWork) {
        while (true) {
            val current = state.load() as? DeletionWorkState.Active
            if (current == null || current.work !== work) return
            if (state.compareAndSet(current, DeletionWorkState.Cancelled(work))) {
                work.job.cancel()
                return
            }
        }
    }

    /** Release only after the HTTP execute/response-cleanup tail; never remove durable intent. */
    fun finish(work: InstallationDeletionWork) {
        while (true) {
            val current = state.load()
            val matches =
                when (current) {
                    is DeletionWorkState.Active -> current.work === work
                    is DeletionWorkState.Cancelled -> current.work === work
                    else -> false
                }
            if (!matches || state.compareAndSet(current, DeletionWorkState.Idle)) return
        }
    }

    fun close() {
        when (val previous = state.exchange(DeletionWorkState.Closed)) {
            is DeletionWorkState.Active -> previous.work.job.cancel()
            is DeletionWorkState.Cancelled -> previous.work.job.cancel()
            else -> Unit
        }
    }
}

internal class InstallationDeletionWork(
    val job: Job,
    private val owner: InstallationDeletionWorks,
) {
    fun isCurrent(): Boolean = owner.isCurrent(this)

    fun claimTerminal(request: InstallationDeletionRequest): Boolean = owner.claimTerminal(this, request)

    fun claimMarkedCleanup(marker: CredentialCleanupMarker): Boolean = owner.claimMarkedCleanup(this, marker)

    fun cancel() = owner.cancel(this)

    fun finish() = owner.finish(this)

    override fun toString(): String = "InstallationDeletionWork(redacted)"
}

private sealed interface DeletionWorkState {
    data object Idle : DeletionWorkState

    data object Closed : DeletionWorkState

    class Active(
        val work: InstallationDeletionWork,
        val applying: Boolean = false,
    ) : DeletionWorkState

    class Cancelled(
        val work: InstallationDeletionWork,
    ) : DeletionWorkState
}
