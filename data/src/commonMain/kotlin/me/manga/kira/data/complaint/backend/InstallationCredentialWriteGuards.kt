package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.platform.storage.CredentialCreateResult
import me.manga.kira.platform.storage.CredentialReplaceResult
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
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
