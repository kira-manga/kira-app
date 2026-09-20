package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

/** Synthetic local examples and MockEngine only; never native-engine, origin, server-signature or storage proof. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
internal class ComplaintSessionFixture(
    scope: TestScope,
    val storage: InstallationCoordinatorFixture = InstallationCoordinatorFixture(Fixtures.record()),
    val clock: TestTimeSource = TestTimeSource(),
    expectedDataScopeId: String? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
    },
) {
    val engine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler(handler)
            },
        )
    val manager =
        InstallationSessionManager(
            storage.coordinator,
            assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL, expectedDataScopeId)),
            engine,
            clock,
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
        manager.close()
        engine.close()
    }
}

internal const val SESSION_BASE_URL = "https://complaints.example.invalid/gateway"
internal const val SESSION_ISSUED_AT = "2026-09-16T08:09:10.123456Z"
internal const val SESSION_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzeW50aGV0aWMifQ.c3ludGhldGlj"

internal fun sessionResponse(record: InstallationCredentialRecord = Fixtures.record()): String =
    buildJsonObject {
        put("installationId", record.material.installationId)
        put("accessToken", SESSION_TOKEN)
        put("tokenType", "Bearer")
        put("credentialVersion", record.credentialVersion)
        put("dataScopeId", record.material.dataScopeId)
        put("issuedAt", SESSION_ISSUED_AT)
        put("expiresInSeconds", 900)
    }.toString()

internal fun sessionHeaders(
    status: HttpStatusCode = HttpStatusCode.OK,
    change: HeadersBuilder.() -> Unit = {},
): Headers =
    Headers.build {
        append(ComplaintBoundedResponse.CONTRACT_HEADER, "1")
        append(HttpHeaders.CacheControl, "no-store, no-transform")
        append(
            HttpHeaders.ContentType,
            if (status == HttpStatusCode.OK) "application/json" else "application/problem+json",
        )
        if (status == HttpStatusCode.Unauthorized) {
            append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"kira-complaints\"")
        }
        change()
    }

internal fun sessionPendingSlot(
    record: InstallationCredentialRecord = Fixtures.record(),
    key: String = Fixtures.KEY,
    dispatched: Boolean = false,
): PendingComplaintSlot {
    val binding =
        assertNotNull(
            PendingComplaintBinding.checked(
                record.material.installationId,
                record.credentialVersion,
                record.localGeneration,
                record.material.dataScopeId,
            ),
        )
    val action =
        assertNotNull(
            PendingComplaintAction.checked(PendingComplaintOperation.CREATE_REPORT, Fixtures.OTHER_ID, null, null),
        )
    val fingerprint = assertNotNull(PendingComplaintFingerprint.checked(1, "A".repeat(43)))
    val request = assertNotNull(PendingComplaintRequest.checked(action, key, fingerprint))
    val time = Instant.parse(SESSION_ISSUED_AT)
    val pending =
        PendingComplaintRecord.prepared(binding, request, assertNotNull(PendingComplaintTimes.checked(time, time)))
    val selected = if (dispatched) assertNotNull(pending.markedDispatched()) else pending
    val encoded = PendingComplaintRecordCodec.encode(selected)
    return assertIs<PendingComplaintCodecResult.Value<PendingComplaintSlot>>(encoded).value
}

internal fun assertSessionFailure(
    expected: ComplaintSessionFailure,
    actual: ComplaintSessionResult,
) {
    assertEquals(expected, assertIs<ComplaintSessionResult.Failed>(actual).reason)
}

// JVM coroutine stack recovery may copy cancellation, but must retain the original cancellation as its cause.
internal fun assertCancellationIdentity(
    expected: CancellationException,
    actual: CancellationException?,
) {
    val causes = generateSequence(assertNotNull(actual)) { it.cause as? CancellationException }
    assertTrue(causes.take(MAX_CANCELLATION_CAUSES).any { it === expected })
}

private const val MAX_CANCELLATION_CAUSES = 8
