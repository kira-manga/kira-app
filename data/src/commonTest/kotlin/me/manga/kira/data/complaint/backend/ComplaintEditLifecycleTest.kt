package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintEditLifecycleTest {
    @Test
    fun editReportReplyAndStatusContendOnOneNonReplacingLaneEvenDuringCancelledHttpCleanup() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val f = heldEditFixture(entered, release)
            try {
                val first = async { f.repository.submit(mobileEditRequest()) }
                entered.await()
                f.assertOtherWritesBusy()
                assertTrue(first.isActive)
                f.repository.cancelCurrent()
                f.assertOtherWritesBusy()
                release.complete(Unit)
                assertFailsWith<CancellationException> { first.await() }
                assertEquals(1, f.requests.size)
                assertEquals(
                    PendingComplaintState.MAY_HAVE_DISPATCHED,
                    reportRecord(f.storage.pending.slots.single()).state,
                )
                assertTrue(Step.PENDING_DELETE_BEFORE !in f.storage.faults.trace)
            } finally {
                release.complete(Unit)
                f.close()
            }
        }

    @Test
    fun consentResetDeletionCancellationAndCloseDuringSessionCannotPublishAnEditOrPrepareMetadata() =
        runTest {
            for (change in listOf("consent", "reset", "deletion", "cancel", "close")) assertEditSessionFence(change)
        }
}

private fun TestScope.heldEditFixture(
    entered: CompletableDeferred<Unit>,
    release: CompletableDeferred<Unit>,
): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                respond(mobileEditAck(), HttpStatusCode.OK, mobileEditHeaders())
            }
        },
    )

private suspend fun ComplaintReportFixture.assertOtherWritesBusy() {
    assertIs<AppResult.Failure>(repository.submit(mutationReport(key = historyId(30))))
    assertIs<AppResult.Failure>(repository.submit(mobileReplyRequest(key = historyId(31))))
    assertIs<AppResult.Failure>(repository.submit(mobileEditRequest(key = historyId(32))))
    assertIs<AppResult.Failure>(repository.reconcile())
}

private suspend fun TestScope.assertEditSessionFence(change: String) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val f = heldEditSessionFixture(entered, release)
    val ordinary = f.coordinator.admit().success()
    try {
        val first = async { f.repository.submit(mobileEditRequest()) }
        entered.await()
        f.changeEditSessionLifetime(ordinary, change)
        release.complete(Unit)
        assertFailsWith<CancellationException> { first.await() }
        assertTrue(f.requests.isEmpty(), change)
        assertTrue(f.storage.pending.slots.isEmpty(), change)
        assertTrue(Step.PENDING_CREATE_BEFORE !in f.storage.faults.trace, change)
    } finally {
        release.complete(Unit)
        f.close()
    }
}

private fun TestScope.heldEditSessionFixture(
    entered: CompletableDeferred<Unit>,
    release: CompletableDeferred<Unit>,
): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        sessionHandler = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
            }
        },
    )

private suspend fun ComplaintReportFixture.changeEditSessionLifetime(ordinary: Permit, change: String) {
    when (change) {
        "consent", "reset" -> {
            val prompt = coordinator.requestRecovery(RecoveryIntent.Reset(ordinary)).success()
            if (change == "reset") {
                coordinator.confirmRecovery(prompt).success()
            } else {
                coordinator.cancelRecovery(prompt).success()
            }
        }
        "deletion" -> coordinator.beginDeletion(ordinary, Fixtures.KEY).success()
        "cancel" -> repository.cancelCurrent()
        "close" -> works.close()
    }
}
