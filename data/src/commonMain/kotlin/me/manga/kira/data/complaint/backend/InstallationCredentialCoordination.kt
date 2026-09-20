package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationValueIssue
import me.manga.kira.platform.storage.InstallationValueResult
import me.manga.kira.platform.storage.PendingComplaintSnapshot

/** Local coordination values only; none establishes entropy, server consent or transport authority. */
object InstallationCredentialCoordination {
    /** Protocol refusal, typed storage failure and structural rejection remain distinct. */
    sealed interface Outcome<out T> {
        class Success<T>(
            val value: T,
        ) : Outcome<T> {
            override fun toString(): String = "InstallationCoordination.Success(redacted)"
        }

        data class Refused(
            val reason: Block,
        ) : Outcome<Nothing>

        data class StorageFailure(
            val failure: InstallationStorageFailure,
        ) : Outcome<Nothing>

        data class Invalid(
            val issue: InstallationValueIssue,
        ) : Outcome<Nothing>
    }

    /** No refusal is permission to clear evidence or enroll a replacement identity. */
    enum class Block {
        MISSING,
        STALE_BINDING,
        CONSENT_PENDING,
        STALE_CONSENT,
        CLEANUP_REQUIRED,
        REMOTE_DELETION_PENDING,
        RECONCILIATION_REQUIRED,
        ACTION_IN_PROGRESS,
        PENDING_CAPACITY_REACHED,
        LIVE_REQUEST_REQUIRED,
        RECEIPT_WINDOW_EXPIRED,
        NOT_UNREADABLE,
        INVALID_CANDIDATE,
    }

    /** Exact immutable binding; not a promise that HTTP may dispatch after the mutex unlocks. */
    class Permit internal constructor(
        internal val record: InstallationCredentialRecord,
    ) {
        override fun toString(): String = "InstallationPermit(redacted)"
    }

    /** Read-only reconciliation binding; never new-mutation, replay or erasure authority. */
    internal class ReconciliationPermit internal constructor(
        internal val record: InstallationCredentialRecord,
        internal val snapshot: PendingComplaintSnapshot,
        internal val issuer: ReconciliationIssuer,
    ) {
        internal fun sameAs(other: ReconciliationPermit): Boolean =
            issuer === other.issuer && record.sameAs(other.record) && samePending(snapshot, other.snapshot)

        override fun toString(): String = "InstallationReconciliationPermit(redacted)"
    }

    /** Retains the exact durable tuple/key for delete-all continuation, not local erasure. */
    class PendingDeletion internal constructor(
        internal val record: InstallationCredentialRecord,
    ) {
        override fun toString(): String = "PendingInstallationDeletion(redacted)"
    }

    /** Three different warned recovery requests; no force Boolean or deletion-to-reset downgrade. */
    sealed interface RecoveryIntent {
        class Reset(
            val permit: Permit,
        ) : RecoveryIntent

        data object Unreadable : RecoveryIntent

        class Abandon(
            val deletion: PendingDeletion,
        ) : RecoveryIntent
    }

    /** Process-local prompt token: only its exact outstanding instance can confirm or cancel. */
    class Confirmation internal constructor(
        internal val intent: RecoveryIntent,
        internal val observed: CredentialReadResult,
    ) {
        override fun toString(): String = "InstallationRecoveryConfirmation(redacted)"
    }

    /**
     * Reserved for a future validated terminal-response producer in data; none is installed here.
     * Constructing this in a test asserts a prerequisite; it does not prove remote erasure or a 204/410.
     */
    class ServerTerminalFact internal constructor(
        internal val deletion: PendingDeletion,
    ) {
        override fun toString(): String = "InstallationServerTerminalFact(redacted)"
    }
}

/** Only content-free local refusals are caught; cancellation and unexpected exceptions propagate. */
internal suspend fun <T> Mutex.serialized(action: suspend () -> T): Outcome<T> =
    withLock {
        try {
            Outcome.Success(action())
        } catch (stop: CoordinationStop) {
            stop.outcome
        }
    }

internal fun refuse(block: Block): Nothing = throw CoordinationStop(Outcome.Refused(block))

internal fun fail(failure: InstallationStorageFailure): Nothing =
    throw CoordinationStop(
        Outcome.StorageFailure(failure),
    )

internal fun permanent(reason: InstallationPermanentFailure): Nothing =
    fail(
        InstallationStorageFailure.PermanentFailure(reason),
    )

internal fun <T> checked(result: InstallationValueResult<T>): T =
    when (result) {
        is InstallationValueResult.Valid -> result.value
        is InstallationValueResult.Invalid -> throw CoordinationStop(Outcome.Invalid(result.issue))
    }

// Coordinator-only guards: callers hold the same coordinator serialization; helpers own no mutex or lifecycle state.
internal fun deleting(record: InstallationCredentialRecord) {
    if (record.state != InstallationCredentialState.DELETION_PENDING) refuse(Block.STALE_BINDING)
}

internal fun active(record: InstallationCredentialRecord) =
    when (record.state) {
        InstallationCredentialState.ACTIVE -> Unit
        InstallationCredentialState.LOCAL_RESET_PENDING -> refuse(Block.CLEANUP_REQUIRED)
        InstallationCredentialState.DELETION_PENDING -> refuse(Block.REMOTE_DELETION_PENDING)
    }

internal fun requireUnreadable(failure: InstallationStorageFailure) {
    val reason = (failure as? InstallationStorageFailure.PermanentFailure)?.reason
    when (reason) {
        InstallationPermanentFailure.CORRUPT,
        InstallationPermanentFailure.INVALIDATED,
        InstallationPermanentFailure.TOO_LARGE,
        -> Unit
        else -> fail(failure)
    }
}

internal fun sameObservation(
    a: CredentialReadResult,
    b: CredentialReadResult,
): Boolean =
    if (a is CredentialReadResult.Present && b is CredentialReadResult.Present) {
        a.record.sameAs(b.record)
    } else {
        a == b
    }

internal class ReconciliationIssuer

internal fun samePending(
    expected: PendingComplaintSnapshot,
    actual: PendingComplaintSnapshot,
): Boolean =
    expected.size == actual.size &&
        expected.entries().all { old -> actual.entries().any { old.sameAs(it) } }

private class CoordinationStop(
    val outcome: Outcome<Nothing>,
) : Exception()
