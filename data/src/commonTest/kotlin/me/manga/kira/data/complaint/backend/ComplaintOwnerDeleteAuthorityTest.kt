package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintOwnerDeleteAuthorityTest {
    @Test
    fun noEchoDeleteReceiptsStillRejectCopiedOrPreviousExchangesAndApplyBeforeExactSlotCleanup() =
        runTest {
            for (applied in listOf(false, true)) assertOwnerDeleteExchangeIdentity(applied)
        }
}

private suspend fun TestScope.assertOwnerDeleteExchangeIdentity(applied: Boolean) {
    val f = ownerDeleteExchangeFixture(applied)
    val firstSlot = mobileOwnerDeleteSlot()
    val secondSlot = mobileOwnerDeleteSlot(mobileOwnerDeleteRequest(key = MUTATION_OTHER_KEY))
    f.storage.pending.slots += listOf(firstSlot, secondSlot)
    val work = assertNotNull(f.works.begin(Job()))
    try {
        val first = f.boundOwnerDeleteStatus(work, firstSlot)
        val copy = ReportExchange.OwnerDeleteStatus(first.binding, first.session, first.request, first.result)
        assertRefused(Block.STALE_BINDING, f.coordinator.applyReportOutcome(copy, f.sessions))
        assertNull(work.application())
        f.coordinator.releaseReportAction(first.binding).success()
        val second = f.boundOwnerDeleteStatus(work, secondSlot)
        assertRefused(Block.STALE_BINDING, f.coordinator.applyReportOutcome(first, f.sessions))
        assertNull(work.application())
        assertEquals(2, f.storage.pending.slots.size)
        f.assertExactOwnerDeleteCleanup(second, firstSlot)
    } finally {
        f.coordinator.finishReport(work)
        work.job.cancel()
        f.close()
    }
}

private fun TestScope.ownerDeleteExchangeFixture(applied: Boolean): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = {
            respond(
                if (applied) MOBILE_OWNER_DELETE_APPLIED else mobileOwnerDeleteRejected(),
                HttpStatusCode.OK,
                mutationHeaders(),
            )
        },
    )

private suspend fun ComplaintReportFixture.boundOwnerDeleteStatus(
    work: ReportWork,
    slot: PendingComplaintSlot,
): ReportExchange.OwnerDeleteStatus {
    val binding = coordinator.beginReportAction(work, ReportStart.Retained(slot)).success()
    return coordinator.readOwnerDeleteStatus(binding, readySession(binding), sessions, http).success()
}

private suspend fun ComplaintReportFixture.assertExactOwnerDeleteCleanup(
    outcome: ReportExchange.OwnerDeleteStatus,
    untouched: PendingComplaintSlot,
) {
    var appliedBeforeDelete = false
    storage.faults.onStep = { step ->
        if (step == Step.PENDING_DELETE_BEFORE) {
            assertIs<ReportActionState.OwnerDelete>(outcome.binding.work.application())
            appliedBeforeDelete = true
        }
    }
    storage.faults.failAt(Step.PENDING_DELETE_BEFORE)
    assertStorageFailure(InstallationStoreFaults.ioFailure, coordinator.applyReportOutcome(outcome, sessions))
    assertTrue(appliedBeforeDelete)
    assertEquals(2, storage.pending.slots.size)
    assertIs<ReportActionState.OwnerDelete>(coordinator.applyReportOutcome(outcome, sessions).success().application)
    assertTrue(untouched.sameAs(storage.pending.slots.single()))
}
