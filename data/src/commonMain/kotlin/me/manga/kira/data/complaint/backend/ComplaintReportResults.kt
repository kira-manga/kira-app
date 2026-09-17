package me.manga.kira.data.complaint.backend

import me.manga.kira.core.error.AppError
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot

/** A result carries the original live object, never replacement IDs or reconstructed prose. */
internal sealed interface ReportAttempt {
    val liveReport: ComplaintReportRequest?

    class Completed(
        override val liveReport: ComplaintReportRequest?,
        val application: ReportActionState,
    ) : ReportAttempt {
        override fun toString(): String = "ReportAttempt.Completed(redacted)"
    }

    /** Uncertain writes may have changed storage; this is not an assertion that a slot is absent/present. */
    class Unresolved(
        override val liveReport: ComplaintReportRequest?,
        val failure: ReportFailure,
        val application: ReportActionState? = null,
        val pending: ReportPendingObservation? = null,
    ) : ReportAttempt {
        override fun toString(): String = "ReportAttempt.Unresolved(redacted)"
    }
}

internal class ReportFailure(
    val error: AppError,
    val block: Block? = null,
)

internal class ReportRecoveryItem(
    val slot: PendingComplaintSlot,
    val attempt: ReportAttempt,
    val permit: ReconciliationPermit,
) {
    override fun toString(): String = "ReportRecoveryItem(redacted)"
}

/** Exact read-only observation, containing no live prose, worker or independent action authority. */
internal class ReportPendingObservation(
    val slot: PendingComplaintSlot,
    val permit: ReconciliationPermit,
) {
    override fun toString(): String = "ReportPendingObservation(redacted)"
}

/** At most the initial sixteen metadata-only observations, not an authoritative inventory or permission. */
internal class ReportRecovery(
    items: List<ReportRecoveryItem>,
    val stopped: ReportFailure? = null,
) {
    private val content = items.toList()

    init {
        require(content.size <= PendingComplaintSnapshot.MAX_SLOTS)
    }

    fun entries(): List<ReportRecoveryItem> = content.toList()

    override fun toString(): String = "ReportRecovery(redacted)"
}

internal class ReportSubmission(
    val attempt: ReportAttempt,
    val recovery: ReportRecovery,
) {
    override fun toString(): String = "ReportSubmission(redacted)"
}

internal class ReportExecution(
    val binding: ReportActionBinding,
    val attempt: ReportAttempt,
)

internal fun reportUnavailable(block: Block? = null): ReportFailure =
    ReportFailure(AppError.Platform.FeatureUnavailable("complaint_report"), block)

internal fun reportLocalFailure(outcome: Outcome<*>): ReportFailure =
    when (outcome) {
        is Outcome.StorageFailure -> ReportFailure(AppError.Storage.Io())
        is Outcome.Invalid -> ReportFailure(AppError.Network.Serialization())
        is Outcome.Refused -> reportUnavailable(outcome.reason)
        is Outcome.Success -> reportUnavailable()
    }

internal fun reportSessionFailure(result: ComplaintSessionResult): ReportFailure =
    when (result) {
        is ComplaintSessionResult.HttpFailure -> ReportFailure(AppError.Network.Http(result.status))
        is ComplaintSessionResult.LocalFailure -> reportLocalFailure(result.outcome)
        is ComplaintSessionResult.Failed ->
            when (result.reason) {
                ComplaintSessionFailure.TIMEOUT -> ReportFailure(AppError.Network.Timeout())
                ComplaintSessionFailure.TRANSPORT -> ReportFailure(AppError.Network.NoConnectivity())
                ComplaintSessionFailure.INVALIDATED -> ReportFailure(AppError.Auth.Forbidden())
                ComplaintSessionFailure.EXPIRED -> ReportFailure(AppError.Auth.TokenExpired())
                ComplaintSessionFailure.CLOSED -> reportUnavailable()
                else -> ReportFailure(AppError.Network.Serialization())
            }
        is ComplaintSessionResult.Ready -> reportUnavailable()
    }

internal fun reportMutationFailure(reason: ComplaintMutationFailure): ReportFailure =
    when (reason) {
        ComplaintMutationFailure.TIMEOUT -> ReportFailure(AppError.Network.Timeout())
        ComplaintMutationFailure.TRANSPORT -> ReportFailure(AppError.Network.NoConnectivity())
        ComplaintMutationFailure.INVALIDATED -> ReportFailure(AppError.Auth.Forbidden())
        ComplaintMutationFailure.RESPONSE -> ReportFailure(AppError.Network.Serialization())
        ComplaintMutationFailure.CLOSED -> reportUnavailable()
    }

internal fun ReportActionBinding.unresolved(failure: ReportFailure): ReportExecution =
    ReportExecution(
        this,
        ReportAttempt.Unresolved(liveReport, failure, work.application(), slot?.let { ReportPendingObservation(it, permit) }),
    )
