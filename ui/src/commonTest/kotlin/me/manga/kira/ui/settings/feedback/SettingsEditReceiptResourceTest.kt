package me.manga.kira.ui.settings.feedback

import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintEditReceiptRejection
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.error_network_not_found
import me.manga.kira.ui.generated.resources.np_complaint_action_updated
import me.manga.kira.ui.generated.resources.request_failed
import me.manga.kira.ui.generated.resources.settings_report_applied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Pure resource selection only; no Compose UI, navigation or activated mixed-operation UX is exercised. */
class SettingsEditReceiptResourceTest {
    @Test
    fun editReceiptUsesExistingAccurateResourcesAndNeverTheReportAcceptedMessage() {
        val applied = settingsEditReceiptResource(ComplaintEditApplication.Applied("synthetic-target", 8))
        assertEquals(Res.string.np_complaint_action_updated, applied)
        assertNotEquals(Res.string.settings_report_applied, applied)
        for (code in ComplaintEditReceiptRejection.entries) {
            val expected =
                if (code == ComplaintEditReceiptRejection.COMPLAINT_NOT_FOUND) {
                    Res.string.error_network_not_found
                } else {
                    Res.string.request_failed
                }
            assertEquals(expected, settingsEditReceiptResource(ComplaintEditApplication.Rejected(code)))
        }
    }
}
