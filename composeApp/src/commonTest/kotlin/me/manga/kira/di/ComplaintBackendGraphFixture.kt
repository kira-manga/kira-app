package me.manga.kira.di

import io.ktor.client.engine.HttpClientEngine
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
import me.manga.kira.data.remote.complaint.ComplaintSessionEngineOwner
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
    var historyHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(graphHistoryResponse(), HttpStatusCode.OK, graphHeaders())
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
            enrollmentEngine = {
                newOwner("enrollment") { error("Unexpected enrollment for existing fixture identity") }
            },
            sessionEngine = {
                newOwner("session") {
                    sessionCalls++
                    respond(graphSessionResponse(), HttpStatusCode.OK, graphHeaders())
                }
            },
            historyEngine = {
                newOwner("history") { request ->
                    historyCalls++
                    historyHandler(request)
                }
            },
        )

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

internal class GraphEngineOwner(
    private val name: String,
    private val delegate: HttpClientEngine,
    private val events: MutableList<String>,
    private val failAccess: () -> Boolean,
    private val failClose: () -> Boolean,
) : ComplaintSessionEngineOwner {
    var closed: Boolean = false
        private set
    override val engine: HttpClientEngine
        get() {
            if (failAccess()) error("Synthetic borrowed engine getter failure")
            return delegate
        }

    override fun close() {
        if (closed) return
        closed = true
        events += "close:$name"
        delegate.close()
        if (failClose()) error("Synthetic native close details")
    }
}

/** Mutation SPIs fail closed and are counted; these fixtures support existing-identity reads only. */
internal class GraphCredentials : InstallationCredentialStore {
    val record: InstallationCredentialRecord =
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
    var writes = 0
        private set

    override suspend fun read(): CredentialReadResult = CredentialReadResult.Present(record)

    override suspend fun readCleanupMarker(): CleanupMarkerReadResult = CleanupMarkerReadResult.Missing

    private fun mutation(): InstallationStorageFailure {
        writes++
        return GRAPH_UNSUPPORTED
    }

    override suspend fun createIfMissing(record: InstallationCredentialRecord): CredentialCreateResult = mutation()

    override suspend fun replace(
        expectedGeneration: Long,
        record: InstallationCredentialRecord,
    ): CredentialReplaceResult = mutation()

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
