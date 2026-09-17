package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

@OptIn(ExperimentalCoroutinesApi::class)
class ComplaintMutationHttpTest {
    @Test
    fun fixedPostsSendOnlyClosedFrozenBodiesAndHeadersAndBindSuccessfulResponses() =
        runTest {
            val f = ComplaintMutationFixture(this)
            try {
                val create = assertIs<ComplaintCreateHttpResult.Applied>(f.http.create(f.create, f.session))
                val status = assertIs<ComplaintCreateStatusHttpResult.Applied>(f.http.status(f.status, f.session))
                assertSame(f.create, create.request)
                assertSame(f.status, status.request)
                assertMutationWire(f)
            } finally {
                f.close()
            }
        }

    @Test
    fun delayedNoEchoRejectionStaysWithItsExactRequestAndDirect409IsNotTerminal() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val f = delayedMutationFixture(this, release)
            try {
                val direct = assertIs<ComplaintCreateHttpResult.HttpFailure>(f.http.create(f.create, f.session))
                assertSame(f.create, direct.request)
                assertEquals(ComplaintMutationProblem.COMPLAINT_CAPACITY_REACHED, direct.problem)
                val late = async { f.http.status(f.status, f.session) }
                runCurrent()
                val otherPending = mutationPending(mutationReport(key = MUTATION_OTHER_KEY))
                val other = assertNotNull(ComplaintCreateStatusRequest.checked(otherPending))
                val second = assertIs<ComplaintCreateStatusHttpResult.Rejected>(f.http.status(other, f.session))
                release.complete(Unit)
                val first = assertIs<ComplaintCreateStatusHttpResult.Rejected>(late.await())
                assertSame(f.status, first.request)
                assertSame(other, second.request)
                assertNotSame(second.request, first.request)
                assertEquals(3, f.requests.size)
            } finally {
                release.complete(Unit)
                f.close()
            }
        }

    @Test
    fun directAcknowledgementsAllow32KiBButStatusAndEveryProblemStay16KiBWithFailedEofRejected() =
        runTest {
            assertMutationBoundary(
                ComplaintMutationRoute.CREATE,
                HttpStatusCode.Created,
                mutationAck(),
                Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES,
            )
            assertMutationBoundary(
                ComplaintMutationRoute.STATUS,
                HttpStatusCode.OK,
                mutationRejected(),
                Policy.MAX_STATUS_OR_PROBLEM_BYTES,
            )
            for (route in ComplaintMutationRoute.entries) {
                assertMutationBoundary(
                    route,
                    HttpStatusCode.Conflict,
                    mutationProblem(HttpStatusCode.Conflict, "COMPLAINT_CAPACITY_REACHED"),
                    Policy.MAX_STATUS_OR_PROBLEM_BYTES,
                )
                for (afterPrefix in listOf(false, true)) assertMutationFailedEof(route, afterPrefix)
            }
        }

    @Test
    fun duplicateSecurityHeadersFramingMediaUtf8AndOtherSuccessOrRedirectStatusesFailClosed() =
        runTest {
            val bytes = mutationAck().encodeToByteArray()
            for (headers in invalidMutationHeaders(bytes.size)) assertRejectedMutation(bytes, headers)
            assertRejectedMutation(byteArrayOf(0xc3.toByte(), 0x28), mutationHeaders(HttpStatusCode.Created))
            for (route in ComplaintMutationRoute.entries) {
                for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.Found)) {
                    assertRejectedMutation(ByteArray(0), mutationHeaders(status), status, route)
                }
            }
            assertRejectedMutation(
                mutationApplied().encodeToByteArray(),
                mutationHeaders { append(HttpHeaders.ETag, "unexpected") },
                HttpStatusCode.OK,
                ComplaintMutationRoute.STATUS,
            )
        }

    @Test
    fun verified401AndOperationNotFoundRemainBoundOrdinaryFailuresWithoutAutomaticRetry() =
        runTest {
            val cases =
                listOf(
                    HttpStatusCode.Unauthorized to "UNAUTHORIZED",
                    HttpStatusCode.NotFound to "OPERATION_NOT_FOUND",
                    HttpStatusCode.Conflict to "IDEMPOTENCY_IN_PROGRESS",
                    HttpStatusCode.TooManyRequests to "RATE_LIMITED",
                    HttpStatusCode.ServiceUnavailable to "SERVICE_UNAVAILABLE",
                )
            for ((status, code) in cases) assertBoundMutationProblem(status, code)
            assertUnverifiedMutationProblemsRejected()
            assertFailedMutation401Rejected()
        }

    @Test
    fun cancellationTimeoutCloseAndAllFourBindingMismatchesPreserveZeroOrOneDispatch() =
        runTest {
            assertMutationCancellation()
            assertMutationTimeout()
            val f = ComplaintMutationFixture(this)
            val mismatched =
                listOf(
                    Fixtures.record(version = 2),
                    Fixtures.record(generation = 2),
                    Fixtures.record(Fixtures.material(id = Fixtures.OTHER_ID)),
                    Fixtures.record(Fixtures.material(scope = MUTATION_OTHER_SCOPE)),
                )
            try {
                for (record in mismatched) {
                    val session = mutationSession(record)
                    assertMutationFailure(ComplaintMutationFailure.INVALIDATED, f.http.create(f.create, session))
                    assertMutationFailure(ComplaintMutationFailure.INVALIDATED, f.http.status(f.status, session))
                }
                f.http.close()
                assertMutationFailure(ComplaintMutationFailure.CLOSED, f.http.create(f.create, f.session))
                assertMutationFailure(ComplaintMutationFailure.CLOSED, f.http.status(f.status, f.session))
                assertTrue(f.requests.isEmpty())
            } finally {
                f.close()
            }
        }
}

