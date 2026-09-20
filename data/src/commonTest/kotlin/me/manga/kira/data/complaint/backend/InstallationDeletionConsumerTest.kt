package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation
import me.manga.kira.domain.repository.ComplaintInstallationDeletionPrompt
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

/** Six consumer-boundary proofs using the existing real coordinator/session/HTTP producer fixture. */
@OptIn(ExperimentalTime::class)
class InstallationDeletionConsumerTest {
    @Test
    fun warningAndCancellationAreReadOnlyShareLocalConsentAndKeepTheReportEpoch() {
        runTest {
            val fixture = InstallationDeletionFixture(this)
            val foreign = InstallationDeletionFixture(this)
            val job = Job()
            val work = assertNotNull(ReportWorkOwner().begin(job))
            fixture.storage.pending.slots += consumerSlots()
            try {
                val original = fixture.coordinator.reportInventory(work).success()
                val prompt = fixture.repository.requestDeletion().reportSuccess()
                assertTrue(work.isCurrent())
                assertIs<AppResult.Failure>(fixture.repository.requestDeletion())
                assertRefused(Block.CONSENT_PENDING, fixture.coordinator.beginReconciliation())
                val local = RecoveryIntent.Reset(Permit(Fixtures.record()))
                assertRefused(Block.CONSENT_PENDING, fixture.coordinator.requestRecovery(local))
                assertIs<AppResult.Failure>(foreign.repository.confirmDeletion(prompt))
                val foreignPrompt = foreign.repository.requestDeletion().reportSuccess()
                assertIs<AppResult.Failure>(fixture.repository.cancelDeletion(foreignPrompt))
                assertIs<AppResult.Failure>(fixture.repository.confirmDeletion(FabricatedDeletionPrompt()))
                fixture.repository.cancelDeletion(prompt).reportSuccess()
                val newer = fixture.repository.requestDeletion().reportSuccess()
                assertIs<AppResult.Failure>(fixture.repository.cancelDeletion(prompt))
                assertIs<AppResult.Failure>(fixture.repository.confirmDeletion(prompt))
                assertRefused(Block.CONSENT_PENDING, fixture.coordinator.beginReconciliation())
                fixture.repository.cancelDeletion(newer).reportSuccess()
                fixture.coordinator.applyReconciliationIfCurrent(original) {}.success()
                assertSame(
                    original.issuer,
                    fixture.coordinator
                        .beginReconciliation()
                        .success()
                        .issuer,
                )
                assertTrue(work.isCurrent())
                val localPrompt = fixture.coordinator.requestRecovery(local).success()
                assertIs<AppResult.Failure>(fixture.repository.requestDeletion())
                fixture.coordinator.cancelRecovery(localPrompt).success()
                foreign.repository.cancelDeletion(foreignPrompt).reportSuccess()
                fixture.assertConsumerDidNotStart()
                foreign.assertConsumerDidNotStart()
                assertFalse(prompt.toString().contains(Fixtures.ID))
            } finally {
                fixture.coordinator.finishReport(work)
                job.cancel()
                fixture.close()
                foreign.close()
            }
        }
    }

