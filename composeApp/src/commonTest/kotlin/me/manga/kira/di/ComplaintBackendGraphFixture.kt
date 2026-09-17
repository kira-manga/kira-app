package me.manga.kira.di

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.data.complaint.backend.ComplaintReportIdentifiers
import me.manga.kira.data.complaint.backend.ComplaintReportInputs
import me.manga.kira.data.complaint.backend.ComplaintReportMetadataInput
import me.manga.kira.platform.storage.CleanupMarkerCreateResult
import me.manga.kira.platform.storage.CleanupMarkerReadResult
import me.manga.kira.platform.storage.CleanupMarkerRemoveResult
import me.manga.kira.platform.storage.CredentialCreateResult
import me.manga.kira.platform.storage.CredentialDeleteResult
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.CredentialReplaceResult
import me.manga.kira.platform.storage.CredentialResetResult
import me.manga.kira.platform.storage.InstallationCredentialMaterial
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationMaterialGenerationResult
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationValueResult
import me.manga.kira.platform.storage.PendingClearResult
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot
import me.manga.kira.platform.storage.PendingCreateResult
import me.manga.kira.platform.storage.PendingDeleteResult
import me.manga.kira.platform.storage.PendingReadResult
import me.manga.kira.platform.storage.PendingReplaceResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import me.manga.kira.platform.storage.CredentialCleanupMarker as Marker

/** Isolated synthetic storage + MockEngine; never calls native factories or provides activation. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ComplaintBackendGraphFixture(
    private val scope: TestScope,
) {
    val events = mutableListOf<String>()
    val credentials = GraphCredentials()
    val pending = GraphPending()
    val owners = mutableMapOf<String, GraphEngineOwner>()
    var failAllocation: String? = null
    var failEngineAccess: String? = null
    var failClose: String? = null
    var generations = 0
    var historyCalls = 0
    var sessionCalls = 0
    var mutationCalls = 0
    var deletionCalls = 0
    var deletionKeyGenerations = 0
    var reportIdentifierGenerations = 0
    var reportMetadataReads = 0
    var historyHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(graphHistoryResponse(), HttpStatusCode.OK, graphHeaders())
    }
    var deletionHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        error("Unexpected deletion without explicit confirmation")
    }

    fun resources(): ComplaintBackendResources =
        ComplaintBackendResources(
            credentials = {
                events += "allocate:credentials"
                credentials
            },
            pending = {
                events += "allocate:pending"
                pending
            },
            generator = {
                events += "allocate:generator"
                object : InstallationCredentialMaterialGenerator {
                    override fun generate(dataScopeId: String): InstallationMaterialGenerationResult {
                        generations++
                        return InstallationMaterialGenerationResult.Unsupported
                    }
                }
            },
            engines =
                ComplaintBackendEngineFactories(
                    enrollment = {
                        newOwner("enrollment") { error("Unexpected enrollment for existing fixture identity") }
                    },
                    session = {
                        newOwner("session") {
                            sessionCalls++
                            respond(graphSessionResponse(), HttpStatusCode.OK, graphHeaders())
                        }
                    },
                    history = {
                        newOwner("history") { request ->
                            historyCalls++
                            historyHandler(request)
                        }
                    },
                    mutation = { target ->
                        assertEquals("$GRAPH_BASE/api/v1/complaints", target.toString())
                        newOwner("mutation") {
                            mutationCalls++
                            error("Unexpected report dispatch from read-only fixture")
                        }
                    },
                    deletion = { target ->
                        assertEquals("$GRAPH_BASE/api/v1/installations/delete-all", target.toString())
                        newOwner("deletion") { request ->
                            deletionCalls++
                            deletionHandler(request)
                        }
                    },
                ),
            inputs =
                ComplaintBackendInputFactories(
                    reports = ::newReportInputs,
                    deletionKey = {
                        deletionKeyGenerations++
                        GRAPH_DELETION_KEY
                    },
                ),
        )

    private fun newReportInputs(): ComplaintReportInputs {
        if (failAllocation == "report-inputs") error("Synthetic report input allocation failure")
        events += "allocate:report-inputs"
        return ComplaintReportInputs(
            identifiers = {
                reportIdentifierGenerations++
                ComplaintReportIdentifiers(
                    clientId = "33333333-3333-4333-8333-333333333333",
                    idempotencyKey = "44444444-4444-4444-8444-444444444444",
                )
            },
            metadata = {
                reportMetadataReads++
                ComplaintReportMetadataInput("1.0.0", "Synthetic OS", "Synthetic vendor", "Synthetic model")
            },
        )
    }

    private fun newOwner(
        name: String,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): GraphEngineOwner {
        if (failAllocation == name) error("Synthetic native allocation failure")
        events += "allocate:$name"
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        events += "request:$name"
                        handler(request)
                    }
                },
            )
        return GraphEngineOwner(
            name,
            engine,
            events,
            { failEngineAccess == name },
            { failClose == name },
        ).also { owners[name] = it }
    }
}

/** Mutation SPIs fail closed and are counted; these fixtures support existing-identity reads only. */
internal class GraphCredentials : InstallationCredentialStore {
    var record: InstallationCredentialRecord =
        InstallationCredentialRecord.candidate(
            assertIs<InstallationValueResult.Valid<InstallationCredentialMaterial>>(
                InstallationCredentialMaterial.checked(
                    GRAPH_INSTALLATION_ID,
                    "A".repeat(42) + "E",
                    "ANDROID",
                    GRAPH_SCOPE,
                ),
            ).value,
        )
        private set
    var writes = 0
        private set
    var missing = false
    var allowDeletionReplacement = false

