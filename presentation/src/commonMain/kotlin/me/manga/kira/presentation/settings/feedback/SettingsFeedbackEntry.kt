package me.manga.kira.presentation.settings.feedback

import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintReportDraft

/** Immutable per-opening mode. Fixed subjects are captured localized labels, never live suppliers. */
sealed interface SettingsFeedbackEntry {
    data object General : SettingsFeedbackEntry

    /** Captures the localized subject for a body-only SITES_ADD request. */
    class SourceRequest(
        val localizedSubject: String,
    ) : SettingsFeedbackEntry {
        override fun toString(): String = "SettingsFeedbackEntry.SourceRequest(redacted)"
    }

    /** Captures the localized subject for a body-only LANGUAGES request. */
    class LanguageRequest(
        val localizedSubject: String,
    ) : SettingsFeedbackEntry {
        override fun toString(): String = "SettingsFeedbackEntry.LanguageRequest(redacted)"
    }
}

internal fun SettingsFeedbackEntry.initialDraft(): ComplaintReportDraft =
    when (this) {
        SettingsFeedbackEntry.General -> ComplaintReportDraft()
        is SettingsFeedbackEntry.SourceRequest ->
            ComplaintReportDraft(type = ComplaintType.SITES_ADD, subject = localizedSubject)
        is SettingsFeedbackEntry.LanguageRequest ->
            ComplaintReportDraft(type = ComplaintType.LANGUAGES, subject = localizedSubject)
    }
