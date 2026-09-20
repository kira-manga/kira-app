package me.manga.kira.data.complaint.backend

import me.manga.kira.platform.storage.CleanupMarkerCreateResult
import me.manga.kira.platform.storage.CleanupMarkerReadResult
import me.manga.kira.platform.storage.CleanupMarkerRemoveResult
import me.manga.kira.platform.storage.CredentialCleanupMarker
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.CredentialCreateResult
import me.manga.kira.platform.storage.CredentialDeleteResult
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.CredentialReplaceResult
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step
import me.manga.kira.platform.storage.CredentialResetResult as ResetResult

/** Retains separate pieces across real coordinator reconstruction; never claims native sync/crypto. */
internal class InstallationCredentialStoreFake(
    private val faults: InstallationStoreFaults,
) : InstallationCredentialStore {
    var keyPresent = false
    var payloadPresent = false
    var payloadRecord: InstallationCredentialRecord? = null
    var readFailure: InstallationStorageFailure? = null
    var marker: CredentialCleanupMarker? = null
    var markerFailure: InstallationStorageFailure? = null

    // Deliberately dishonest adapter success, for the coordinator's independent absence check.
    var cleanupReply: CredentialDeleteResult? = null

    fun install(record: InstallationCredentialRecord) {
        keyPresent = true
        payloadPresent = true
        payloadRecord = record
        readFailure = null
    }

    fun removePieces() {
        keyPresent = false
        payloadPresent = false
        payloadRecord = null
        readFailure = null
    }

    override suspend fun read(): CredentialReadResult {
        faults.visit(Step.CREDENTIAL_READ)?.let { return it }
        return retainedRead()
    }

    override suspend fun createIfMissing(record: InstallationCredentialRecord): CredentialCreateResult {
        faults.visit(Step.CREATE_BEFORE)?.let { return it }
        return markerFailure ?: when {
            keyPresent || payloadPresent || marker != null -> CredentialCreateResult.AlreadyPresent
            else ->
                readFailure ?: if (!record.isInitialCandidate) {
                    permanent(InstallationPermanentFailure.STATE_CHANGED)
                } else {
                    install(record)
                    faults.visit(Step.CREATE_STORED) ?: CredentialCreateResult.Stored
                }
        }
    }

    override suspend fun replace(
        expectedGeneration: Long,
        record: InstallationCredentialRecord,
    ): CredentialReplaceResult {
        val failure = faults.visit(Step.REPLACE_BEFORE) ?: markerFailure
        if (failure != null) return failure
        return when {
            marker != null -> permanent(InstallationPermanentFailure.MARKER_CONFLICT)
            else ->
                when (val read = retainedRead()) {
                    CredentialReadResult.Missing -> CredentialReplaceResult.Missing
                    is InstallationStorageFailure -> read
                    is CredentialReadResult.Present ->
                        when {
                            read.record.localGeneration != expectedGeneration -> CredentialReplaceResult.Stale
                            !validReplacement(read.record, record) ->
                                permanent(InstallationPermanentFailure.STATE_CHANGED)
                            else -> {
                                install(record)
                                faults.visit(Step.REPLACE_STORED) ?: CredentialReplaceResult.Stored
                            }
                        }
                }
        }
    }

    override suspend fun delete(
        expectedGeneration: Long,
        expectedMarker: CredentialCleanupMarker,
    ): CredentialDeleteResult {
        if (expectedMarker.expectedGeneration != expectedGeneration) return CredentialDeleteResult.Stale
        return finishMarkedCleanup(expectedMarker)
    }

    override suspend fun resetUnreadableAfterConfirmation(expectedMarker: CredentialCleanupMarker): ResetResult {
        if (expectedMarker.expectedGeneration != null) return permanent(InstallationPermanentFailure.STATE_CHANGED)
        return when (val result = finishMarkedCleanup(expectedMarker)) {
            CredentialDeleteResult.Deleted -> ResetResult.Deleted
            CredentialDeleteResult.Missing -> ResetResult.Missing
            CredentialDeleteResult.Stale -> permanent(InstallationPermanentFailure.STATE_CHANGED)
            is InstallationStorageFailure -> result
        }
    }

    override suspend fun finishMarkedCleanup(expectedMarker: CredentialCleanupMarker): CredentialDeleteResult {
        val refusal = faults.visit(Step.CLEANUP_BEFORE) ?: cleanupReply ?: cleanupRefusal(expectedMarker)
        return refusal ?: if (!keyPresent && !payloadPresent) {
            CredentialDeleteResult.Missing
        } else {
            val keyFailure =
                if (keyPresent) {
                    keyPresent = false
                    faults.visit(Step.KEY_REMOVED)
                } else {
                    null
                }
            keyFailure ?: finishPayloadCleanup()
        }
    }

    override suspend fun readCleanupMarker(): CleanupMarkerReadResult {
        faults.visit(Step.MARKER_READ)?.let { return it }
        return markerFailure ?: marker?.let { CleanupMarkerReadResult.Present(it) } ?: CleanupMarkerReadResult.Missing
    }

    override suspend fun createCleanupMarkerIfMissing(marker: CredentialCleanupMarker): CleanupMarkerCreateResult {
        faults.visit(Step.MARKER_CREATE_BEFORE)?.let { return it }
        return markerFailure ?: if (this.marker != null) {
            CleanupMarkerCreateResult.AlreadyPresent
        } else {
            this.marker = marker
            faults.visit(Step.MARKER_STORED) ?: CleanupMarkerCreateResult.Stored
        }
    }

    override suspend fun removeCleanupMarker(expectedMarker: CredentialCleanupMarker): CleanupMarkerRemoveResult {
        val failure = faults.visit(Step.MARKER_REMOVE_BEFORE) ?: markerFailure
        if (failure != null) return failure
        return when {
            keyPresent || payloadPresent -> permanent(InstallationPermanentFailure.STATE_CHANGED)
            else ->
                when (val stored = marker) {
                    null -> CleanupMarkerRemoveResult.Missing
                    else ->
                        if (!stored.sameAs(expectedMarker)) {
                            CleanupMarkerRemoveResult.Stale
                        } else {
                            marker = null
                            faults.visit(Step.MARKER_REMOVED) ?: CleanupMarkerRemoveResult.Removed
                        }
                }
        }
    }

    private suspend fun finishPayloadCleanup(): CredentialDeleteResult {
        if (payloadPresent) {
            payloadPresent = false
            payloadRecord = null
            readFailure = null
            faults.visit(Step.PAYLOAD_REMOVED)?.let { return it }
        }
        readFailure = null
        return CredentialDeleteResult.Deleted
    }

    private fun retainedRead(): CredentialReadResult {
        readFailure?.let { return it }
        return when {
            !keyPresent && !payloadPresent -> CredentialReadResult.Missing
            !keyPresent -> permanent(InstallationPermanentFailure.INVALIDATED)
            !payloadPresent -> permanent(InstallationPermanentFailure.CORRUPT)
            else ->
                payloadRecord?.let { CredentialReadResult.Present(it) }
                    ?: permanent(InstallationPermanentFailure.CORRUPT)
        }
    }

    private fun validReplacement(
        old: InstallationCredentialRecord,
        next: InstallationCredentialRecord,
    ): Boolean =
        old.state == InstallationCredentialState.ACTIVE &&
            next.state != InstallationCredentialState.ACTIVE &&
            old.material.sameAs(next.material) &&
            old.credentialVersion == next.credentialVersion &&
            old.localGeneration != Long.MAX_VALUE &&
            next.localGeneration == old.localGeneration + 1

    private fun cleanupRefusal(expected: CredentialCleanupMarker): CredentialDeleteResult? {
        markerFailure?.let { return it }
        val stored = marker
        return when {
            stored == null -> CredentialDeleteResult.Stale
            !stored.sameAs(expected) -> CredentialDeleteResult.Stale
            else ->
                when (val read = retainedRead()) {
                    CredentialReadResult.Missing -> null
                    is CredentialReadResult.Present ->
                        if (!matchesReason(read.record, expected)) CredentialDeleteResult.Stale else null
                    is InstallationStorageFailure -> if (!unreadable(read)) read else null
                }
        }
    }

    private fun matchesReason(
        record: InstallationCredentialRecord,
        marker: CredentialCleanupMarker,
    ): Boolean {
        if (marker.expectedGeneration != record.localGeneration) return false
        return when (marker.reason) {
            CredentialCleanupReason.USER_RESET_CONFIRMED ->
                record.state == InstallationCredentialState.LOCAL_RESET_PENDING
            CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED,
            CredentialCleanupReason.REMOTE_DELETE_ABANDON_CONFIRMED,
            -> record.state == InstallationCredentialState.DELETION_PENDING
            CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED -> false
        }
    }

    private fun unreadable(failure: InstallationStorageFailure): Boolean =
        failure is InstallationStorageFailure.PermanentFailure &&
            failure.reason in
            setOf(
                InstallationPermanentFailure.CORRUPT,
                InstallationPermanentFailure.INVALIDATED,
                InstallationPermanentFailure.TOO_LARGE,
            )

    private fun permanent(reason: InstallationPermanentFailure): InstallationStorageFailure =
        InstallationStorageFailure.PermanentFailure(reason)
}
