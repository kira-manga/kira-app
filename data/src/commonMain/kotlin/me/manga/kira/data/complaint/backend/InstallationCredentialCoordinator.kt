package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.PendingDeletion
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ServerTerminalFact
import me.manga.kira.platform.storage.CleanupMarkerCreateResult
import me.manga.kira.platform.storage.CleanupMarkerReadResult
import me.manga.kira.platform.storage.CleanupMarkerRemoveResult
import me.manga.kira.platform.storage.CredentialCleanupMarker
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.CredentialDeleteResult
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.PendingClearResult
import me.manga.kira.platform.storage.PendingComplaintActionStore

/**
 * Single-process lifecycle; the named enrollment operation alone adds bounded bootstrap/HTTP.
 * Every call is mutex serialized; cancellation/unknown exceptions propagate without destructive finally work.
 * Startup calls resumeCleanup before admission. Repository adapters must map results to AppResult, not prose.
 */
@Suppress("TooManyFunctions")
class InstallationCredentialCoordinator(
    private val credentials: InstallationCredentialStore,
    private val pending: PendingComplaintActionStore,
) {
    private val mutex = Mutex()
    private var reconciliationIssuer = ReconciliationIssuer()
    private var confirmation: Confirmation? = null

    /** Only an explicit initial candidate may fill a proven-empty store; an existing winner is reread. */
    suspend fun admit(candidate: InstallationCredentialRecord? = null): Outcome<Permit> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            pending.requireEmptyPending()
            val record =
                when (val read = credentials.read()) {
                    CredentialReadResult.Missing -> credentials.createCoordinated(candidate)
                    is CredentialReadResult.Present -> read.record
                    is InstallationStorageFailure -> fail(read)
                }
            active(record)
            Permit(record)
        }

    /** Rechecks and applies while locked. The callback must be bounded, non-suspending and perform no I/O. */
    suspend fun applyIfCurrent(
        permit: Permit,
        apply: () -> Unit,
    ): Outcome<Unit> =
        mutex.serialized {
            admission(permit)
            apply()
        }

    /** Existing-identity observation only; ordinary admission still refuses every nonempty inventory. */
    internal suspend fun beginReconciliation(): Outcome<ReconciliationPermit> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            val record = credentials.coordinationRecord().also(::active)
            ReconciliationPermit(record, pending.reconciliationSnapshot(record), reconciliationIssuer)
        }

    /** Exact read-only recheck; callback is bounded, non-suspending, and performs no I/O or dispatch. */
    internal suspend fun applyReconciliationIfCurrent(
        permit: ReconciliationPermit,
        apply: () -> Unit,
    ): Outcome<Unit> =
        mutex.serialized {
            if (permit.issuer !== reconciliationIssuer) refuse(Block.STALE_BINDING)
            noConsent()
            credentials.requireNoCleanupMarker()
            val record = credentials.exactRecord(permit.record).also(::active)
            if (!samePending(permit.snapshot, pending.reconciliationSnapshot(record))) refuse(Block.STALE_BINDING)
            apply()
        }

    /** Caller must later establish fresh-session/dispatch prerequisites; this only persists local intent. */
    suspend fun beginDeletion(
        permit: Permit,
        key: String,
    ): Outcome<PendingDeletion> =
        mutex.serialized {
            admission(permit)
            PendingDeletion(credentials.replaceCoordinated(permit.record, checked(permit.record.beginDeletion(key))))
        }

    /** Allows exact continuation after restart, including when opaque normal pending work still exists. */
    suspend fun pendingDeletion(): Outcome<PendingDeletion> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            val record = credentials.coordinationRecord().also(::deleting)
            PendingDeletion(record)
        }

    /** Blocks local admission/application until this precise prompt is confirmed or canceled. */
    suspend fun requestRecovery(intent: RecoveryIntent): Outcome<Confirmation> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            Confirmation(intent, validateIntent(intent)).also {
                confirmation = it
                reconciliationIssuer = ReconciliationIssuer()
            }
        }

    /** A stale prompt cannot cancel a newer one or undo a durable pending state. */
    suspend fun cancelRecovery(expected: Confirmation): Outcome<Unit> =
        mutex.serialized {
            if (confirmation !== expected) refuse(Block.STALE_CONSENT)
            confirmation = null
        }

    /** A confirmed warning is not server erasure; authorization is persisted in reason-specific order. */
    suspend fun confirmRecovery(expected: Confirmation): Outcome<Unit> =
        mutex.serialized {
            if (confirmation !== expected) refuse(Block.STALE_CONSENT)
            credentials.requireNoCleanupMarker()
            val observed = validateIntent(expected.intent)
            if (!sameObservation(observed, expected.observed)) refuse(Block.STALE_BINDING)
            confirmation = null
            when (val intent = expected.intent) {
                is RecoveryIntent.Reset -> {
                    val reset = checked(intent.permit.record.beginLocalReset())
                    val next = credentials.replaceCoordinated(intent.permit.record, reset)
                    authorizedCleanup(next, CredentialCleanupReason.USER_RESET_CONFIRMED)
                }
                RecoveryIntent.Unreadable -> finish(mark(null, CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED))
                is RecoveryIntent.Abandon ->
                    authorizedCleanup(intent.deletion.record, CredentialCleanupReason.REMOTE_DELETE_ABANDON_CONFIRMED)
            }
        }

    /** Synthetic input is not proof of a 204/410. Nonempty opaque pending cannot be terminal-qualified here. */
    suspend fun finishServerDeletion(fact: ServerTerminalFact): Outcome<Unit> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            deleting(credentials.exactRecord(fact.deletion.record))
            pending.requireEmptyPending()
            finish(mark(fact.deletion.record.localGeneration, CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED))
        }

    /** Resumes durable authority, never converts DELETION_PENDING alone into permission to erase. */
    suspend fun resumeCleanup(): Outcome<Unit> = mutex.serialized { resumeCleanupLocked() }

    /** One deadline owns both fixed exchanges and all coordinator checks, including mutex wait. */
    internal suspend fun enroll(
        http: InstallationEnrollmentHttp,
        generator: InstallationCredentialMaterialGenerator,
    ): InstallationEnrollmentResult<Unit> =
        withTimeoutOrNull(InstallationEnrollmentHttp.ATTEMPT_TIMEOUT_MS) {
            mutex
                .serialized {
                    resumeCleanupLocked()
                    val attempt = InstallationEnrollmentAttempt(credentials, pending, http, generator)
                    when (val prepared = attempt.prepare()) {
                        is InstallationEnrollmentResult.Failure -> prepared
                        is InstallationEnrollmentResult.Ready -> {
                            val permit = Permit(prepared.value)
                            admission(permit)
                            val result = attempt.send(permit.record)
                            if (result is InstallationEnrollmentResult.Ready) admission(permit)
                            attempt.checkPublication(result)
                        }
                    }
                }.enrollmentResult()
        } ?: InstallationEnrollmentResult.Failed(ComplaintSessionFailure.TIMEOUT)

    private suspend fun resumeCleanupLocked() {
        noConsent()
        when (val marker = credentials.readCleanupMarker()) {
            is CleanupMarkerReadResult.Present -> finish(marker.marker)
            is InstallationStorageFailure -> fail(marker)
            CleanupMarkerReadResult.Missing ->
                when (val read = credentials.read()) {
                    CredentialReadResult.Missing -> pending.requireEmptyPending()
                    is InstallationStorageFailure -> fail(read)
                    is CredentialReadResult.Present ->
                        when (read.record.state) {
                            InstallationCredentialState.LOCAL_RESET_PENDING ->
                                authorizedCleanup(read.record, CredentialCleanupReason.USER_RESET_CONFIRMED)
                            InstallationCredentialState.DELETION_PENDING -> refuse(Block.REMOTE_DELETION_PENDING)
                            InstallationCredentialState.ACTIVE -> Unit
                        }
                }
        }
    }

    private suspend fun admission(permit: Permit) {
        noConsent()
        credentials.requireNoCleanupMarker()
        pending.requireEmptyPending()
        active(credentials.exactRecord(permit.record))
    }

    private fun noConsent() {
        if (confirmation != null) refuse(Block.CONSENT_PENDING)
    }

    private suspend fun validateIntent(intent: RecoveryIntent): CredentialReadResult =
        when (intent) {
            is RecoveryIntent.Reset ->
                CredentialReadResult.Present(credentials.exactRecord(intent.permit.record).also(::active))
            is RecoveryIntent.Abandon ->
                CredentialReadResult.Present(credentials.exactRecord(intent.deletion.record).also(::deleting))
            RecoveryIntent.Unreadable -> unreadableObservation(credentials, pending)
        }

    private suspend fun clearPending() {
        when (val result = pending.clearForConfirmedRecovery()) {
            PendingClearResult.Cleared -> pending.requireEmptyPending()
            is InstallationStorageFailure -> fail(result)
        }
    }

    private suspend fun authorizedCleanup(
        record: InstallationCredentialRecord,
        reason: CredentialCleanupReason,
    ) {
        clearPending()
        finish(mark(record.localGeneration, reason))
    }

    private suspend fun mark(
        generation: Long?,
        reason: CredentialCleanupReason,
    ): CredentialCleanupMarker {
        val marker =
            checked(
                CredentialCleanupMarker.checked(InstallationCredentialRecord.SCHEMA_VERSION, generation, reason),
            )
        when (val result = credentials.createCleanupMarkerIfMissing(marker)) {
            CleanupMarkerCreateResult.Stored, CleanupMarkerCreateResult.AlreadyPresent -> matchingMarker(marker)
            is InstallationStorageFailure -> fail(result)
        }
        return marker
    }

    private suspend fun matchingMarker(expected: CredentialCleanupMarker) {
        when (val read = credentials.readCleanupMarker()) {
            is CleanupMarkerReadResult.Present ->
                if (!read.marker.sameAs(expected)) {
                    permanent(InstallationPermanentFailure.MARKER_CONFLICT)
                }
            CleanupMarkerReadResult.Missing -> permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
            is InstallationStorageFailure -> fail(read)
        }
    }

    private suspend fun finish(marker: CredentialCleanupMarker) {
        matchingMarker(marker)
        if (marker.reason == CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED) {
            when (val read = credentials.read()) {
                is CredentialReadResult.Present -> permanent(InstallationPermanentFailure.STATE_CHANGED)
                CredentialReadResult.Missing -> Unit
                is InstallationStorageFailure -> requireUnreadable(read)
            }
            clearPending()
        } else {
            pending.requireEmptyPending()
        }
        when (val result = credentials.finishMarkedCleanup(marker)) {
            CredentialDeleteResult.Deleted, CredentialDeleteResult.Missing -> absentCredential()
            CredentialDeleteResult.Stale -> refuse(Block.STALE_BINDING)
            is InstallationStorageFailure -> fail(result)
        }
        pending.requireEmptyPending()
        when (val result = credentials.removeCleanupMarker(marker)) {
            CleanupMarkerRemoveResult.Removed, CleanupMarkerRemoveResult.Missing -> credentials.requireNoCleanupMarker()
            CleanupMarkerRemoveResult.Stale -> permanent(InstallationPermanentFailure.MARKER_CONFLICT)
            is InstallationStorageFailure -> fail(result)
        }
        absentCredential()
        pending.requireEmptyPending()
    }

    private suspend fun absentCredential() {
        when (val read = credentials.read()) {
            CredentialReadResult.Missing -> Unit
            is CredentialReadResult.Present -> permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
            is InstallationStorageFailure -> fail(read)
        }
    }
}
