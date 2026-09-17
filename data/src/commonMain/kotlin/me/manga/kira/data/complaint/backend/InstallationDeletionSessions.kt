package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/**
 * The existing session manager owns this helper and serializes fetch with its own session mutex.
 * A ticket proves one actual fresh exchange, not cache freshness, and is consumed exactly once.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class InstallationDeletionSessions(
    private val coordinator: InstallationCredentialCoordinator,
    private val http: ComplaintSessionHttp,
    private val clock: TimeSource,
) {
    private val state = AtomicReference<DeletionSessionState>(DeletionSessionState.Empty)

    suspend fun fetch(start: InstallationDeletionStart): InstallationDeletionSessionResult {
        val previous = state.load()
        if (previous === DeletionSessionState.Closed || !state.compareAndSet(previous, DeletionSessionState.Empty)) {
            return closed()
        }
        val admitted = coordinator.checkDeletionStart(start)
        if (admitted !is Outcome.Success) return local(admitted)
        val mark = clock.markNow()
        val result = http.fetch(start.record)
        val checked = coordinator.checkDeletionStart(start)
        if (checked !is Outcome.Success) return local(checked)
        if (state.load() === DeletionSessionState.Closed) return closed()
        if (result !is ComplaintSessionResult.Ready) return InstallationDeletionSessionResult.Failed(result)
        val entry = InstallationSessionEntry(start.record, start.issuer, result.session, mark)
        if (!entry.isFresh()) return failed(ComplaintSessionFailure.EXPIRED)
        val ticket = InstallationDeletionSession(start, entry)
        return if (state.compareAndSet(DeletionSessionState.Empty, DeletionSessionState.Ready(ticket))) {
            InstallationDeletionSessionResult.Ready(ticket)
        } else {
            closed()
        }
    }

    /** Non-suspending under the credential coordinator mutex; never takes the session mutex. */
    fun claim(start: InstallationDeletionStart, ticket: InstallationDeletionSession): Boolean {
        val current = state.load() as? DeletionSessionState.Ready ?: return false
        return current.ticket === ticket && ticket.start === start &&
            ticket.entry.matches(start.record, start.issuer) && ticket.entry.isFresh() &&
            state.compareAndSet(current, DeletionSessionState.Empty)
    }

    fun close() {
        state.exchange(DeletionSessionState.Closed)
    }

    private fun local(outcome: Outcome<*>): InstallationDeletionSessionResult = when (outcome) {
        is Outcome.Refused -> InstallationDeletionSessionResult.Failed(ComplaintSessionResult.LocalFailure(outcome))
        is Outcome.StorageFailure -> InstallationDeletionSessionResult.Failed(ComplaintSessionResult.LocalFailure(outcome))
        is Outcome.Invalid -> InstallationDeletionSessionResult.Failed(ComplaintSessionResult.LocalFailure(outcome))
        is Outcome.Success -> failed(ComplaintSessionFailure.INVALIDATED)
    }

    private fun closed(): InstallationDeletionSessionResult = failed(ComplaintSessionFailure.CLOSED)

    private fun failed(reason: ComplaintSessionFailure): InstallationDeletionSessionResult =
        InstallationDeletionSessionResult.Failed(ComplaintSessionResult.Failed(reason))
}

internal class InstallationDeletionSession(
    val start: InstallationDeletionStart,
    val entry: InstallationSessionEntry,
) {
    override fun toString(): String = "InstallationDeletionSession(redacted)"
}

internal sealed interface InstallationDeletionSessionResult {
    class Ready(val ticket: InstallationDeletionSession) : InstallationDeletionSessionResult

    class Failed(val result: ComplaintSessionResult) : InstallationDeletionSessionResult
}

private sealed interface DeletionSessionState {
    data object Empty : DeletionSessionState

    data object Closed : DeletionSessionState

    class Ready(val ticket: InstallationDeletionSession) : DeletionSessionState
}
