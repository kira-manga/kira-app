package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.platform.storage.CredentialCreateResult
import me.manga.kira.platform.storage.CredentialReplaceResult
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot
import me.manga.kira.platform.storage.PendingCreateResult
import me.manga.kira.platform.storage.PendingDeleteResult
import me.manga.kira.platform.storage.PendingReadResult
import me.manga.kira.platform.storage.PendingReplaceResult
import me.manga.kira.platform.storage.InstallationCredentialRecord as CredentialRecord

// The caller must hold the existing coordinator mutex, including through full-record readback.
// These stateless store primitives own no lock and confer no admission, dispatch or cleanup authority.
internal suspend fun InstallationCredentialStore.createCoordinated(candidate: CredentialRecord?): CredentialRecord {
    if (candidate == null) refuse(Block.MISSING)
    if (!candidate.isInitialCandidate) refuse(Block.INVALID_CANDIDATE)
    return when (val result = createIfMissing(candidate)) {
        CredentialCreateResult.Stored -> readBack(candidate)
        CredentialCreateResult.AlreadyPresent -> coordinationRecord()
        is InstallationStorageFailure -> fail(result)
    }
}

internal suspend fun InstallationCredentialStore.replaceCoordinated(
    old: CredentialRecord,
    next: CredentialRecord,
): CredentialRecord =
    when (val result = replace(old.localGeneration, next)) {
        CredentialReplaceResult.Stored -> readBack(next)
        CredentialReplaceResult.Missing, CredentialReplaceResult.Stale -> refuse(Block.STALE_BINDING)
        is InstallationStorageFailure -> fail(result)
    }

private suspend fun InstallationCredentialStore.readBack(expected: CredentialRecord) =
    coordinationRecord().also {
        if (!it.sameAs(expected)) permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
    }

/** The adapter's Stored claim is not the coordinator's independent inventory readback. */
internal suspend fun PendingComplaintActionStore.createPendingCoordinated(
    inventory: PendingComplaintSnapshot,
    replacement: PendingComplaintSlot,
): PendingComplaintSnapshot {
    if (inventory.entries().any { it.id == replacement.id }) refuse(Block.STALE_BINDING)
    val expected = checked(PendingComplaintSnapshot.checked(inventory.entries() + replacement))
    when (val result = createIfMissing(replacement)) {
        PendingCreateResult.Stored -> Unit
        PendingCreateResult.AlreadyPresent -> refuse(Block.STALE_BINDING)
        is InstallationStorageFailure -> fail(result)
    }
    return readBackPending(expected)
}

internal suspend fun PendingComplaintActionStore.replacePendingCoordinated(
    inventory: PendingComplaintSnapshot,
    expected: PendingComplaintSlot,
    replacement: PendingComplaintSlot,
): PendingComplaintSnapshot {
    if (expected.id != replacement.id || inventory.entries().none { it.sameAs(expected) }) refuse(Block.STALE_BINDING)
    val next =
        checked(PendingComplaintSnapshot.checked(inventory.entries().map { if (it.id == expected.id) replacement else it }))
    when (val result = replace(expected, replacement)) {
        PendingReplaceResult.Stored -> Unit
        PendingReplaceResult.Missing, PendingReplaceResult.Stale -> refuse(Block.STALE_BINDING)
        is InstallationStorageFailure -> fail(result)
    }
    return readBackPending(next)
}

internal suspend fun PendingComplaintActionStore.deletePendingCoordinated(
    inventory: PendingComplaintSnapshot,
    expected: PendingComplaintSlot,
): PendingComplaintSnapshot {
    if (inventory.entries().none { it.sameAs(expected) }) refuse(Block.STALE_BINDING)
    val next = checked(PendingComplaintSnapshot.checked(inventory.entries().filterNot { it.id == expected.id }))
    when (val result = delete(expected)) {
        PendingDeleteResult.Deleted -> Unit
        PendingDeleteResult.Missing, PendingDeleteResult.Stale -> refuse(Block.STALE_BINDING)
        is InstallationStorageFailure -> fail(result)
    }
    return readBackPending(next)
}

private suspend fun PendingComplaintActionStore.readBackPending(expected: PendingComplaintSnapshot): PendingComplaintSnapshot =
    when (val result = read()) {
        is PendingReadResult.Verified -> {
            if (!samePending(expected, result.snapshot)) permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
            result.snapshot
        }
        is InstallationStorageFailure -> fail(result)
    }
