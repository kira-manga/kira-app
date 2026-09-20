package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintEditAuthorityTest {
    @Test
    fun copiedOrPreviousEditStatusCannotClearAnotherKeyEvenWithTheSameTargetAndFingerprint() =
        runTest {
            for (applied in listOf(false, true)) assertEditExchangeIdentity(applied)
        }

    @Test
    fun finalAckAndRejectionApplicationIndependentlyRejectCredentialConsentResetDeletionAndCloseDrift() =
        runTest {
            for (direct in listOf(false, true)) {
                for (change in editFinalAuthorityChanges()) {
                    assertEditFinalFence(direct, change)
                }
            }
        }

    @Test
    fun finalOwnerDelete204AndTerminalStatusUseTheSameCredentialConsentResetDeletionAndCloseFences() =
        runTest {
            for (direct in listOf(false, true)) {
                for (change in editFinalAuthorityChanges()) {
                    assertEditFinalFence(direct, change, deletion = true)
                }
            }
        }
}

private suspend fun TestScope.assertEditExchangeIdentity(applied: Boolean) {
    val f = editExchangeIdentityFixture(applied)
    val a = mobileEditSlot()
    val b = mobileEditSlot(mobileEditRequest(key = MUTATION_OTHER_KEY))
    f.storage.pending.slots += listOf(a, b)
    val work = assertNotNull(f.works.begin(Job()))
    try {
        val first = f.boundEditStatus(work, a)
        val copy = ReportExchange.EditStatus(first.binding, first.session, first.request, first.result)
        assertRefused(Block.STALE_BINDING, f.coordinator.applyReportOutcome(copy, f.sessions))
        assertNull(work.application())
        f.coordinator.releaseReportAction(first.binding).success()
        val second = f.boundEditStatus(work, b)
        assertRefused(Block.STALE_BINDING, f.coordinator.applyReportOutcome(first, f.sessions))
        assertNull(work.application())
        assertEquals(2, f.storage.pending.slots.size)
        f.assertExactEditCleanupRetry(second, a)
    } finally {
        f.coordinator.finishReport(work)
        work.job.cancel()
        f.close()
    }
}

private fun TestScope.editExchangeIdentityFixture(applied: Boolean): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = {
            respond(
                if (applied) mobileEditApplied() else mobileEditRejected(),
                HttpStatusCode.OK,
                mobileEditHeaders(direct = false),
            )
        },
    )

private suspend fun ComplaintReportFixture.boundEditStatus(
    work: ReportWork,
    slot: PendingComplaintSlot,
): ReportExchange.EditStatus {
    val binding = coordinator.beginReportAction(work, ReportStart.Retained(slot)).success()
    return coordinator.readEditStatus(binding, readySession(binding), sessions, http).success()
}

private suspend fun ComplaintReportFixture.assertExactEditCleanupRetry(
    outcome: ReportExchange.EditStatus,
    untouched: PendingComplaintSlot,
) {
    var appliedBeforeDelete = false
    storage.faults.onStep = { step ->
        if (step == Step.PENDING_DELETE_BEFORE) {
            assertIs<ReportActionState.Edit>(outcome.binding.work.application())
            appliedBeforeDelete = true
        }
    }
    storage.faults.failAt(Step.PENDING_DELETE_BEFORE)
    assertStorageFailure(InstallationStoreFaults.ioFailure, coordinator.applyReportOutcome(outcome, sessions))
    assertTrue(appliedBeforeDelete)
    assertEquals(2, storage.pending.slots.size)
    assertIs<ReportActionState.Edit>(coordinator.applyReportOutcome(outcome, sessions).success().application)
    assertTrue(untouched.sameAs(storage.pending.slots.single()))
}

