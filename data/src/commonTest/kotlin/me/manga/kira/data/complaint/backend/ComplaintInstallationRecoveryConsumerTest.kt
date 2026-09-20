package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintInstallationRecoveryConsumerTest {
    @Test
    fun unreadableRequestsUseFreshEligibilityAndTheExistingExactPromptIssuerWithoutSending() =
        runTest {
            val fixture = ComplaintReportFixture(this, InstallationCoordinatorFixture())
            val consumer = fixture.consumer(noRecoveryInputs())
            val foreign = fixture.consumer(noRecoveryInputs())
            try {
                assertUnreadableRefusals(fixture, consumer)
                fixture.storage.credentials.readFailure =
                    InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.INVALIDATED)
                val prompt = consumer.requestUnreadableRecovery().reportSuccess()
                assertIs<AppResult.Failure>(foreign.cancelRecovery(prompt))
                assertIs<AppResult.Failure>(foreign.confirmRecovery(prompt))
                assertIs<AppResult.Failure>(consumer.resumeCleanup())
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
                consumer.cancelRecovery(prompt).reportSuccess()
                consumer.confirmRecovery(consumer.requestUnreadableRecovery().reportSuccess()).reportSuccess()
                fixture.storage.assertAbsent()
                assertNoRecoveryNetwork(fixture)
            } finally {
                consumer.close()
                foreign.close()
                fixture.close()
            }
        }

    @Test
    fun existingDeletionAbandonmentRechecksItsTupleAndOnlyMarkedCleanupCanResumeAfterConsumedConsent() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val consumer = fixture.consumer(noRecoveryInputs())
            try {
                consumer.resumeCleanup().reportSuccess()
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
                assertStaleAbandonmentPreservesIdentity(fixture, consumer)
                assertFailedAbandonmentNeedsFreshWarning(fixture, consumer)
                assertMarkedAbandonmentResumes(fixture, consumer)
                assertNoRecoveryNetwork(fixture)
            } finally {
                consumer.close()
                fixture.close()
            }
        }
}

private suspend fun assertUnreadableRefusals(
    fixture: ComplaintReportFixture,
    recovery: ComplaintInstallationRecoveryRepository,
) {
    assertIs<AppResult.Failure>(recovery.requestUnreadableRecovery())
    fixture.storage.credentials.install(Fixtures.record())
    assertIs<AppResult.Failure>(recovery.requestUnreadableRecovery())
    val unavailable =
        listOf(
            InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.LOCKED),
            InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED),
        )
    for (failure in unavailable) {
        fixture.storage.credentials.readFailure = failure
        assertIs<AppResult.Failure>(recovery.requestUnreadableRecovery())
    }
    assertTrue(
        fixture.storage.faults.mutations
            .isEmpty(),
    )
}

private suspend fun assertStaleAbandonmentPreservesIdentity(
    fixture: ComplaintReportFixture,
    consumer: BackendComplaintReportRepository,
) {
    assertIs<AppResult.Failure>(consumer.requestDeletionAbandonment())
    val deletion =
        Fixtures.record(generation = 2, state = InstallationCredentialState.DELETION_PENDING, key = Fixtures.KEY)
    fixture.storage.credentials.install(deletion)
    fixture.storage.pending.slots += Fixtures.slot(1)
    val prompt = consumer.requestDeletionAbandonment().reportSuccess()
    val replacement =
        Fixtures.record(generation = 2, state = InstallationCredentialState.DELETION_PENDING, key = Fixtures.OTHER_ID)
    fixture.storage.credentials.install(replacement)
    assertIs<AppResult.Failure>(consumer.confirmRecovery(prompt))
    assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(replacement))
    assertTrue(
        fixture.storage.faults.mutations
            .isEmpty(),
    )
    consumer.cancelRecovery(prompt).reportSuccess()
    fixture.storage.credentials.install(deletion)
}

private suspend fun assertFailedAbandonmentNeedsFreshWarning(
    fixture: ComplaintReportFixture,
    consumer: BackendComplaintReportRepository,
) {
    val prompt = consumer.requestDeletionAbandonment().reportSuccess()
    fixture.storage.faults.failAt(Step.MARKER_CREATE_BEFORE)
    assertIs<AppResult.Failure>(consumer.confirmRecovery(prompt))
    assertIs<AppResult.Failure>(consumer.confirmRecovery(prompt))
    assertNull(fixture.storage.credentials.marker)
    assertIs<AppResult.Failure>(consumer.resumeCleanup())
    assertTrue(fixture.storage.credentials.keyPresent)
    assertTrue(fixture.storage.credentials.payloadPresent)
    fixture.storage.faults.clearFaults()
}

private suspend fun assertMarkedAbandonmentResumes(
    fixture: ComplaintReportFixture,
    consumer: BackendComplaintReportRepository,
) {
    val fresh = consumer.requestDeletionAbandonment().reportSuccess()
    fixture.storage.faults.failAt(Step.CLEANUP_BEFORE)
    assertIs<AppResult.Failure>(consumer.confirmRecovery(fresh))
    assertNotNull(fixture.storage.credentials.marker)
    fixture.storage.faults.clearFaults()
    consumer.resumeCleanup().reportSuccess()
    fixture.storage.assertAbsent()
}

private fun noRecoveryInputs(): ComplaintReportInputs =
    ComplaintReportInputs(
        { error("recovery must not allocate report IDs") },
        { error("recovery must not capture report metadata") },
    )

private fun assertNoRecoveryNetwork(fixture: ComplaintReportFixture) {
    assertTrue(fixture.sessionRequests.isEmpty())
    assertTrue(fixture.requests.isEmpty())
}
