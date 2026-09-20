package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.platform.storage.CredentialCleanupReason as Reason

@OptIn(ExperimentalTime::class)
class InstallationDeletionContinuationTest {
    @Test
    fun restartAfterServerFailureResendsOnlyTheExactDurableBodyAndKey() =
        runTest {
            val first = unavailableDeletionFixture()
            val original =
                try {
                    assertIs<AppError.Network.Http>(assertDeletionPending(first.repository.startDeletion()).error)
                    first.assertRetained(deletingRecord())
                    first.bodies.single()
                } finally {
                    first.close()
                }
            val resumed = deletionRestart(first.storage, expectedDataScopeId = Fixtures.SCOPE)
            try {
                assertDeletionCompleted(resumed.repository.continueDeletion())
                assertEquals(listOf(original), resumed.bodies)
                assertEquals(Fixtures.KEY, resumed.requests.single().headers[Policy.IDEMPOTENCY_HEADER])
                resumed.assertNoNewIdentity()
                resumed.storage.assertAbsent()
            } finally {
                resumed.close()
            }
        }

    @Test
    fun launchScopeMismatchOnRestartRetainsExactDeletionTupleWithoutAnyRequestOrMutation() =
        runTest {
            val record = deletingRecord()
            val storage = InstallationCoordinatorFixture(record)
            val slots = listOf(Fixtures.slot(1), Fixtures.slot(2))
            storage.pending.slots += slots
            val resumed = deletionRestart(storage, expectedDataScopeId = OTHER_SCOPE) {
                error("A different launch scope must never receive the retained deletion credential")
            }
            try {
                repeat(2) {
                    val pending = assertDeletionPending(resumed.repository.continueDeletion())
                    assertIs<AppError.Network.Serialization>(pending.error)
                    assertNull(pending.retryAfterSeconds)
                    resumed.assertRetained(record, slots)
                    resumed.assertNoNewIdentity()
                    assertTrue(resumed.requests.isEmpty() && resumed.bodies.isEmpty())
                    assertTrue(storage.faults.mutations.isEmpty())
                }
            } finally {
                resumed.close()
            }
        }

    @Test
    fun launchScopeMismatchDoesNotBlockAlreadyAuthorizedTerminalMarkerCleanupOnRestart() =
        runTest {
            val storage = InstallationCoordinatorFixture(deletingRecord())
            storage.pending.slots += Fixtures.slot(1)
            storage.faults.failAt(InstallationStoreStep.CLEANUP_BEFORE)
            val first = InstallationDeletionFixture(this, storage)
            try {
                assertNotNull(assertDeletionPending(first.repository.continueDeletion()).error)
                val marker = assertNotNull(storage.credentials.marker)
                assertEquals(Reason.SERVER_TERMINAL_CONFIRMED, marker.reason)
                assertEquals(deletingRecord().localGeneration, marker.expectedGeneration)
                assertEquals(1, first.requests.size)
                assertTrue(storage.pending.slots.isEmpty())
            } finally {
                first.close()
            }
            val resumed = deletionRestart(storage, expectedDataScopeId = OTHER_SCOPE) {
                error("Authorized terminal-marker cleanup is local, even after a launch scope change")
            }
            try {
                assertDeletionCompleted(resumed.repository.continueDeletion())
                assertTrue(resumed.requests.isEmpty() && resumed.bodies.isEmpty())
                resumed.assertNoNewIdentity()
                storage.assertAbsent()
            } finally {
                resumed.close()
            }
        }

