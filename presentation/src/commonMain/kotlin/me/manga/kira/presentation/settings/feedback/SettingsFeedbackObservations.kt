package me.manga.kira.presentation.settings.feedback

import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportPending
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery

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
