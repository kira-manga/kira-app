package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
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
internal class ReportLiveHandle(
    val issuer: ReportConsumerIssuer,
    val request: ComplaintReportRequest,
    val origin: ReconciliationPermit,
) : ComplaintLiveReport {
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

    override fun toString(): String = "ComplaintLiveReport(redacted)"
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
