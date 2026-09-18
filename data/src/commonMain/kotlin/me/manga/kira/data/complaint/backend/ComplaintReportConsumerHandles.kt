package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.model.feedback.ComplaintLiveOwnerDelete
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Handle provenance/lifetime only, not a registry or credential/report admission authority. */
@OptIn(ExperimentalAtomicApi::class)
internal class ReportConsumerIssuer {
    private val open = AtomicBoolean(true)

    fun isOpen(): Boolean = open.load()

    fun close() = open.store(false)
}

/** The handle itself retains the exact immutable normalized request; the singleton stores no prose map. */
@OptIn(ExperimentalAtomicApi::class)
internal sealed class ComplaintOwnerLiveHandle(
    val issuer: ReportConsumerIssuer,
    val origin: ReconciliationPermit,
) {
    abstract val request: ComplaintOwnerRequest
    private val submitted = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val application = AtomicReference<ComplaintReportApplication?>(null)

    fun claimSubmission(): Boolean = submitted.compareAndSet(expectedValue = false, newValue = true)

    fun canRetry(): Boolean = submitted.load() && !completed.load()

    /** Preserve an already-known receipt if a later exact retry cannot resolve durable cleanup. */
    fun remember(result: ComplaintReportAttempt): ComplaintReportAttempt =
        when (result) {
            is ComplaintReportAttempt.Completed -> {
                application.store(result.application)
                completed.store(true)
                result
            }
            is ComplaintReportAttempt.Unresolved -> {
                result.knownApplication?.let(application::store)
                ComplaintReportAttempt.Unresolved(result.failure, application.load(), result.pending)
            }
        }

    override fun toString(): String = "ComplaintLiveOwnerAction(redacted)"
}

/** Creation handles remain creation-typed; edits cannot be passed to their wire producer. */
internal sealed class ComplaintCreationLiveHandle(
    issuer: ReportConsumerIssuer,
    origin: ReconciliationPermit,
) : ComplaintOwnerLiveHandle(issuer, origin) {
    abstract override val request: ComplaintCreationRequest
}

internal class EditLiveHandle(
    issuer: ReportConsumerIssuer,
    override val request: ComplaintEditRequest,
    origin: ReconciliationPermit,
) : ComplaintOwnerLiveHandle(issuer, origin),
    ComplaintLiveEdit {
    override fun toString(): String = "ComplaintLiveEdit(redacted)"
}

internal class OwnerDeleteLiveHandle(
    issuer: ReportConsumerIssuer,
    override val request: ComplaintOwnerDeleteRequest,
    origin: ReconciliationPermit,
) : ComplaintOwnerLiveHandle(issuer, origin),
    ComplaintLiveOwnerDelete {
    override fun toString(): String = "ComplaintLiveOwnerDelete(redacted)"
}

internal class ReportLiveHandle(
    issuer: ReportConsumerIssuer,
    override val request: ComplaintReportRequest,
    origin: ReconciliationPermit,
) : ComplaintCreationLiveHandle(issuer, origin),
    ComplaintLiveReport {
    override fun toString(): String = "ComplaintLiveReport(redacted)"
}

internal class ReplyLiveHandle(
    issuer: ReportConsumerIssuer,
    override val request: ComplaintReplyRequest,
    origin: ReconciliationPermit,
) : ComplaintCreationLiveHandle(issuer, origin),
    ComplaintLiveReply {
    override fun toString(): String = "ComplaintLiveReply(redacted)"
}

internal class ReportPendingHandle(
    val issuer: ReportConsumerIssuer,
    val observation: ReportPendingObservation,
) : ComplaintPendingReport {
    override fun toString(): String = "ComplaintPendingReport(redacted)"
}

internal class ReportPromptHandle(
    val issuer: ReportConsumerIssuer,
    val confirmation: Confirmation,
) : ComplaintRecoveryPrompt {
    override fun toString(): String = "ComplaintRecoveryPrompt(redacted)"
}