    override suspend fun read(): CredentialReadResult =
        if (missing) {
            CredentialReadResult.Missing
        } else {
            CredentialReadResult.Present(record)
        }

    override suspend fun readCleanupMarker(): CleanupMarkerReadResult = CleanupMarkerReadResult.Missing

    private fun mutation(): InstallationStorageFailure {
        writes++
        return GRAPH_UNSUPPORTED
    }

    override suspend fun createIfMissing(record: InstallationCredentialRecord): CredentialCreateResult = mutation()

    override suspend fun replace(
        expectedGeneration: Long,
        record: InstallationCredentialRecord,
    ): CredentialReplaceResult {
        if (!allowDeletionReplacement) return mutation()
        writes++
        if (missing) return CredentialReplaceResult.Missing
        if (this.record.localGeneration != expectedGeneration) return CredentialReplaceResult.Stale
        this.record = record
        return CredentialReplaceResult.Stored
    }

    override suspend fun delete(
        expectedGeneration: Long,
        expectedMarker: Marker,
    ): CredentialDeleteResult = mutation()

    override suspend fun resetUnreadableAfterConfirmation(expectedMarker: Marker): CredentialResetResult = mutation()

    override suspend fun finishMarkedCleanup(expectedMarker: Marker): CredentialDeleteResult = mutation()

    override suspend fun createCleanupMarkerIfMissing(marker: Marker): CleanupMarkerCreateResult = mutation()

    override suspend fun removeCleanupMarker(expectedMarker: Marker): CleanupMarkerRemoveResult = mutation()
}

internal class GraphPending : PendingComplaintActionStore {
    private val snapshot =
        assertIs<InstallationValueResult.Valid<PendingComplaintSnapshot>>(
            PendingComplaintSnapshot.checked(emptyList()),
        ).value
    var writes = 0
        private set

    override suspend fun read(): PendingReadResult = PendingReadResult.Verified(snapshot)

    private fun mutation(): InstallationStorageFailure {
        writes++
        return GRAPH_UNSUPPORTED
    }

    override suspend fun createIfMissing(slot: PendingComplaintSlot): PendingCreateResult = mutation()

    override suspend fun replace(
        expected: PendingComplaintSlot,
        replacement: PendingComplaintSlot,
    ): PendingReplaceResult = mutation()

    override suspend fun delete(expected: PendingComplaintSlot): PendingDeleteResult = mutation()

    override suspend fun clearForConfirmedRecovery(): PendingClearResult = mutation()
}

internal const val GRAPH_BASE = "https://complaints.example.invalid/gateway"
internal const val GRAPH_DELETION_KEY = "55555555-5555-4555-8555-555555555555"
private const val GRAPH_INSTALLATION_ID = "11111111-1111-4111-8111-111111111111"
private const val GRAPH_SCOPE = "00000000-0000-0000-0000-000000000000"
private const val GRAPH_ROW_ID = "22222222-2222-4222-a222-222222222222"
private val GRAPH_UNSUPPORTED = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED)

private fun graphSessionResponse(): String =
    buildJsonObject {
        put("installationId", GRAPH_INSTALLATION_ID)
        put("accessToken", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzeW50aGV0aWMifQ.c3ludGhldGlj")
        put("tokenType", "Bearer")
        put("credentialVersion", 1)
        put("dataScopeId", GRAPH_SCOPE)
        put("issuedAt", "2026-09-17T00:00:00Z")
        put("expiresInSeconds", 900)
    }.toString()

internal fun graphHistoryResponse(empty: Boolean = false): String =
    buildJsonObject {
        put("notices", buildJsonArray {})
        put(
            "items",
            buildJsonArray {
                if (!empty) {
                    add(
                        buildJsonObject {
                            put("id", GRAPH_ROW_ID)
                            put("kind", "REPORT")
                            put("type", "TECHNICAL")
                            put("subject", "Synthetic connected subject")
                            put("body", "Synthetic connected body")
                            put("status", "OPEN")
                            put("createdAt", "2026-09-17T00:00:00Z")
                            put("updatedAt", "2026-09-17T00:00:00Z")
                            put("version", 1)
                            put("actionTag", "\"complaint-$GRAPH_ROW_ID-v1\"")
                            put("appVersion", JsonNull)
                            put("platform", "ANDROID")
                            put("osVersion", JsonNull)
                            put("manufacturer", JsonNull)
                            put("deviceModel", JsonNull)
                            put("closureReason", JsonNull)
                            put("replyToId", JsonNull)
                        },
                    )
                }
            },
        )
        put("nextCursor", JsonNull)
    }.toString()

internal fun graphHeaders(status: HttpStatusCode = HttpStatusCode.OK): Headers =
    Headers.build {
        append("X-Kira-Complaint-Contract", "1")
        append(HttpHeaders.CacheControl, "no-store, no-transform")
        append(
            HttpHeaders.ContentType,
            if (status == HttpStatusCode.OK) "application/json" else "application/problem+json",
        )
    }
