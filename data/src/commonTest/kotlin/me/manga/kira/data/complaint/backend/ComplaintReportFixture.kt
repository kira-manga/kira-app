package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import me.manga.kira.core.result.AppResult
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

/** Existing store/wire fixtures around the actual coordinator, session owner, fixed HTTP and report lane. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
internal class ComplaintReportFixture(
    scope: TestScope,
    val storage: InstallationCoordinatorFixture = InstallationCoordinatorFixture(Fixtures.record()),
    val coordinator: InstallationCredentialCoordinator = storage.coordinator,
    sessionHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
    },
    mutationHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
    },
) {
    val clock = TestTimeSource()
    private val endpoint = assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL))
    val sessionRequests = mutableListOf<HttpRequestData>()
    val requests = mutableListOf<HttpRequestData>()
    val sentBodies = mutableListOf<String>()
    val sessionEngine =
        historyMockEngine(scope) { request ->
            sessionRequests += request
            sessionHandler(request)
        }
    val mutationEngine =
        historyMockEngine(scope) { request ->
            requests += request
            sentBodies += request.body.toByteArray().decodeToString()
            mutationHandler(request)
        }
    val sessions = InstallationSessionManager(coordinator, endpoint, sessionEngine, clock)
    val http = ComplaintMutationHttp(endpoint, mutationEngine)
    val works = ReportWorkOwner()
    val repository = BackendFeedbackRepository(coordinator, sessions, http, works)

    fun close() {
        works.close()
        http.close()
        sessions.close()
        mutationEngine.close()
        sessionEngine.close()
    }
}

internal fun reportSlot(
    report: ComplaintReportRequest = mutationReport(),
    dispatched: Boolean = true,
): PendingComplaintSlot =
    assertIs<PendingComplaintCodecResult.Value<PendingComplaintSlot>>(
        PendingComplaintRecordCodec.encode(mutationPending(report, dispatched = dispatched)),
    ).value

internal fun reportRecord(slot: PendingComplaintSlot): PendingComplaintRecord =
    assertIs<PendingComplaintCodecResult.Value<PendingComplaintRecord>>(PendingComplaintRecordCodec.decode(slot)).value

internal fun <T> AppResult<T>.reportSuccess(): T =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("Unexpected report failure")
    }

internal suspend fun ComplaintReportFixture.readySession(binding: ReportActionBinding): ReportSession =
    assertIs<ReportSessionResult.Ready>(sessions.reportSession(binding)).session

internal fun ComplaintReportFixture.installWriteFault(case: String) {
    when (case) {
        "create-before" -> storage.faults.failAt(Step.PENDING_CREATE_BEFORE)
        "create-after" -> storage.faults.failAt(Step.PENDING_CREATED)
        "replace-before" -> storage.faults.failAt(Step.PENDING_REPLACE_BEFORE)
        "replace-after" -> storage.faults.failAt(Step.PENDING_REPLACED)
        else -> installReadbackFault(case)
    }
}

private fun ComplaintReportFixture.installReadbackFault(case: String) {
    var prepared: PendingComplaintSlot? = null
    storage.faults.onStep = { step ->
        when {
            case == "create-readback" && step == Step.PENDING_CREATED -> storage.faults.failAt(Step.PENDING_READ)
            case == "replace-readback" && step == Step.PENDING_REPLACED -> storage.faults.failAt(Step.PENDING_READ)
            case == "create-lie" && step == Step.PENDING_CREATED -> storage.pending.slots.clear()
            case == "replace-lie" && step == Step.PENDING_REPLACE_BEFORE -> prepared = storage.pending.slots.single()
            case == "replace-lie" && step == Step.PENDING_REPLACED -> storage.pending.slots[0] = assertNotNull(prepared)
            case == "unrelated-slot" && step == Step.PENDING_CREATED ->
                storage.pending.slots += reportSlot(mutationReport(key = historyId(2)))
            else -> Unit
        }
    }
}

internal fun TestScope.successfulReportFixture(): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = { request ->
            if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
                respond(mutationApplied(), HttpStatusCode.OK, mutationHeaders())
            } else {
                respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
            }
        },
    )

internal fun TestScope.failingSecondStatusFixture(): ComplaintReportFixture {
    var reads = 0
    return ComplaintReportFixture(
        this,
        mutationHandler = {
            reads++
            val status = if (reads == 1) HttpStatusCode.NotFound else HttpStatusCode.ServiceUnavailable
            val body = if (reads == 1) mutationProblem(status, "OPERATION_NOT_FOUND") else historyProblem(status)
            respond(body, status, mutationHeaders(status))
        },
    )
}

internal fun MockRequestHandleScope.notFoundOrCreated(request: HttpRequestData): HttpResponseData =
    if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
        respond(
            mutationProblem(HttpStatusCode.NotFound, "OPERATION_NOT_FOUND"),
            HttpStatusCode.NotFound,
            mutationHeaders(HttpStatusCode.NotFound),
        )
    } else {
        respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
    }

internal fun directProblem(status: HttpStatusCode): String =
    if (status == HttpStatusCode.Conflict) {
        mutationProblem(status, "COMPLAINT_CAPACITY_REACHED")
    } else {
        historyProblem(status)
    }