private fun delayedMutationFixture(
    scope: TestScope,
    release: CompletableDeferred<Unit>,
): ComplaintMutationFixture =
    ComplaintMutationFixture(scope) { request ->
        if (request.url.encodedPath.endsWith(Policy.CREATE_PATH)) {
            respond(
                mutationProblem(HttpStatusCode.Conflict, "COMPLAINT_CAPACITY_REACHED"),
                HttpStatusCode.Conflict,
                mutationHeaders(HttpStatusCode.Conflict),
            )
        } else {
            val body = assertIs<JsonObject>(Json.parseToJsonElement(request.body.toByteArray().decodeToString()))
            if (body.historyString("key") == Fixtures.KEY) release.await()
            respond(mutationRejected(), HttpStatusCode.OK, mutationHeaders())
        }
    }

private fun invalidMutationHeaders(bodySize: Int): List<Headers> =
    listOf<HeadersBuilder.() -> Unit>(
        { remove(ComplaintBoundedResponse.CONTRACT_HEADER) },
        { append(ComplaintBoundedResponse.CONTRACT_HEADER, "1") },
        { set(HttpHeaders.CacheControl, "no-store") },
        { append(HttpHeaders.CacheControl, "no-store, no-transform") },
        { append(HttpHeaders.ContentType, "application/json") },
        { set(HttpHeaders.ContentType, "application/json; charset=iso-8859-1") },
        { append(HttpHeaders.ContentEncoding, "gzip") },
        { append(HttpHeaders.ContentLength, "${bodySize + 1}") },
        { append(HttpHeaders.ContentLength, "32769") },
        {
            append(HttpHeaders.ContentLength, bodySize.toString())
            append(HttpHeaders.TransferEncoding, "chunked")
        },
        { append(HttpHeaders.Location, "${Policy.CREATE_PATH}/${Fixtures.OTHER_ID}") },
        { append(HttpHeaders.ETag, "\"complaint-${Fixtures.OTHER_ID}-v1\"") },
        { append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"kira-complaints\"") },
    ).map { mutationHeaders(HttpStatusCode.Created, it) }

private suspend fun TestScope.assertBoundMutationProblem(
    status: HttpStatusCode,
    code: String,
) {
    val f = ComplaintMutationFixture(this) { respond(mutationProblem(status, code), status, mutationHeaders(status)) }
    try {
        val result = assertIs<ComplaintCreateStatusHttpResult.HttpFailure>(f.http.status(f.status, f.session))
        assertSame(f.status, result.request)
        assertEquals(status.value, result.status)
        if (code == "OPERATION_NOT_FOUND") assertEquals(ComplaintMutationProblem.OPERATION_NOT_FOUND, result.problem)
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertUnverifiedMutationProblemsRejected() {
    val unauthorized = mutationProblem(HttpStatusCode.Unauthorized, "UNAUTHORIZED").encodeToByteArray()
    assertRejectedMutation(
        unauthorized,
        mutationHeaders(HttpStatusCode.Unauthorized) { remove(HttpHeaders.WWWAuthenticate) },
        HttpStatusCode.Unauthorized,
        ComplaintMutationRoute.STATUS,
    )
    val wrongStatus = mutationProblem(HttpStatusCode.NotFound, "OPERATION_NOT_FOUND").encodeToByteArray()
    assertRejectedMutation(
        wrongStatus,
        mutationHeaders(HttpStatusCode.Conflict),
        HttpStatusCode.Conflict,
        ComplaintMutationRoute.STATUS,
    )
}

private suspend fun TestScope.assertFailedMutation401Rejected() {
    val bytes = mutationProblem(HttpStatusCode.Unauthorized, "UNAUTHORIZED").encodeToByteArray()
    val channel = ComplaintFailedEofChannel(bytes, afterPrefix = true)
    val f =
        ComplaintMutationFixture(this) {
            respond(channel, HttpStatusCode.Unauthorized, mutationHeaders(HttpStatusCode.Unauthorized))
        }
    try {
        assertMutationFailure(ComplaintMutationFailure.TRANSPORT, f.http.status(f.status, f.session))
        assertTrue(channel.cancelled)
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertMutationCancellation() {
    val cancellation = CancellationException("Synthetic cancelled mutation")
    val f = ComplaintMutationFixture(this) { throw cancellation }
    try {
        val actual = assertFailsWith<CancellationException> { f.http.status(f.status, f.session) }
        assertCancellationIdentity(cancellation, actual)
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertMutationTimeout() {
    val f = ComplaintMutationFixture(this) { awaitCancellation() }
    try {
        assertMutationFailure(ComplaintMutationFailure.TIMEOUT, f.http.create(f.create, f.session))
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}
