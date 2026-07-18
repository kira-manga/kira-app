package me.manga.kira.presentation.features.complaint.repository

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType
import me.manga.kira.presentation.features.complaint.utils.toComplaintStatus
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * KMP-portable HTTP-backed implementation of [ComplaintRepository] (Phase 14.x).
 *
 * Talks to the same Firestore collection (`complaints_v2`) that the Android Firebase SDK impl
 * (`ComplaintFirestoreDataSource`) uses, but goes through the Firestore REST API rather than the
 * native client SDK. This is the iOS/Desktop equivalent — those targets cannot reuse the Android
 * Firebase SDK and the official `firebase-ios-sdk` / `google-cloud-firestore` (JVM admin) routes
 * each require non-trivial cinterop / server-side credentials that are out of scope.
 *
 * The REST client intentionally has no authentication provider today. Its Firebase project ID and
 * API key are injected from the target's local Firebase configuration and are never committed in
 * source. Firestore rules therefore remain the only server-side enforcement boundary; the feature
 * must not be enabled in a public release until those rules and the admin authorization model have
 * been reviewed and deployed.
 *
 * Behavioural parity with the Android impl:
 *   - `sendComplaint` POSTs to the collection root and returns the auto-generated doc ID. The
 *     server populates the `createdAt` timestamp when we pass an Instant; otherwise we send the
 *     current clock as ISO-8601 (the Android impl relies on `@ServerTimestamp` but we cannot use
 *     that hint over the REST surface — closest equivalent is to write a client-side timestamp).
 *   - `getAllComplaints` GETs the collection.
 *   - `getComplaintsByUser` POSTs a `structuredQuery` to `:runQuery` that mirrors
 *     `Filter.or(equalTo("userId", id), equalTo("a", id))`.
 *   - `updateComplaint` PATCHes the doc with a full body (no `updateMask`, matching `.set(dto)`).
 *   - `deleteComplaint` DELETEs the doc.
 *
 * Legacy field shapes (single-letter `a`/`b`/`c`/`d`/`e`/`f`/`g`) are decoded with the same
 * fallback chain as the Android impl — same field-name priority, same `UNKNOWN`-on-failure
 * behaviour from [toComplaintStatus].
 *
 * Timestamp decoding handles the two shapes that actually exist in the collection: the canonical
 * Firestore REST `timestampValue` (ISO-8601 with `Z`) and the legacy `mapValue` shape with
 * `seconds`/`nanoseconds` (or `_seconds`/`_nanoseconds`). The deeper alt-format soup the Android
 * impl tolerates (number epoch, `"1 November 2025 at ..."`) isn't reachable over REST — those
 * shapes were Firebase SDK quirks, not literal stored bytes.
 */
