package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import me.manga.kira.domain.model.complaint.ComplaintHistoryPlatform
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerFields
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintEditDraft
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.repository.ComplaintEditRepository
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

internal fun mobileEditInputs(key: () -> String = { Fixtures.KEY }): ComplaintReportInputs =
    ComplaintReportInputs(
        identifiers = { error("edit must not generate a creation ID") },
        metadata = { error("edit must not read device diagnostics") },
        editKey = key,
    )

internal fun mobileEditFields(
    id: String = MOBILE_EDIT_ID,
    version: Long = MOBILE_EDIT_VERSION,
    tag: String = "\"complaint-$id-v$version\"",
    status: ComplaintHistoryStatus = ComplaintHistoryStatus.Known(ComplaintStatus.UNKNOWN),
): ComplaintOwnerFields =
    ComplaintOwnerFields(
        id = id,
        body = "Original body must not be sent",
        status = status,
        createdAt = Instant.parse(SESSION_ISSUED_AT),
        updatedAt = Instant.parse(SESSION_ISSUED_AT),
        version = version,
        actionTag = tag,
        appVersion = "Original diagnostics must not be sent",
        platform = ComplaintHistoryPlatform.ANDROID,
        osVersion = null,
        manufacturer = null,
        deviceModel = null,
        closureReason = null,
    )

internal fun mobileEditRow(
    fields: ComplaintOwnerFields = mobileEditFields(),
    type: ComplaintHistoryType = ComplaintHistoryType.Known(ComplaintType.TECHNICAL),
): ComplaintOwnerRow.Report = ComplaintOwnerRow.Report(fields, type, "Original subject")

internal fun mobileEditDraft(
    target: ComplaintOwnerRow = mobileEditRow(),
    subject: String? = " Synthetic edit ",
    body: String = " Line 1\r\nLine 2 ",
): ComplaintEditDraft = ComplaintEditDraft(target, subject, body)

internal suspend fun ComplaintEditRepository.preparedConsumerEdit(
    draft: ComplaintEditDraft = mobileEditDraft(),
): ComplaintLiveEdit = assertIs<ComplaintEditPreparation.Ready>(prepare(draft).reportSuccess()).edit

internal fun TestScope.assertMobileEditSupplierUnlocked(fixture: ComplaintReportFixture) {
    val probe = launch(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.beginReconciliation().success() }
    try {
        assertTrue(probe.isCompleted, "edit key supplier must run outside the credential mutex")
    } finally {
        probe.cancel()
    }
}

internal fun ComplaintReportFixture.assertMobileEditUntouched() {
    assertTrue(storage.faults.mutations.isEmpty())
    assertTrue(requests.isEmpty())
    assertTrue(sessionRequests.isEmpty())
}
