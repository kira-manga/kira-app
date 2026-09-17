package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

/** Real coordinator/session/delete HTTP/repository, synthetic retained stores and two isolated MockEngines only. */
@OptIn(ExperimentalTime::class)
internal class InstallationDeletionFixture(
    scope: TestScope,
    val storage: InstallationCoordinatorFixture = InstallationCoordinatorFixture(Fixtures.record()),
    val settings: DeletionFixtureSettings = DeletionFixtureSettings(),
    sessionHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(sessionResponse(assertNotNull(storage.credentials.payloadRecord)), HttpStatusCode.OK, sessionHeaders())
    },
    deletionHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond("", HttpStatusCode.NoContent, deletionHeaders())
    },
) {
    val coordinator = settings.coordinator ?: storage.coordinator
    val sessionRequests = mutableListOf<HttpRequestData>()
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()
    var keyCalls = 0
        private set
    private val endpoint = assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL))
    private val sessionEngine =
        historyMockEngine(scope) { request ->
            sessionRequests += request
            sessionHandler(request)
        }
    private val deletionEngine =
        historyMockEngine(scope) { request ->
            requests += request
            // Production wipes its request bytes after execute; copy synthetic fixture text during the handler.
            bodies += request.body.toByteArray().decodeToString()
            deletionHandler(request)
        }
    val sessions = InstallationSessionManager(coordinator, endpoint, sessionEngine, settings.clock)
    val http = InstallationDeletionHttp(endpoint, deletionEngine)
    val works = InstallationDeletionWorks()
    val repository =
        BackendInstallationDeletionRepository(
            coordinator,
            sessions,
            http,
            InstallationDeletionInputs(
                {
                    keyCalls += 1
                    settings.nextKey()
                },
                settings.clock,
            ),
            works,
        )

    fun assertRetained(
        record: InstallationCredentialRecord,
        slots: List<PendingComplaintSlot> = emptyList(),
    ) {
        assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(record))
        assertTrue(storage.credentials.keyPresent && storage.credentials.payloadPresent)
        assertNull(storage.credentials.marker)
        assertEquals(slots.size, storage.pending.slots.size)
        assertTrue(slots.all { expected -> storage.pending.slots.any { it.sameAs(expected) } })
    }

    fun close() {
        works.close()
        http.close()
        sessions.close()
        deletionEngine.close()
        sessionEngine.close()
    }
}

@OptIn(ExperimentalTime::class)
internal class DeletionFixtureSettings(
    val clock: TestTimeSource = TestTimeSource(),
    val nextKey: () -> String = { Fixtures.KEY },
    val coordinator: InstallationCredentialCoordinator? = null,
)

internal fun deletingRecord(
    active: InstallationCredentialRecord = Fixtures.record(),
    key: String = Fixtures.KEY,
): InstallationCredentialRecord = active.beginDeletion(key).valid()

internal fun deletionHeaders(
    status: HttpStatusCode = HttpStatusCode.NoContent,
    change: HeadersBuilder.() -> Unit = {},
): Headers =
    Headers.build {
        append(ComplaintBoundedResponse.CONTRACT_HEADER, "1")
        append(HttpHeaders.CacheControl, "no-store, no-transform")
        if (status != HttpStatusCode.NoContent && status != HttpStatusCode.Accepted) {
            append(HttpHeaders.ContentType, "application/problem+json")
        }
        if (status == HttpStatusCode.Accepted) append(HttpHeaders.RetryAfter, Policy.MIN_RETRY_SECONDS.toString())
        change()
    }

internal fun assertDeletionPending(
    result: AppResult<ComplaintInstallationDeletionOutcome>,
): ComplaintInstallationDeletionOutcome.Pending =
    assertIs<ComplaintInstallationDeletionOutcome.Pending>(assertIs<AppResult.Success<*>>(result).value)

internal fun assertDeletionCompleted(result: AppResult<ComplaintInstallationDeletionOutcome>) {
    assertEquals(ComplaintInstallationDeletionOutcome.Completed, assertIs<AppResult.Success<*>>(result).value)
}

/** A new owner after the previous fixture has closed; no enrollment/session/key fallback is permitted. */
internal fun TestScope.deletionRestart(
    storage: InstallationCoordinatorFixture,
    deletionHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond("", HttpStatusCode.NoContent, deletionHeaders())
    },
): InstallationDeletionFixture =
    InstallationDeletionFixture(
        this,
        storage,
        DeletionFixtureSettings(nextKey = { error("Restart must not allocate a key") }, coordinator = storage.restart()),
        sessionHandler = { error("Restart must not request a session") },
        deletionHandler = deletionHandler,
    )

internal fun InstallationDeletionFixture.assertNoNewIdentity() {
    assertEquals(0, keyCalls)
    assertTrue(sessionRequests.isEmpty())
    assertTrue(InstallationStoreStep.CREATE_BEFORE !in storage.faults.trace)
    assertTrue(storage.credentials.payloadRecord?.state != InstallationCredentialState.ACTIVE)
}
