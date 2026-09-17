package me.manga.kira.presentation.settings.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportPending
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery

internal fun AppResult<ComplaintReportPreparation>.preparationResult(): ComplaintReportPreparation =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> ComplaintReportPreparation.Blocked(ComplaintReportFailure(error))
    }

/** Only a bounded MISSING preparation result opens the explicit history/setup action. */
internal fun ComplaintReportPreparation.isMissingPreparation(): Boolean =
    this is ComplaintReportPreparation.Blocked && failure.block == ComplaintReportBlock.MISSING

internal fun isPreparedReport(
    report: ComplaintPendingReport,
    live: ComplaintLiveReport?,
    latest: ComplaintReportAttempt?,
    recovery: ComplaintReportRecovery?,
): Boolean = findReportObservation(report, live, latest, recovery)?.phase == ComplaintReportPhase.PREPARED

internal fun ComplaintReportFailure.afterAttempt(previous: ComplaintReportAttempt?): ComplaintReportAttempt.Unresolved {
    val unresolved = previous as? ComplaintReportAttempt.Unresolved
    return ComplaintReportAttempt.Unresolved(this, unresolved?.knownApplication, unresolved?.pending)
}

/** A known applied/rejected receipt must survive a later failed cleanup/status observation. */
internal fun retainReportApplication(
    attempt: ComplaintReportAttempt,
    previous: ComplaintReportAttempt?,
): ComplaintReportAttempt =
    if (attempt is ComplaintReportAttempt.Unresolved) {
        val known = (previous as? ComplaintReportAttempt.Unresolved)?.knownApplication
        ComplaintReportAttempt.Unresolved(attempt.failure, attempt.knownApplication ?: known, attempt.pending)
    } else {
        attempt
    }

/** UI intent correlation only; the repository still freshly checks exact issuer/record/slot authority. */
internal fun findReportObservation(
    report: ComplaintPendingReport,
    live: ComplaintLiveReport?,
    attempt: ComplaintReportAttempt?,
    recovery: ComplaintReportRecovery?,
): ComplaintReportPending? {
    val current = (attempt as? ComplaintReportAttempt.Unresolved)?.pending
    if (live != null) return current?.takeIf { it.handle === report }
    return recovery
        ?.entries()
        ?.firstOrNull { it.pending.handle === report && it.attempt is ComplaintReportAttempt.Unresolved }
        ?.pending
}

internal fun ComplaintReportRecovery.without(report: ComplaintPendingReport): ComplaintReportRecovery =
    ComplaintReportRecovery(entries().filterNot { it.pending.handle === report }, stopped)
