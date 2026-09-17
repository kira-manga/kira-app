package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.core.result.AppResult
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

/** Real repository/coordinator/clients; only typed storage and wire responses are synthetic. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
internal class ComplaintHistoryFixture(
    scope: TestScope,
    val storage: InstallationCoordinatorFixture = InstallationCoordinatorFixture(Fixtures.record()),
    val clock: TestTimeSource = TestTimeSource(),
    sessionHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(sessionResponse(assertNotNull(storage.credentials.payloadRecord)), HttpStatusCode.OK, sessionHeaders())
    },
    historyHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(historyResponse(), HttpStatusCode.OK, sessionHeaders())
    },
) {
    private val endpoint = assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL))
    val enrollment = InstallationEnrollmentFixture(scope, storage)
    val sessionRequests = mutableListOf<HttpRequestData>()
    val historyRequests = mutableListOf<HttpRequestData>()
    val sessionEngine = historyMockEngine(scope) { request ->
        sessionRequests += request
        sessionHandler(request)
    }
    val historyEngine = historyMockEngine(scope) { request ->
        historyRequests += request
        historyHandler(request)
    }
    val sessions = InstallationSessionManager(storage.coordinator, endpoint, sessionEngine, clock)
    val http = ComplaintHistoryHttp(endpoint, historyEngine)
    val loads = ComplaintHistoryLoads()
    val repository = BackendComplaintHistoryRepository(
        storage.coordinator, sessions, enrollment.http, enrollment.generator, http, loads,
    )

    fun assertPreserved(
        record: InstallationCredentialRecord = Fixtures.record(),
        slots: List<PendingComplaintSlot> = emptyList(),
    ) {
        assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(record))
        assertTrue(storage.credentials.keyPresent && storage.credentials.payloadPresent)
        assertEquals(slots.size, storage.pending.slots.size)
        assertTrue(slots.all { expected -> storage.pending.slots.any { it.sameAs(expected) } })
        assertTrue(storage.faults.mutations.isEmpty())
    }

    fun close() {
        loads.close()
        http.close()
        sessions.close()
        enrollment.close()
        historyEngine.close()
        sessionEngine.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun historyMockEngine(
    scope: TestScope,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): MockEngine = MockEngine(
    MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(scope.testScheduler)
        addHandler(handler)
    },
)

internal fun historyId(index: Int): String = "44444444-4444-4444-8444-${index.toString(16).padStart(12, '0')}"

internal fun historyItem(
    index: Int = 100,
    kind: String = "REPORT",
    type: String = "TECHNICAL",
    status: String = "OPEN",
    noticeKey: String? = null,
    createdAt: String = SESSION_ISSUED_AT,
): JsonObject = buildJsonObject {
    val id = historyId(index)
    put("id", id)
    put("kind", kind)
    put("type", type)
    put("subject", if (noticeKey == null) JsonPrimitive("Synthetic subject") else JsonNull)
    put("body", "Synthetic body")
    put("status", status)
    put("createdAt", createdAt)
    put("updatedAt", createdAt)
    put("version", 1)
    put("actionTag", "\"complaint-$id-v1\"")
    put("appVersion", JsonNull)
    put("platform", "ANDROID")
    put("osVersion", JsonNull)
    put("manufacturer", JsonNull)
    put("deviceModel", JsonNull)
    put("closureReason", if (status == "CLOSED") JsonPrimitive("Synthetic closure") else JsonNull)
    put("replyToId", if (kind == "REPLY") JsonPrimitive(Fixtures.OTHER_ID) else JsonNull)
    if (noticeKey != null) put("noticeKey", noticeKey)
}

internal fun historyNotice(index: Int = 200, key: String = "complaints.notice.synthetic"): JsonObject = buildJsonObject {
    put("id", historyId(index))
    put("kind", "NOTICE")
    put("noticeKey", key)
    put("status", "PINNED")
    put("createdAt", SESSION_ISSUED_AT)
    put("updatedAt", SESSION_ISSUED_AT)
    put("version", 1)
}

internal fun historyResponse(
    items: List<JsonObject> = emptyList(),
    notices: List<JsonObject> = emptyList(),
    cursor: String? = null,
): String = buildJsonObject {
    put("notices", JsonArray(notices))
    put("items", JsonArray(items))
    put("nextCursor", cursor?.let { JsonPrimitive(it) } ?: JsonNull)
}.toString()

internal fun decodedHistory(
    items: List<JsonObject> = emptyList(),
    notices: List<JsonObject> = emptyList(),
    cursor: String? = null,
): ComplaintHistoryPage = assertIs<AppResult.Success<ComplaintHistoryPage>>(
    ComplaintHistoryResponse.decode(historyResponse(items, notices, cursor)),
).value

/** Actual common/ApiError.kt NON_EMPTY shape; no fabricated top-level code/requestId/instance. */
internal fun historyProblem(status: HttpStatusCode): String = buildJsonObject {
    put("type", "about:blank")
    put("title", status.description)
    put("status", status.value)
    if (status == HttpStatusCode.NotFound) put("detail", "Not found.")
    if (status == HttpStatusCode.Unauthorized) {
        put("detail", "Authentication is required or the token is invalid.")
    }
}.toString()

/** This precise machine code is distinct from the otherwise valid code-less disabled404 problem. */
internal fun historyInstallationNotFoundProblem(): String = buildJsonObject {
    put("type", "about:blank")
    put("title", "Not Found")
    put("status", 404)
    put("errors", JsonArray(listOf(buildJsonObject {
        put("code", "INSTALLATION_NOT_FOUND")
        put("message", "Installation was never claimed.")
    })))
}.toString()

internal class HistoryTrackedChannel(private val delegate: ByteReadChannel) : ByteReadChannel by delegate {
    var cancelled = false
        private set

    override fun cancel(cause: Throwable?) {
        cancelled = true
        delegate.cancel(cause)
    }
}

/**
 * Deterministic terminal-failure model for Ktor's readAvailable -1 shortcut. In afterPrefix mode,
 * ordinary bytes drain first; only the following EOF exposes failure. No exception is thrown by
 * the fixture's read methods, so a passing guard cannot rely on a read exception instead.
 */
internal class ComplaintFailedEofChannel private constructor(
    private val delegate: ByteReadChannel,
    private val afterPrefix: Boolean,
) : ByteReadChannel by delegate {
    constructor(bytes: ByteArray, afterPrefix: Boolean) : this(ByteReadChannel(bytes), afterPrefix)

    private val failure = IllegalStateException("Synthetic complaint receive failure")
    var cancelled = false
        private set
    var prefixDrained = false
        private set

    override val isClosedForRead: Boolean
        get() {
            if (cancelled || !afterPrefix) return true
            return delegate.isClosedForRead.also { if (it) prefixDrained = true }
        }

    override val closedCause: Throwable?
        get() = if (isClosedForRead) failure else null

    override fun cancel(cause: Throwable?) {
        cancelled = true
        delegate.cancel(cause)
    }
}
