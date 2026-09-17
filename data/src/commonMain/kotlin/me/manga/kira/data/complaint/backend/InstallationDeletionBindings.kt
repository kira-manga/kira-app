package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation
import me.manga.kira.platform.storage.CleanupMarkerReadResult
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.platform.storage.PendingComplaintSnapshot

/**
 * Coordinator-owned helper. Every method runs under its existing mutex and consent/marker guards.
 * Keep the binding lifecycle beside its one work registry; splitting it would divide fencing ownership.
 */
@Suppress("TooManyFunctions")
internal class InstallationDeletionBindings(
    private val credentials: InstallationCredentialStore,
    private val pending: PendingComplaintActionStore,
) {
    private val works = mutableSetOf<InstallationDeletionWork>()

    /** Read-only classification, never a cleanup capability. The coordinator retains its mutex. */
    suspend fun observe(): ComplaintInstallationDeletionObservation =
        when (val marker = credentials.readCleanupMarker()) {
            is InstallationStorageFailure -> fail(marker)
            is CleanupMarkerReadResult.Present ->
                if (marker.marker.reason == CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED) {
                    ComplaintInstallationDeletionObservation.RemoteDeletionPending
                } else {
                    ComplaintInstallationDeletionObservation.LocalCleanupRequired
                }
            CleanupMarkerReadResult.Missing -> observeRecord()
        }

    private suspend fun observeRecord(): ComplaintInstallationDeletionObservation =
        when (val read = credentials.read()) {
            is InstallationStorageFailure -> fail(read)
            CredentialReadResult.Missing -> {
                pending.requireEmptyPending()
                ComplaintInstallationDeletionObservation.Missing
            }
            is CredentialReadResult.Present ->
                when (read.record.state) {
                    InstallationCredentialState.ACTIVE -> {
                        pending.reconciliationSnapshot(read.record)
                        ComplaintInstallationDeletionObservation.Active
                    }
                    InstallationCredentialState.DELETION_PENDING ->
                        ComplaintInstallationDeletionObservation.RemoteDeletionPending
                    InstallationCredentialState.LOCAL_RESET_PENDING ->
                        ComplaintInstallationDeletionObservation.LocalCleanupRequired
                }
        }

    suspend fun captureConfirmation(): InstallationDeletionConfirmation {
        val record = credentials.coordinationRecord().also(::active)
        return InstallationDeletionConfirmation(record, pending.reconciliationSnapshot(record))
    }

    suspend fun checkConfirmation(
        expected: InstallationDeletionConfirmation,
        work: InstallationDeletionWork,
    ) {
        requireLive(work)
        val record = credentials.exactRecord(expected.record).also(::active)
        if (!samePending(expected.snapshot, pending.reconciliationSnapshot(record))) refuse(Block.STALE_BINDING)
        requireLive(work)
    }

    /** Register the captured consent, never recapture a replacement identity after the warning. */
    fun confirmedStart(
        expected: InstallationDeletionConfirmation,
        work: InstallationDeletionWork,
        issuer: ReconciliationIssuer,
    ): InstallationDeletionStart {
        works += work
        return InstallationDeletionStart(expected.record, expected.snapshot, issuer, work)
    }

    suspend fun start(
        work: InstallationDeletionWork,
        issuer: ReconciliationIssuer,
    ): InstallationDeletionStart {
        requireLive(work)
        val record = credentials.coordinationRecord().also(::active)
        val snapshot = pending.reconciliationSnapshot(record)
        requireLive(work)
        works += work
        return InstallationDeletionStart(record, snapshot, issuer, work)
    }

    suspend fun continuation(
        work: InstallationDeletionWork,
        issuer: ReconciliationIssuer,
    ): InstallationDeletionBinding {
        requireLive(work)
        val record = credentials.coordinationRecord().also(::deleting)
        requireLive(work)
        works += work
        return InstallationDeletionBinding(record, issuer, work)
    }

    suspend fun check(
        start: InstallationDeletionStart,
        issuer: ReconciliationIssuer,
    ) {
        requireRegistered(start.work, start.issuer, issuer)
        val record = credentials.exactRecord(start.record).also(::active)
        if (!samePending(start.snapshot, pending.reconciliationSnapshot(record))) refuse(Block.STALE_BINDING)
        requireLive(start.work)
    }

    suspend fun commit(
        start: InstallationDeletionStart,
        key: String,
    ): InstallationDeletionBinding {
        val next = credentials.replaceCoordinated(start.record, checked(start.record.beginDeletion(key)))
        return InstallationDeletionBinding(next, start.issuer, start.work)
    }

    suspend fun check(
        binding: InstallationDeletionBinding,
        issuer: ReconciliationIssuer,
    ) {
        requireRegistered(binding.work, binding.issuer, issuer)
        deleting(credentials.exactRecord(binding.record))
        requireLive(binding.work)
    }

    fun cancel() = works.forEach { it.cancel() }

    fun finish(work: InstallationDeletionWork) {
        works -= work
        work.finish()
    }

    private suspend fun requireRegistered(
        work: InstallationDeletionWork,
        expected: ReconciliationIssuer,
        issuer: ReconciliationIssuer,
    ) {
        if (expected !== issuer || work !in works) refuse(Block.STALE_BINDING)
        requireLive(work)
    }

    private suspend fun requireLive(work: InstallationDeletionWork) {
        currentCoroutineContext().ensureActive()
        if (!work.isCurrent()) refuse(Block.STALE_BINDING)
    }
}

/** Exact immutable warning observation; neither a session nor durable delete-all authority. */
internal class InstallationDeletionConfirmation(
    val record: InstallationCredentialRecord,
    val snapshot: PendingComplaintSnapshot,
) {
    override fun toString(): String = "InstallationDeletionConfirmation(redacted)"
}

internal class InstallationDeletionStart(
    val record: InstallationCredentialRecord,
    val snapshot: PendingComplaintSnapshot,
    val issuer: ReconciliationIssuer,
    val work: InstallationDeletionWork,
) {
    override fun toString(): String = "InstallationDeletionStart(redacted)"
}

internal class InstallationDeletionBinding(
    val record: InstallationCredentialRecord,
    val issuer: ReconciliationIssuer,
    val work: InstallationDeletionWork,
) {
    override fun toString(): String = "InstallationDeletionBinding(redacted)"
}