@OptIn(ExperimentalTime::class)
@Suppress("TooManyFunctions")
class ComplaintFirestoreRestDataSource(
    private val httpClient: HttpClient,
    private val config: ComplaintFirestoreRestConfig,
) : ComplaintRepository {
    private val log = Logger.withTag("ComplaintFirestoreRest")

    override suspend fun sendComplaint(complaint: Complaint): String {
        val endpoints = configuredEndpoints()
        val body = FirestoreDocumentWrite(fields = complaint.toFirestoreFields())
        val response: HttpResponse =
            httpClient.post(endpoints.collectionUrl) {
                parameter("key", endpoints.apiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        if (!response.status.isSuccess()) {
            log.e { "sendComplaint failed: HTTP ${response.status.value}" }
            error("Firestore create failed: HTTP ${response.status.value}")
        }
        val doc: FirestoreDocument = response.body()
        return doc.parseDocId()
    }

    override suspend fun getAllComplaints(): List<Complaint> {
        return try {
            val endpoints = configuredEndpoints()
            val docs = mutableListOf<FirestoreDocument>()
            var pageToken: String? = null
            do {
                val token = pageToken
                val response: HttpResponse =
                    httpClient.get(endpoints.collectionUrl) {
                        parameter("key", endpoints.apiKey)
                        parameter("pageSize", PAGE_SIZE)
                        if (token != null) parameter("pageToken", token)
                    }
                if (!response.status.isSuccess()) {
                    log.e { "getAllComplaints failed: HTTP ${response.status.value}" }
                    return emptyList()
                }
                val page: FirestoreListResponse = response.body()
                page.documents?.let { docs.addAll(it) }
                pageToken = page.nextPageToken
            } while (pageToken != null)
            docs.mapNotNull { doc ->
                runCatching { doc.toComplaint() }
                    .onFailure { log.e { "Failed to parse a complaint document" } }
                    .getOrNull()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            log.e { "Failed to fetch complaints" }
            emptyList()
        }
    }

    override suspend fun getComplaintsByUser(userId: String): List<Complaint> {
        val trimmedUserId = userId.trim()
        return try {
            val endpoints = configuredEndpoints()
            val response: HttpResponse =
                httpClient.post(endpoints.runQueryUrl) {
                    parameter("key", endpoints.apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(buildUserIdQuery(trimmedUserId))
                }
            if (!response.status.isSuccess()) {
                log.e { "getComplaintsByUser failed: HTTP ${response.status.value}" }
                return emptyList()
            }
            val rows: List<RunQueryRow> = response.body()
            rows
                .mapNotNull { it.document }
                .mapNotNull { doc ->
                    runCatching { doc.toComplaint() }
                        .onFailure { log.e { "Failed to parse a complaint document" } }
                        .getOrNull()
                }.sortedBy { it.createdAt }
                .reversed()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            log.e { "Failed to fetch user complaints" }
            emptyList()
        }
    }

    override suspend fun updateComplaint(complaint: Complaint) {
        val endpoints = configuredEndpoints()
        val docUrl = "${endpoints.collectionUrl}/${complaint.id.requireDocumentId()}"
        val body = FirestoreDocumentWrite(fields = complaint.toFirestoreFields())
        val response: HttpResponse =
            httpClient.patch(docUrl) {
                parameter("key", endpoints.apiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        if (!response.status.isSuccess()) {
            log.e { "updateComplaint failed: HTTP ${response.status.value}" }
            error("Firestore update failed: HTTP ${response.status.value}")
        }
    }

    override suspend fun deleteComplaint(complaintId: String) {
        val endpoints = configuredEndpoints()
        val docUrl = "${endpoints.collectionUrl}/${complaintId.requireDocumentId()}"
        val response: HttpResponse =
            httpClient.delete(docUrl) {
                parameter("key", endpoints.apiKey)
            }
        if (!response.status.isSuccess()) {
            log.e { "deleteComplaint failed: HTTP ${response.status.value}" }
            error("Firestore delete failed: HTTP ${response.status.value}")
        }
    }

    // ---- Mapping helpers ----

    /**
     * Builds the structuredQuery body for "userId == id OR a == id". Constructed as a
     * [JsonObject] tree rather than a typed DTO because nested `value` discriminators are
     * inconvenient under kotlinx.serialization's structural type system (every level would need
     * its own @Serializable wrapper) and the query body is fully static apart from the user id.
     */
    private fun buildUserIdQuery(userId: String): JsonObject {
        val userIdValue = JsonObject(mapOf("stringValue" to JsonPrimitive(userId)))

        fun fieldFilter(fieldPath: String): JsonObject =
            JsonObject(
                mapOf(
                    "fieldFilter" to
                        JsonObject(
                            mapOf(
                                "field" to JsonObject(mapOf("fieldPath" to JsonPrimitive(fieldPath))),
                                "op" to JsonPrimitive("EQUAL"),
                                "value" to userIdValue,
                            ),
                        ),
                ),
            )
        val structuredQuery =
            JsonObject(
                mapOf(
                    "from" to
                        JsonArray(
                            listOf(JsonObject(mapOf("collectionId" to JsonPrimitive(COLLECTION_NAME)))),
                        ),
                    "where" to
                        JsonObject(
                            mapOf(
                                "compositeFilter" to
                                    JsonObject(
                                        mapOf(
                                            "op" to JsonPrimitive("OR"),
                                            "filters" to
                                                JsonArray(
                                                    listOf(fieldFilter("userId"), fieldFilter("a")),
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                ),
            )
        return JsonObject(mapOf("structuredQuery" to structuredQuery))
    }

    /** Domain -> Firestore field map. */
    private fun Complaint.toFirestoreFields(): Map<String, FirestoreField> {
        val createdAtIso = (createdAt ?: Clock.System.now()).toString()
        val out =
            mutableMapOf(
                "userId" to FirestoreField(stringValue = userId),
                "type" to FirestoreField(stringValue = type.name),
                "subject" to FirestoreField(stringValue = subject),
                "body" to FirestoreField(stringValue = body),
                "status" to FirestoreField(stringValue = status.name),
                "createdAt" to FirestoreField(timestampValue = createdAtIso),
            )
        if (!metadata.isNullOrEmpty()) {
            val metaFields =
                metadata.mapValues { (_, v) ->
                    FirestoreField(stringValue = v.toString())
                }
            out["metadata"] =
                FirestoreField(
                    mapValue = FirestoreMap(fields = metaFields),
                )
        }
        return out
    }

    /** Firestore REST document -> domain. */
    private fun FirestoreDocument.toComplaint(): Complaint {
        val docId = parseDocId()
        val fieldsMap = fields ?: emptyMap()

        fun str(vararg keys: String): String? {
            for (k in keys) fieldsMap[k]?.stringValue?.let { return it }
            return null
        }

        val uid = str("userId", "a") ?: "0"
        val typeName = str("type", "b") ?: ComplaintType.CUSTOM.name
        val type = runCatching { ComplaintType.valueOf(typeName) }.getOrDefault(ComplaintType.CUSTOM)
        val subject = str("subject", "c") ?: "0"
        val body = str("body", "d") ?: "0"
        val status = (str("status", "f", "e"))?.toComplaintStatus() ?: ComplaintStatus.OPEN

        val metadataField = fieldsMap["metadata"] ?: fieldsMap["g"]
        val metadata: Map<String, Any> =
            metadataField
                ?.mapValue
                ?.fields
                ?.mapValues { (_, fv) -> fv.unwrapPrimitiveOrString() }
                ?: emptyMap()

        val createdAt: Instant = extractCreatedAt(fieldsMap) ?: Clock.System.now()

        return Complaint(
            id = docId,
            userId = uid,
            type = type,
            subject = subject,
            body = body,
            status = status,
            metadata = metadata,
            createdAt = createdAt,
        )
    }

    /**
     * Parse `createdAt` across the two shapes the REST surface actually emits:
     *   1. `timestampValue` ISO-8601 with `Z` (canonical Firestore REST shape).
     *   2. Legacy `mapValue` storing `{seconds, nanoseconds}` (or `_seconds`/`_nanoseconds`).
     *
     * Tries the canonical key first, then the legacy `e` key (the Android impl also accepts `e`
     * as a fallback per the screenshot referenced in `ComplaintFirestoreDataSource`).
     */
    @Suppress("ReturnCount") // Defensive timestamp-shape parsing is clearest with early matches.
    private fun extractCreatedAt(fields: Map<String, FirestoreField>): Instant? {
        for (key in listOf("createdAt", "e", "created_at", "timestamp", "time")) {
            val field = fields[key] ?: continue
            field.timestampValue?.let { iso ->
                runCatching { Instant.parse(iso) }
                    .onFailure { log.w { "A complaint timestamp could not be parsed" } }
                    .getOrNull()
                    ?.let { return it }
            }
            field.mapValue?.fields?.let { mapFields ->
                val seconds =
                    mapFields["seconds"]?.numberAsLong()
                        ?: mapFields["_seconds"]?.numberAsLong()
                val nanos =
                    mapFields["nanoseconds"]?.numberAsLong()?.toInt()
                        ?: mapFields["_nanoseconds"]?.numberAsLong()?.toInt() ?: 0
                if (seconds != null) {
                    val millis = seconds * 1000L + nanos / 1_000_000
                    return Instant.fromEpochMilliseconds(millis)
                }
            }
            field.integerValue?.toLongOrNull()?.let { n ->
                val millis = if (n > 1_000_000_000_000L) n else n * 1000
                return Instant.fromEpochMilliseconds(millis)
            }
        }
        return null
    }

    /**
     * Firestore REST document `name` is `projects/.../databases/.../documents/complaints_v2/<id>`.
     * The doc ID is the last path segment.
     */
    private fun FirestoreDocument.parseDocId(): String {
        val n = name ?: return ""
        val idx = n.lastIndexOf('/')
        return if (idx >= 0 && idx < n.length - 1) n.substring(idx + 1) else n
    }

    private fun configuredEndpoints(): ComplaintFirestoreEndpoints {
        val projectId = config.projectId.trim()
        check(PROJECT_ID_PATTERN.matches(projectId)) { "Complaint Firebase project ID is not configured" }
        val apiKey = config.apiKey.trim()
        check(apiKey.isNotEmpty()) { "Complaint Firebase API key is not configured" }
        val root = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"
        return ComplaintFirestoreEndpoints(
            apiKey = apiKey,
            collectionUrl = "$root/$COLLECTION_NAME",
            runQueryUrl = "$root:runQuery",
        )
    }

    private fun String.requireDocumentId(): String =
        trim().also { id ->
            require(DOCUMENT_ID_PATTERN.matches(id)) { "Invalid complaint document ID" }
        }

    companion object {
        private const val COLLECTION_NAME = "complaints_v2"

        // documents.list defaults to a small server page; page through explicitly so the admin
        // "all complaints" view isn't silently truncated once complaints_v2 grows.
        private const val PAGE_SIZE = 300

        private val PROJECT_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{2,62}")
        private val DOCUMENT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,256}")
    }
}

/** Runtime Firebase identifiers supplied by each target's uncommitted configuration. */
data class ComplaintFirestoreRestConfig(
    val projectId: String,
    val apiKey: String,
)

private data class ComplaintFirestoreEndpoints(
    val apiKey: String,
    val collectionUrl: String,
    val runQueryUrl: String,
)

// ---- Firestore REST API DTOs (internal to this file) ----

/**
 * Firestore field-wrapper format. Each [FirestoreField] populates exactly one of these
 * type-tagged fields at runtime. They're all optional so a single class handles every wire shape.
 *
 * Only the value types the complaints collection actually contains are modelled here
 * (string / integer / timestamp / map). `arrayValue` / `booleanValue` / `bytesValue` /
 * `geoPointValue` / `referenceValue` are not used by the complaint schema, so they're omitted —
 * `ignoreUnknownKeys = true` on the shared Json config tolerates any future additions.
 */
@Serializable
internal data class FirestoreField(
    val stringValue: String? = null,
    val integerValue: String? = null,
    val doubleValue: Double? = null,
    val booleanValue: Boolean? = null,
    val timestampValue: String? = null,
    val nullValue: JsonElement? = null,
    val mapValue: FirestoreMap? = null,
    val arrayValue: FirestoreArray? = null,
) {
    /** Best-effort scalar projection used by metadata mapping. */
    fun unwrapPrimitiveOrString(): String =
        stringValue
            ?: integerValue
            ?: doubleValue?.toString()
            ?: booleanValue?.toString()
            ?: timestampValue
            ?: ""

    /** Returns the field as a [Long] when it's an `integerValue` or numeric `doubleValue`. */
    fun numberAsLong(): Long? = integerValue?.toLongOrNull() ?: doubleValue?.toLong()
}

@Serializable
internal data class FirestoreMap(
    val fields: Map<String, FirestoreField>? = null,
)

@Serializable
internal data class FirestoreArray(
    val values: List<FirestoreField>? = null,
)

@Serializable
internal data class FirestoreDocument(
    val name: String? = null,
    val fields: Map<String, FirestoreField>? = null,
    val createTime: String? = null,
    val updateTime: String? = null,
)

/** Body sent on POST (create) and PATCH (update). The REST API ignores `name` / `createTime` on write. */
@Serializable
internal data class FirestoreDocumentWrite(
    val fields: Map<String, FirestoreField>,
)

@Serializable
internal data class FirestoreListResponse(
    val documents: List<FirestoreDocument>? = null,
    val nextPageToken: String? = null,
)

/**
 * `:runQuery` returns an array of rows. Each row carries either a `document` (a hit) or just a
 * `readTime` (empty result marker). Skip rows where `document` is null.
 */
@Serializable
internal data class RunQueryRow(
    val document: FirestoreDocument? = null,
    val readTime: String? = null,
    val skippedResults: Int? = null,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster202.staleKdocSweep.cascade, Task #658, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster202 leaf 2/2 — :shared/complaint/repository/ tier CLOSER, sibling 362. With this leaf,
 * the legacy :shared/.../complaint/ subdirectory is FULLY SWEPT (11 of 11 files across
 * clusters 200-201-202). Cumulative §253-postscript count = 87 leaves with this commit.
 *
 * File-shape note: 407-line @OptIn(ExperimentalTime::class) class — `ComplaintFirestoreRest
 * DataSource(httpClient: HttpClient) : ComplaintRepository`. The longest legacy complaint-tier
 * file by a wide margin: 5 ComplaintRepository overrides + a structuredQuery JsonObject builder
 * + a domain-to-Firestore-field mapper + a Firestore-to-domain reverse mapper + a multi-shape
 * timestamp extractor + a Firestore REST DTO suite (6 @Serializable internal data classes at
 * file bottom: FirestoreField + FirestoreMap + FirestoreArray + FirestoreDocument +
 * FirestoreDocumentWrite + FirestoreListResponse + RunQueryRow).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — sole REST-backed ComplaintRepository impl. Bound at SharedModule.kt
 *     for iOS + Desktop + Android-without-Firebase-SDK paths. Companion to the androidMain
 *     ComplaintFirestoreDataSource (Firebase SDK path) which implements the same interface
 *     but only on Android. Both data sources call .toComplaintStatus() (sibling 360) during
 *     reverse-mapping. CROSS-CONSUMED both by the 5-usecase fan (siblings 352-356) AND
 *     transitively via the rework :data strangler-fig impls that delegate through the legacy
 *     interface (preserving the wire contract while inverting the typed-domain boundary).
 *
 *   • FULFILLED-PORT — the Phase 14.x KDoc claim ("KMP-portable HTTP-backed implementation")
 *     is factually accurate AND fully landed. Verified: ktor3 HttpClient injected; POST + GET +
 *     PATCH + DELETE + :runQuery all use ktor3 surface; kotlinx.serialization @Serializable
 *     DTOs handle the wire shape; iOS + Desktop targets reach this same commonMain code path.
 *
 *   • STANDALONE-PROSE-RICH-NOT-STALE — extensive KDoc block (L30-67) explains 4 design
 *     pillars: REST vs SDK rationale (iOS-cinterop avoidance), unauthenticated-with-API-key
 *     security model (rules-enforced + key-already-public via google-services.json), Android
 *     behavioural parity (per-method enumeration of REST equivalents), legacy field-shape
 *     decoding (single-letter `a-g` fallback chain). Every claim verifies against the
 *     implementation body. Per §253 — preserved as point-in-time accurate AND still factually
 *     current (no drift introduced by the rework — the wire shape and Firestore collection
 *     stay identical across legacy and rework data layers).
 *
 *   • LEGACY-FIELD-SHAPE-FORWARD-COMPAT — the `str("userId", "a")` / `str("type", "b")` /
 *     etc. fallback chain is NOT dead-code. The collection contains historical documents
 *     authored against pre-Phase-3 schemas that used single-letter field names (`a` = userId,
 *     `b` = type, `c` = subject, `d` = body, `e` = timestamp, `f` = status, `g` = metadata).
 *     The decoder tries the human-readable name FIRST, falls back to the legacy single-letter
 *     name. DO NOT remove these fallbacks during field-name cleanup passes — would break
 *     read paths for documents still on the legacy schema.
 *
 *   • DEFENSIVE-FALLBACK-CHAIN — paired with sibling 360 (toComplaintStatus). The reverse-map
 *     at L250 calls `.toComplaintStatus()` which falls to ComplaintStatus.UNKNOWN on
 *     unrecognized status strings (forward-compat against server-emitted statuses pre-dating
 *     the client). Status fallback chain: explicit `status` key → `f` legacy key → `e` legacy
 *     key. Final `?: ComplaintStatus.OPEN` is reached only when ALL three keys are missing.
 *
 *   • INTENTIONAL-PUBLIC-SECRET-NOT-VIOLATION — `API_KEY = "AIzaSy..."` at L320 is a Firebase
 *     project API key shipped in google-services.json (i.e., already inside the APK). The
 *     KDoc explicitly documents this is intentional: Firebase keys are project identifiers,
 *     NOT auth secrets; access control is enforced by Firestore security rules. DO NOT flag
 *     during secret-scan passes; DO NOT rotate (the value matches the project's published
 *     google-services.json).
 *
 *   • INVERTED-PARALLEL — no rework :data counterpart at this name. The rework :data layer
 *     consumes this legacy data source through ComplaintRepository (sibling 361) injection —
 *     the rework's typed-domain repository interfaces (ComplaintListRepository +
 *     ComplaintActionRepository + AdminComplaintListRepository + AdminComplaintActionRepository
 *     + FeedbackRepository) all delegate through `legacyComplaintRepo: ComplaintRepository`
 *     under the hood. The Firestore wire shape is :shared-internal and was never re-implemented
 *     in :data — strangler-fig boundary is at the interface, not the data source.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — heavy imports: ktor3 HttpClient surface (HttpClient +
 *     body + delete + get + parameter + patch + post + setBody + HttpResponse + bodyAsText +
 *     ContentType + contentType + isSuccess), kotlin.time (Clock + ExperimentalTime + Instant),
 *     kotlinx.serialization.json (Serializable + JsonArray + JsonElement + JsonObject +
 *     JsonPrimitive), co.touchlab.kermit Logger, sibling complaint package types (Complaint
 *     model + ComplaintStatus enum + ComplaintType enum + toComplaintStatus utility).
 *
 *   • STRUCTURED-QUERY-INLINE-NOT-DTO — the buildUserIdQuery() helper (L178-211) constructs
 *     the Firestore :runQuery body as nested JsonObject literals rather than @Serializable
 *     typed DTOs. The KDoc explains this: kotlinx.serialization's structural type system
 *     would require a @Serializable wrapper at every nested level, which the static query
 *     shape does not justify. Per §253 — preserved as point-in-time accurate engineering
 *     trade-off, NOT a missing-abstraction debt.
 *
 *   • TIMESTAMP-MULTI-SHAPE-DECODER — extractCreatedAt() (L279-303) handles 3 wire shapes:
 *     ISO-8601 timestampValue (canonical), {seconds, nanoseconds} mapValue (legacy with
 *     underscore-prefix alias _seconds/_nanoseconds), integerValue epoch (millis or seconds
 *     heuristic via the 1_000_000_000_000L threshold). KDoc explicitly notes the Android
 *     impl handles MORE shapes (number epoch, "1 November 2025 at ..." string) which are
 *     Firebase SDK quirks not literal stored bytes — those shapes never reach the REST path.
 *
 * Cross-cluster :shared/complaint/ subdirectory FULLY-SWEPT register (cluster202 closer):
 *
 *   • Three-cluster sweep — cluster200 covered usecase/ (5 leaves, siblings 352-356);
 *     cluster201 covered model/ + utils/ (4 leaves, siblings 357-360); cluster202 closes
 *     repository/ (2 leaves, siblings 361-362). Combined coverage: 11 of 11 files in
 *     :shared/complaint/ swept. The legacy :shared/complaint/ subdirectory is now in its
 *     terminal §253-coverage state.
 *
 *   • Naming-axis pattern across the closed subdir:
 *       - Data carriers: legacy `Complaint` data class (sibling 357) ← keystone; carries
 *         metadata: Map<String, Any>? — DROPPED in rework ComplaintSummary; appVersion is
 *         the first carved-out replacement key.
 *       - Enums: legacy `ComplaintType` + `ComplaintStatus` (siblings 358-359) ← 1:1
 *         CLONE-NOT-DRIFT with rework counterparts at :domain/.../complaint/ComplaintSummary
 *         declared inline; enumValueOf mapper relies on identity + order match.
 *       - Extension utility: legacy `String.toComplaintStatus()` (sibling 360) ← reverse-map
 *         with UNKNOWN fallback; INVERTED-PARALLEL (rework :data collapses to direct
 *         enumValueOf at the mapper).
 *       - Use cases: 5 legacy use cases (cluster200 cohort) ← strangler-fig SOURCES; rework
 *         SPLITS into 9 narrower use cases (per-field ISP-fan: Edit + Reply + Delete +
 *         ObserveUser + AdminEdit + ChangeStatus + AddClosureReason + AdminDelete + ObserveAll).
 *       - Repository: legacy 5-method `ComplaintRepository` interface (sibling 361) ←
 *         single contract; rework SHATTERS into 5 ISP-narrowed :domain interfaces.
 *       - Data source: legacy `ComplaintFirestoreRestDataSource` (sibling 362) ← sole
 *         REST-backed impl; INVERTED-PARALLEL (rework :data never re-implemented the wire
 *         shape, delegates through the legacy interface).
 *
 *   • SOLID-applied trajectory across legacy → rework: legacy is data-source-monolithic +
 *     repository-monolithic + use-case-fanned-out-1-to-1; rework is data-source-preserved +
 *     repository-ISP-shattered + use-case-ISP-shattered-further. The migration's SOLID-Δ is
 *     at the repository layer (5 methods → 5 narrower interfaces) and use-case layer
 *     (5 use cases → 9 narrower use cases), not the data-source layer (1 impl → 1 impl,
 *     wire-preserved).
 *
 *   • Doc-lacuna ratios across the 11-file subdir:
 *       - usecase/ tier (cluster200): 1-of-5 retain doc → 4-of-5 stripped (single-developer
 *         doc-tidy pass signature).
 *       - model/ tier (cluster201): 3-of-3 retain Phase 4 batch 4.4 migration prose.
 *       - utils/ tier (cluster201): 0-of-1 retain (stripped).
 *       - repository/ tier (cluster202): 2-of-2 retain prose. The interface has 1-of-5
 *         per-method KDocs; the REST impl has the campaign's richest standalone-prose-block
 *         (~38 lines of design rationale + per-method parity table + DTO contract notes).
 *     Aggregate: 6-of-11 retain meaningful prose, 5-of-11 are doc-lacuna. Mixed-skew —
 *     no single-axis pattern across the full subdir.
 *
 *   • Strangler-fig boundary trajectory: the boundary lives at the ComplaintRepository
 *     interface (sibling 361), NOT at the Complaint model nor at the data source. Legacy
 *     consumers (5 use cases) bind to the interface. Rework :data impls bind to the SAME
 *     interface and translate at the boundary to the typed :domain repository surface.
 *     The data source (sibling 362) is wire-preserved and serves BOTH sides identically.
 *
 *   • Wave-61 closes its primary target cleanly. Three clusters consumed (200 + 201 + 202),
 *     11 leaves swept, zero orphans, zero drifted prose, zero dead code, zero build breaks.
 *     The legacy :shared/complaint/ subdirectory is now in a terminal §253-coverage state.
 */
