package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

@OptIn(ExperimentalTime::class)
class InstallationDeletionStartTest {
    @Test
    fun constructionAndObservationAreInertAndEvenACachedTokenRequiresAnotherActualSession() =
        runTest {
            val fixture = InstallationDeletionFixture(this)
            try {
                fixture.coordinator.beginReconciliation().success()
                assertTrue(fixture.sessionRequests.isEmpty() && fixture.requests.isEmpty())
                assertEquals(0, fixture.keyCalls)
                assertIs<ComplaintSessionResult.Ready>(fixture.sessions.session())
                assertDeletionCompleted(fixture.repository.startDeletion())
                assertEquals(2, fixture.sessionRequests.size)
                assertEquals(1, fixture.requests.size)
                assertEquals(1, fixture.keyCalls)
                fixture.storage.assertAbsent()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun sixteenValidRetainedActionsSurviveUntilAfterExactPendingRecordWasReadBackAndHttpCompletes() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            val slots = List(16) { sessionPendingSlot(key = historyId(it + 10), dispatched = it % 2 == 0) }
            storage.pending.slots += slots
            val fixture = InstallationDeletionFixture(this, storage, deletionHandler = {
                assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(deletingRecord()))
                assertEquals(slots, storage.pending.slots)
                val afterCommit = storage.faults.trace.dropWhile { it != Step.REPLACE_STORED }.drop(1)
                assertTrue(Step.CREDENTIAL_READ in afterCommit)
                assertTrue(Step.PENDING_CLEAR_BEFORE !in storage.faults.trace)
                respond("", HttpStatusCode.NoContent, deletionHeaders())
            })
            try {
                assertDeletionCompleted(fixture.repository.startDeletion())
                assertEquals(1, fixture.sessionRequests.size)
                storage.assertAbsent()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun sessionNotFoundForbiddenTerminalAndServerErrorsCannotEnrollAllocateKeyOrChangeStorage() =
        runTest {
            val cases = listOf(
                HttpStatusCode.NotFound to "INSTALLATION_NOT_FOUND",
                HttpStatusCode.Forbidden to "INSTALLATION_CREDENTIAL_REJECTED",
                HttpStatusCode.Gone to "INSTALLATION_DELETED",
                HttpStatusCode.ServiceUnavailable to "SERVICE_UNAVAILABLE",
            )
            for ((status, code) in cases) {
                val fixture = InstallationDeletionFixture(this, sessionHandler = {
                    respond(mutationProblem(status, code), status, sessionHeaders(status))
                })
                try {
                    assertIs<AppResult.Failure>(fixture.repository.startDeletion())
                    fixture.assertRetained(Fixtures.record())
                    assertTrue(fixture.storage.faults.mutations.isEmpty())
                    assertEquals(1, fixture.sessionRequests.size)
                    assertTrue(fixture.requests.isEmpty())
                    assertEquals(0, fixture.keyCalls)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun missingCorruptMismatchedLockedAndUnsupportedInventoriesRefuseBeforeAnyNetworkOrKey() =
        runTest {
            for (change in deletionStartInvalidStores()) {
                val fixture = InstallationDeletionFixture(this)
                change(fixture.storage)
                try {
                    assertIs<AppResult.Failure>(fixture.repository.startDeletion())
                    assertTrue(fixture.sessionRequests.isEmpty() && fixture.requests.isEmpty())
                    assertEquals(0, fixture.keyCalls)
                    assertTrue(fixture.storage.faults.mutations.isEmpty())
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun failedReplaceAndUncertainReadbackNeverDispatchDeletion() =
        runTest {
            for (point in listOf(Step.REPLACE_BEFORE, Step.REPLACE_STORED, Step.CREDENTIAL_READ)) {
                val fixture = InstallationDeletionFixture(this)
                if (point == Step.CREDENTIAL_READ) {
                    fixture.storage.faults.onStep = { step ->
                        if (step == Step.REPLACE_STORED) fixture.storage.faults.failAt(point)
                    }
                } else {
                    fixture.storage.faults.failAt(point)
                }
                try {
                    assertIs<AppResult.Failure>(fixture.repository.startDeletion())
                    val expected = if (point == Step.REPLACE_BEFORE) Fixtures.record() else deletingRecord()
                    fixture.assertRetained(expected)
                    assertTrue(fixture.requests.isEmpty())
                    assertTrue(Step.PENDING_CLEAR_BEFORE !in fixture.storage.faults.trace)
                    assertEquals(1, fixture.keyCalls)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun badKeyGenerationOverflowAndSessionExpiringBeforeAdmissionLeaveActiveIdentityUntouched() =
        runTest {
            for (scenario in listOf("bad-key", "overflow", "expired")) {
                val clock = TestTimeSource()
                val active = Fixtures.record(generation = if (scenario == "overflow") Long.MAX_VALUE else 1)
                val settings = DeletionFixtureSettings(clock, nextKey = { if (scenario == "bad-key") "bad" else Fixtures.KEY })
                val fixture = InstallationDeletionFixture(this, InstallationCoordinatorFixture(active), settings, {
                    if (scenario == "expired") clock += 900.seconds
                    respond(sessionResponse(active), HttpStatusCode.OK, sessionHeaders())
                })
                try {
                    assertIs<AppResult.Failure>(fixture.repository.startDeletion())
                    fixture.assertRetained(active)
                    assertTrue(fixture.requests.isEmpty() && fixture.storage.faults.mutations.isEmpty())
                    assertEquals(if (scenario == "expired") 0 else 1, fixture.keyCalls)
                } finally {
                    fixture.close()
                }
            }
        }
}

private fun deletionStartInvalidStores(): List<(InstallationCoordinatorFixture) -> Unit> =
    listOf(
        { it.credentials.removePieces() },
        { it.pending.slots += Fixtures.slot(1) },
        { it.pending.slots += sessionPendingSlot(Fixtures.record(generation = 2)) },
        { it.pending.slots += sessionPendingSlot(Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))) },
        {
            it.pending.readFailure = InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.LOCKED)
        },
        {
            it.credentials.readFailure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT)
        },
        {
            it.pending.readFailure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED)
        },
    )
