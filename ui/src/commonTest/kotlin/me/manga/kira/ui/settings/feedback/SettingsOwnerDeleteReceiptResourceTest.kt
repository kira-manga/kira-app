package me.manga.kira.ui.settings.feedback

import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteReceiptRejection
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.error_network_not_found
import me.manga.kira.ui.generated.resources.np_complaint_action_deleted
import me.manga.kira.ui.generated.resources.np_complaint_action_updated
import me.manga.kira.ui.generated.resources.request_failed
import me.manga.kira.ui.generated.resources.settings_report_applied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Pure existing-resource selection, not Compose automation or an activated deletion UI. */
class SettingsOwnerDeleteReceiptResourceTest {
    @Test
    fun ownerDeleteReceiptIsNeitherReportAcceptedNorEditUpdatedAndKeepsClosedRejections() {
        val applied = settingsOwnerDeleteReceiptResource(ComplaintOwnerDeleteApplication.Applied)
        assertEquals(Res.string.np_complaint_action_deleted, applied)
        assertNotEquals(Res.string.settings_report_applied, applied)
        assertNotEquals(Res.string.np_complaint_action_updated, applied)
        for (code in ComplaintOwnerDeleteReceiptRejection.entries) {
            val expected =
                if (code == ComplaintOwnerDeleteReceiptRejection.COMPLAINT_NOT_FOUND) {
                    Res.string.error_network_not_found
                } else {
                    Res.string.request_failed
                }
            assertEquals(expected, settingsOwnerDeleteReceiptResource(ComplaintOwnerDeleteApplication.Rejected(code)))
        }
    }
}
