package me.manga.kira.ui.settings.feedback

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintEditReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportField
import me.manga.kira.domain.model.feedback.ComplaintReportReceiptRejection
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.complaint_features
import me.manga.kira.ui.generated.resources.complaint_languages
import me.manga.kira.ui.generated.resources.complaint_other
import me.manga.kira.ui.generated.resources.complaint_site_error
import me.manga.kira.ui.generated.resources.complaint_sites_add
import me.manga.kira.ui.generated.resources.complaint_technical
import me.manga.kira.ui.generated.resources.error_network_not_found
import me.manga.kira.ui.generated.resources.np_complaint_action_updated
import me.manga.kira.ui.generated.resources.request_failed
import me.manga.kira.ui.generated.resources.request_feedback_missing_installation
import me.manga.kira.ui.generated.resources.settings_report_applied
import me.manga.kira.ui.generated.resources.settings_report_capacity_rejected
import me.manga.kira.ui.generated.resources.settings_report_cleanup_pending
import me.manga.kira.ui.generated.resources.settings_report_id_rejected
import me.manga.kira.ui.generated.resources.settings_report_invalid_body
import me.manga.kira.ui.generated.resources.settings_report_invalid_metadata
import me.manga.kira.ui.generated.resources.settings_report_invalid_subject
import me.manga.kira.ui.generated.resources.settings_report_live_required
import me.manga.kira.ui.generated.resources.settings_report_pending_capacity
import me.manga.kira.ui.generated.resources.settings_report_receipt_expired
import me.manga.kira.ui.generated.resources.settings_report_unavailable
import me.manga.kira.ui.generated.resources.settings_report_unresolved
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun settingsReportCategoryText(type: ComplaintType): String =
    when (type) {
        ComplaintType.TECHNICAL -> stringResource(Res.string.complaint_technical)
        ComplaintType.LANGUAGES -> stringResource(Res.string.complaint_languages)
        ComplaintType.SITES_ADD -> stringResource(Res.string.complaint_sites_add)
        ComplaintType.SITE_ERROR -> stringResource(Res.string.complaint_site_error)
        ComplaintType.FEATURES -> stringResource(Res.string.complaint_features)
        ComplaintType.CUSTOM -> stringResource(Res.string.complaint_other)
    }

@Composable
internal fun settingsReportInvalidText(field: ComplaintReportField): String =
    when (field) {
        ComplaintReportField.SUBJECT -> stringResource(Res.string.settings_report_invalid_subject)
        ComplaintReportField.BODY -> stringResource(Res.string.settings_report_invalid_body)
        ComplaintReportField.APP_VERSION,
        ComplaintReportField.OS_VERSION,
        ComplaintReportField.MANUFACTURER,
        ComplaintReportField.DEVICE_MODEL,
        -> stringResource(Res.string.settings_report_invalid_metadata)
    }

/** No exception text, HTTP body, identifier, raw metadata, or opaque handle is ever rendered. */
@Composable
internal fun settingsReportFailureText(failure: ComplaintReportFailure): String =
    when (failure.block) {
        ComplaintReportBlock.MISSING -> stringResource(Res.string.request_feedback_missing_installation)
        ComplaintReportBlock.PENDING_CAPACITY_REACHED -> stringResource(Res.string.settings_report_pending_capacity)
        ComplaintReportBlock.RECEIPT_WINDOW_EXPIRED -> stringResource(Res.string.settings_report_receipt_expired)
        ComplaintReportBlock.LIVE_REQUEST_REQUIRED -> stringResource(Res.string.settings_report_live_required)
        else -> stringResource(Res.string.settings_report_unavailable)
    }

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsReportAttemptSummary(attempt: ComplaintReportAttempt) {
    when (attempt) {
        is ComplaintReportAttempt.Completed -> Text(settingsReportApplicationText(attempt.application))
        is ComplaintReportAttempt.Unresolved -> {
            val application = attempt.knownApplication
            if (application == null) {
                Text(stringResource(Res.string.settings_report_unresolved))
            } else {
                Text(settingsReportApplicationText(application))
                Text(stringResource(Res.string.settings_report_cleanup_pending))
            }
            Text(settingsReportFailureText(attempt.failure))
        }
    }
}

@Composable
private fun settingsReportApplicationText(application: ComplaintReportApplication): String =
    when (application) {
        is ComplaintReportApplication.Applied -> stringResource(Res.string.settings_report_applied)
        is ComplaintReportApplication.Rejected ->
            when (application.code) {
                ComplaintReportReceiptRejection.COMPLAINT_CAPACITY_REACHED ->
                    stringResource(Res.string.settings_report_capacity_rejected)
                ComplaintReportReceiptRejection.COMPLAINT_RESOURCE_ID_REUSED ->
                    stringResource(Res.string.settings_report_id_rejected)
                ComplaintReportReceiptRejection.COMPLAINT_PARENT_NOT_FOUND ->
                    stringResource(Res.string.error_network_not_found)
                ComplaintReportReceiptRejection.COMPLAINT_DELETION_PENDING ->
                    stringResource(Res.string.request_failed)
            }
        is ComplaintReportApplication.Edit -> stringResource(settingsEditReceiptResource(application.application))
    }

/** Compatibility for an original edit receipt, not editor controls or a current row/version claim. */
internal fun settingsEditReceiptResource(application: ComplaintEditApplication): StringResource =
    when (application) {
        is ComplaintEditApplication.Applied -> Res.string.np_complaint_action_updated
        is ComplaintEditApplication.Rejected ->
            when (application.code) {
                ComplaintEditReceiptRejection.COMPLAINT_NOT_FOUND -> Res.string.error_network_not_found
                ComplaintEditReceiptRejection.COMPLAINT_INVALID_TRANSITION,
                ComplaintEditReceiptRejection.COMPLAINT_NO_CHANGE,
                ComplaintEditReceiptRejection.COMPLAINT_DELETION_PENDING,
                ComplaintEditReceiptRejection.PRECONDITION_FAILED,
                -> Res.string.request_failed
            }
    }
