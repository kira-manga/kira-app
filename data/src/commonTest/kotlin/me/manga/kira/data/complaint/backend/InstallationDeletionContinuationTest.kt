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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

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
            val resumed = deletionRestart(first.storage)
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
            val fixture = InstallationDeletionFixture(this, storage)
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
