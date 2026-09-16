package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.platform.storage.CleanupMarkerReadResult
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.platform.storage.PendingComplaintSnapshot
import me.manga.kira.platform.storage.PendingReadResult
import me.manga.kira.platform.storage.InstallationCredentialRecord as Credential

// These read-only guards retain the caller's coordinator mutex; no independent lifecycle is introduced.
internal suspend fun InstallationCredentialStore.coordinationRecord(): Credential =
    when (val read = read()) {
        is CredentialReadResult.Present -> read.record
        CredentialReadResult.Missing -> refuse(Block.MISSING)
        is InstallationStorageFailure -> fail(read)
    }

internal suspend fun InstallationCredentialStore.exactRecord(expected: Credential) =
    coordinationRecord().also {
        if (!it.sameBinding(expected) || !it.sameAs(expected)) refuse(Block.STALE_BINDING)
    }

internal suspend fun InstallationCredentialStore.requireNoCleanupMarker() {
    when (val read = readCleanupMarker()) {
        CleanupMarkerReadResult.Missing -> Unit
        is CleanupMarkerReadResult.Present -> refuse(Block.CLEANUP_REQUIRED)
        is InstallationStorageFailure -> fail(read)
    }
}

internal suspend fun PendingComplaintActionStore.requireEmptyPending() {
    when (val read = read()) {
        is PendingReadResult.Verified -> if (!read.snapshot.isEmpty) refuse(Block.RECONCILIATION_REQUIRED)
        is InstallationStorageFailure -> fail(read)
    }
}

internal suspend fun PendingComplaintActionStore.reconciliationSnapshot(record: Credential): PendingComplaintSnapshot {
    val snapshot =
        when (val read = read()) {
            is PendingReadResult.Verified -> read.snapshot
            is InstallationStorageFailure -> fail(read)
        }
    when (val inspected = PendingComplaintTransitions.inspect(snapshot, record)) {
        is PendingComplaintInventory.Decoded -> Unit
        is PendingComplaintInventory.Quarantined ->
            when (inspected.reason) {
                PendingComplaintHold.CORRUPT_SLOT -> permanent(InstallationPermanentFailure.CORRUPT)
                PendingComplaintHold.TOO_LARGE -> permanent(InstallationPermanentFailure.TOO_LARGE)
                PendingComplaintHold.STALE_BINDING -> refuse(Block.STALE_BINDING)
                else -> refuse(Block.RECONCILIATION_REQUIRED)
            }
    }
    return snapshot
}

/** Read-only observation; the caller must retain its existing coordinator serialization across both reads. */
internal suspend fun unreadableObservation(
    credentials: InstallationCredentialStore,
    pending: PendingComplaintActionStore,
): CredentialReadResult =
    credentials.read().also {
        when (it) {
            is CredentialReadResult.Present -> refuse(Block.NOT_UNREADABLE)
            CredentialReadResult.Missing ->
                when (val inventory = pending.read()) {
                    is PendingReadResult.Verified -> if (inventory.snapshot.isEmpty) refuse(Block.NOT_UNREADABLE)
                    is InstallationStorageFailure -> requireUnreadable(inventory)
                }
            is InstallationStorageFailure -> requireUnreadable(it)
        }
    }