    @Test
    fun changedIdentityTupleOrPendingInventoryCannotStartFromAnEarlierWarning() {
        runTest {
            for (change in consumerConfirmationDrift()) {
                val fixture = InstallationDeletionFixture(this)
                fixture.storage.pending.slots += sessionPendingSlot(dispatched = false)
                try {
                    val prompt = fixture.repository.requestDeletion().reportSuccess()
                    change(fixture.storage)
                    assertIs<AppResult.Failure>(fixture.repository.confirmDeletion(prompt))
                    fixture.assertConsumerDidNotStart()
                    fixture.repository.cancelDeletion(prompt).reportSuccess()
                } finally {
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun replacementAfterExactConfirmationObservationCannotBeRecapturedForFreshSession() {
        runTest {
            val fixture = InstallationDeletionFixture(this)
            val original = fixture.coordinator.beginReconciliation().success()
            val replacement = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            try {
                val prompt = fixture.repository.requestDeletion().reportSuccess()
                var replaced = false
                fixture.storage.faults.onStep = { step ->
                    if (step == Step.PENDING_READ && !replaced) {
                        replaced = true
                        // The confirm path has read the original credential, but not yet transferred consent.
                        fixture.storage.credentials.install(replacement)
                    }
                }
                assertIs<AppResult.Failure>(fixture.repository.confirmDeletion(prompt))
                assertTrue(replaced)
                fixture.assertConsumerDidNotStart()
                fixture.assertRetained(replacement)
                assertIs<AppResult.Failure>(fixture.repository.cancelDeletion(prompt))
                assertNotSame(
                    original.issuer,
                    fixture.coordinator
                        .beginReconciliation()
                        .success()
                        .issuer,
                )
                assertRefused(Block.STALE_BINDING, fixture.coordinator.applyReconciliationIfCurrent(original) {})
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun canceledOrClosedPromptDeliveryDismissesOnlyTheUndeliveredOwnedWarning() {
        runTest {
            for (closeOwner in listOf(false, true)) {
                val fixture = InstallationDeletionFixture(this)
                var intercepted = false
                fixture.storage.faults.onStep = { step ->
                    if (step == Step.PENDING_READ && !intercepted) {
                        intercepted = true
                        if (closeOwner) fixture.close() else currentCoroutineContext().job.cancel()
                    }
                }
                try {
                    val caller = async { fixture.repository.requestDeletion() }
                    if (closeOwner) {
                        assertIs<AppResult.Failure>(caller.await())
                    } else {
                        assertFailsWith<CancellationException> { caller.await() }
                    }
                    fixture.storage.faults.clearFaults()
                    fixture.coordinator.beginReconciliation().success()
                    val newer =
                        fixture.coordinator.requestRecovery(RecoveryIntent.Reset(Permit(Fixtures.record()))).success()
                    assertIs<AppResult.Failure>(fixture.repository.requestDeletion())
                    assertRefused(Block.CONSENT_PENDING, fixture.coordinator.beginReconciliation())
                    fixture.coordinator.cancelRecovery(newer).success()
                    fixture.assertConsumerDidNotStart()
                } finally {
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun genuineConfirmationUsesFreshActualSessionAndOnlyExplicitContinuationReusesDurableTupleAndKey() {
        runTest {
            val fixture = acceptedConsumerDeletion()
            val job = Job()
            val work = assertNotNull(ReportWorkOwner().begin(job))
            val slots = consumerSlots()
            fixture.storage.pending.slots += slots
            try {
                val original = fixture.coordinator.reportInventory(work).success()
                assertIs<ComplaintSessionResult.Ready>(fixture.sessions.session())
                val prompt = fixture.repository.requestDeletion().reportSuccess()
                assertEquals(1, fixture.sessionRequests.size)
                assertTrue(work.isCurrent())
                assertEquals(1, assertDeletionPending(fixture.repository.confirmDeletion(prompt)).retryAfterSeconds)
                assertFalse(work.isCurrent())
                assertRefused(Block.STALE_BINDING, fixture.coordinator.applyReconciliationIfCurrent(original) {})
                fixture.assertRetained(deletingRecord(), slots)
                assertEquals(2, fixture.sessionRequests.size)
                assertEquals(1, fixture.keyCalls)
                assertEquals(1, fixture.requests.size)
                assertDeletionPending(fixture.repository.continueDeletion())
                assertEquals(1, fixture.requests.size)
                fixture.settings.clock += 2.seconds
                assertDeletionCompleted(fixture.repository.continueDeletion())
                assertEquals(2, fixture.requests.size)
                assertEquals(fixture.bodies.first(), fixture.bodies.last())
                assertTrue(fixture.requests.all { it.headers[Policy.IDEMPOTENCY_HEADER] == Fixtures.KEY })
                assertEquals(2, fixture.sessionRequests.size)
                assertEquals(1, fixture.keyCalls)
                assertTrue(Step.CREATE_BEFORE !in fixture.storage.faults.trace)
                fixture.storage.assertAbsent()
            } finally {
                fixture.coordinator.finishReport(work)
                job.cancel()
                fixture.close()
            }
        }
    }

    @Test
    fun localObservationClassifiesMarkersAndInventoryWithoutHttpMutationOrCompletionInference() {
        runTest {
            for (case in consumerObservations()) {
                val fixture = InstallationDeletionFixture(this)
                case.change(fixture.storage)
                try {
                    val result = fixture.repository.observeDeletion()
                    if (case.expected == null) {
                        assertIs<AppResult.Failure>(result)
                    } else {
                        assertEquals(case.expected, result.reportSuccess())
                    }
                    case.lastRead?.let {
                        assertEquals(
                            it,
                            fixture.storage.faults.trace
                                .last(),
                        )
                    }
                    fixture.assertConsumerDidNotStart()
                } finally {
                    fixture.close()
                }
            }
        }
    }
}

private fun InstallationDeletionFixture.assertConsumerDidNotStart() {
    assertEquals(0, keyCalls)
    assertTrue(sessionRequests.isEmpty() && requests.isEmpty())
    assertTrue(storage.faults.mutations.isEmpty())
}

private fun consumerSlots(): List<PendingComplaintSlot> =
    List(PendingComplaintSnapshot.MAX_SLOTS) {
        sessionPendingSlot(key = historyId(CONSUMER_KEY_OFFSET + it))
    }

private fun consumerConfirmationDrift(): List<(InstallationCoordinatorFixture) -> Unit> =
    listOf(
        Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID)),
        Fixtures.record(material = Fixtures.material(secret = "B".repeat(42) + "E")),
        Fixtures.record(material = Fixtures.material(scope = historyId(CONSUMER_KEY_OFFSET))),
        Fixtures.record(material = Fixtures.material(platform = "IOS")),
        Fixtures.record(version = 2),
        Fixtures.record(generation = 2),
        Fixtures.record().beginLocalReset().valid(),
    ).map { replacement -> { storage: InstallationCoordinatorFixture -> storage.credentials.install(replacement) } } +
        listOf(
            { storage: InstallationCoordinatorFixture -> storage.pending.slots.clear() },
            { storage: InstallationCoordinatorFixture ->
                storage.pending.slots += sessionPendingSlot(key = historyId(CONSUMER_KEY_OFFSET))
            },
            { storage: InstallationCoordinatorFixture ->
                storage.pending.slots[0] = sessionPendingSlot(dispatched = true)
            },
        )

private fun TestScope.acceptedConsumerDeletion(): InstallationDeletionFixture {
    val storage = InstallationCoordinatorFixture(Fixtures.record())
    var exchanges = 0
    return InstallationDeletionFixture(
        this,
        storage,
        deletionHandler = {
            assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(deletingRecord()))
            assertEquals(PendingComplaintSnapshot.MAX_SLOTS, storage.pending.slots.size)
            val readback =
                storage.faults.trace
                    .dropWhile { it != Step.REPLACE_STORED }
                    .drop(1)
            assertTrue(Step.CREDENTIAL_READ in readback)
            exchanges++
            val status = if (exchanges == 1) HttpStatusCode.Accepted else HttpStatusCode.NoContent
            respond("", status, deletionHeaders(status))
        },
    )
}

private class ConsumerObservation(
    val expected: ComplaintInstallationDeletionObservation?,
    val change: (InstallationCoordinatorFixture) -> Unit = {},
    val lastRead: Step? = null,
)

private fun consumerObservations(): List<ConsumerObservation> {
    val corrupt = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT)
    val cases =
        listOf(
            ConsumerObservation(ComplaintInstallationDeletionObservation.Active),
            ConsumerObservation(
                ComplaintInstallationDeletionObservation.Active,
                { it.pending.slots += consumerSlots() },
            ),
            ConsumerObservation(ComplaintInstallationDeletionObservation.Missing, { it.credentials.removePieces() }),
            ConsumerObservation(
                null,
                {
                    it.credentials.removePieces()
                    it.pending.slots += Fixtures.slot(1)
                },
            ),
            ConsumerObservation(null, { it.pending.slots += Fixtures.slot(1) }),
            ConsumerObservation(
                ComplaintInstallationDeletionObservation.RemoteDeletionPending,
                {
                    it.credentials.install(deletingRecord())
                    it.pending.readFailure = corrupt
                },
                Step.CREDENTIAL_READ,
            ),
            ConsumerObservation(
                ComplaintInstallationDeletionObservation.RemoteDeletionPending,
                {
                    it.credentials.marker = Fixtures.marker(2, CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED)
                    it.credentials.readFailure = corrupt
                    it.pending.readFailure = corrupt
                },
                Step.MARKER_READ,
            ),
            ConsumerObservation(
                ComplaintInstallationDeletionObservation.LocalCleanupRequired,
                { it.credentials.install(Fixtures.record().beginLocalReset().valid()) },
                Step.CREDENTIAL_READ,
            ),
        )
    return cases + consumerLocalMarkers() + consumerReadErrors()
}

private fun consumerLocalMarkers(): List<ConsumerObservation> =
    CredentialCleanupReason.entries
        .filterNot { it == CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED }
        .map { reason ->
            ConsumerObservation(
                ComplaintInstallationDeletionObservation.LocalCleanupRequired,
                {
                    val generation = if (reason == CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED) null else 2L
                    it.credentials.marker = Fixtures.marker(generation, reason)
                },
                Step.MARKER_READ,
            )
        }

private fun consumerReadErrors(): List<ConsumerObservation> =
    listOf(
        InstallationStoreFaults.ioFailure,
        InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT),
        InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED),
    ).flatMap { failure ->
        listOf(
            ConsumerObservation(null, { it.credentials.markerFailure = failure }),
            ConsumerObservation(null, { it.credentials.readFailure = failure }),
            ConsumerObservation(null, { it.pending.readFailure = failure }),
        )
    }

private class FabricatedDeletionPrompt : ComplaintInstallationDeletionPrompt

private const val CONSUMER_KEY_OFFSET = 30