private suspend fun TestScope.assertEditFinalFence(direct: Boolean, change: String, deletion: Boolean = false) {
    val f = editFinalFenceFixture(direct, deletion)
    val ordinary = f.coordinator.admit().success()
    val work = assertNotNull(f.works.begin(Job()))
    try {
        val exchange = if (deletion) f.readyOwnerDeleteExchange(work, direct) else f.readyEditExchange(work, direct)
        val slot = f.storage.pending.slots.single()
        f.changeFinalEditAuthority(ordinary, change)
        val mutations = f.storage.faults.mutations.toList()
        assertRefused(Block.STALE_BINDING, f.coordinator.applyReportOutcome(exchange, f.sessions))
        assertTrue(currentCoroutineContext().isActive)
        assertNull(work.application())
        assertEquals(mutations, f.storage.faults.mutations)
        assertTrue(slot.sameAs(f.storage.pending.slots.single()))
    } finally {
        f.coordinator.finishReport(work)
        work.job.cancel()
        f.close()
    }
}

private suspend fun ComplaintReportFixture.readyEditExchange(work: ReportWork, direct: Boolean): ReportExchange =
    if (direct) {
        val start = coordinator.beginReportAction(work, ReportStart.New(mobileEditRequest())).success()
        val session = readySession(start)
        val prepared = coordinator.prepareReport(start, session, sessions).success()
        coordinator.dispatchEdit(prepared, session, sessions, http).success().also {
            assertIs<ComplaintEditHttpResult.Applied>(it.result)
        }
    } else {
        val slot = mobileEditSlot()
        storage.pending.slots += slot
        boundEditStatus(work, slot).also { assertIs<ComplaintEditStatusHttpResult.Rejected>(it.result) }
    }

private suspend fun ComplaintReportFixture.readyOwnerDeleteExchange(work: ReportWork, direct: Boolean): ReportExchange =
    if (direct) {
        val start = coordinator.beginReportAction(work, ReportStart.New(mobileOwnerDeleteRequest())).success()
        val session = readySession(start)
        val prepared = coordinator.prepareReport(start, session, sessions).success()
        coordinator.dispatchOwnerDelete(prepared, session, sessions, http).success().also {
            assertIs<ComplaintOwnerDeleteHttpResult.Applied>(it.result)
        }
    } else {
        val slot = mobileOwnerDeleteSlot()
        storage.pending.slots += slot
        val binding = coordinator.beginReportAction(work, ReportStart.Retained(slot)).success()
        coordinator.readOwnerDeleteStatus(binding, readySession(binding), sessions, http).success().also {
            assertIs<ComplaintOwnerDeleteStatusHttpResult.Rejected>(it.result)
        }
    }

private fun TestScope.editFinalFenceFixture(direct: Boolean, deletion: Boolean): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = {
            if (deletion && direct) {
                respond("", HttpStatusCode.NoContent, mobileOwnerDeleteHeaders())
            } else {
                val text =
                    when {
                        deletion -> mobileOwnerDeleteRejected()
                        direct -> mobileEditAck()
                        else -> mobileEditRejected()
                    }
                respond(text, HttpStatusCode.OK, mobileEditHeaders(direct = direct))
            }
        },
    )

private fun editFinalAuthorityChanges(): List<String> =
    listOf("version", "generation", "installation", "scope", "consent", "reset", "deletion", "close")

private suspend fun ComplaintReportFixture.changeFinalEditAuthority(ordinary: Permit, change: String) {
    when (change) {
        "version" -> storage.credentials.install(Fixtures.record(version = 2))
        "generation" -> storage.credentials.install(Fixtures.record(generation = 2))
        "installation" -> storage.credentials.install(Fixtures.record(Fixtures.material(id = Fixtures.OTHER_ID)))
        "scope" -> storage.credentials.install(Fixtures.record(Fixtures.material(scope = MOBILE_EDIT_SCOPE)))
        "deletion" ->
            storage.credentials.install(
                Fixtures.record(
                    generation = 2,
                    state = InstallationCredentialState.DELETION_PENDING,
                    key = Fixtures.KEY,
                ),
            )
        "close" -> works.close()
        else -> {
            val retained = storage.pending.slots.toList()
            val prompt = coordinator.requestRecovery(RecoveryIntent.Reset(ordinary)).success()
            if (change == "reset") {
                coordinator.confirmRecovery(prompt).success()
                storage.credentials.install(Fixtures.record())
                storage.pending.slots += retained
            } else {
                coordinator.cancelRecovery(prompt).success()
            }
        }
    }
}