    @Test
    fun acceptedDelayIsMemoryOnlyExactTupleBoundAndNeverARetryLoopOrNewSession() =
        runTest {
            val clock = TestTimeSource()
            val fixture = acceptedDeletionFixture(clock)
            try {
                assertEquals(2, assertDeletionPending(fixture.repository.startDeletion()).retryAfterSeconds)
                assertEquals(2, assertDeletionPending(fixture.repository.continueDeletion()).retryAfterSeconds)
                clock += 1_999.milliseconds
                assertEquals(1, assertDeletionPending(fixture.repository.continueDeletion()).retryAfterSeconds)
                assertEquals(1, fixture.requests.size)
                clock += 1.milliseconds
                assertEquals(2, assertDeletionPending(fixture.repository.continueDeletion()).retryAfterSeconds)
                assertEquals(2, fixture.requests.size)
                clock += 9.days
                assertEquals(2, assertDeletionPending(fixture.repository.continueDeletion()).retryAfterSeconds)
                assertEquals(3, fixture.requests.size)
                assertEquals(1, fixture.bodies.distinct().size)
                assertEquals(1, fixture.sessionRequests.size)
                assertEquals(1, fixture.keyCalls)
                fixture.assertRetained(deletingRecord())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun exactContinuationHasNoBearerOrIdsInUrl() =
        runTest {
            val storage = InstallationCoordinatorFixture(deletingRecord())
            val fixture = deletionRestart(storage, expectedDataScopeId = Fixtures.SCOPE)
            try {
                assertDeletionCompleted(fixture.repository.continueDeletion())
                assertDeletionWire(fixture)
                fixture.assertNoNewIdentity()
                storage.assertAbsent()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun barePendingCannotCleanLocallyAndCallingStartAgainNeverRefreshesOrChangesItsKey() =
        runTest {
            val fixture = InstallationDeletionFixture(this, InstallationCoordinatorFixture(deletingRecord()))
            try {
                assertRefused(Block.REMOTE_DELETION_PENDING, fixture.coordinator.resumeCleanup())
                assertIs<AppResult.Failure>(fixture.repository.startDeletion())
                fixture.assertRetained(deletingRecord())
                fixture.assertNoNewIdentity()
                assertTrue(fixture.requests.isEmpty())
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
            } finally {
                fixture.close()
            }
        }
}

private const val OTHER_SCOPE = "66666666-6666-4666-8666-666666666666"

private fun TestScope.unavailableDeletionFixture(): InstallationDeletionFixture =
    InstallationDeletionFixture(
        this,
        deletionHandler = {
            val status = HttpStatusCode.ServiceUnavailable
            respond(mutationProblem(status, "SERVICE_UNAVAILABLE"), status, deletionHeaders(status))
        },
    )

@OptIn(ExperimentalTime::class)
private fun TestScope.acceptedDeletionFixture(clock: TestTimeSource): InstallationDeletionFixture =
    InstallationDeletionFixture(
        this,
        settings = DeletionFixtureSettings(clock),
        deletionHandler = {
            val headers =
                deletionHeaders(HttpStatusCode.Accepted) {
                    remove(HttpHeaders.RetryAfter)
                    append(HttpHeaders.RetryAfter, "2")
                }
            respond("", HttpStatusCode.Accepted, headers)
        },
    )

private fun assertDeletionWire(fixture: InstallationDeletionFixture) {
    val request = fixture.requests.single()
    assertEquals(HttpMethod.Post, request.method)
    assertEquals(SESSION_BASE_URL + Policy.PATH, request.url.toString())
    assertEquals(Fixtures.KEY, request.headers[Policy.IDEMPOTENCY_HEADER])
    assertNull(request.headers[HttpHeaders.Authorization])
    assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
    assertEquals("no-store, no-transform", request.headers[HttpHeaders.CacheControl])
    val body = assertIs<JsonObject>(Json.parseToJsonElement(fixture.bodies.single()))
    assertEquals(setOf("installationId", "secret", "credentialVersion", "dataScopeId"), body.keys)
    assertEquals(Fixtures.ID, body.historyString("installationId"))
    assertEquals(Fixtures.secret, body.historyString("secret"))
    assertEquals(1L, body.number("credentialVersion"))
    assertEquals(Fixtures.SCOPE, body.historyString("dataScopeId"))
}
