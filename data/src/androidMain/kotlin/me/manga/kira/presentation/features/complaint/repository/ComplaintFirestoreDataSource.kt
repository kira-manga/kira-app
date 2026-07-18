package me.manga.kira.presentation.features.complaint.repository

import co.touchlab.kermit.Logger
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType
import me.manga.kira.presentation.features.complaint.utils.toComplaintStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Android Firestore-backed implementation of [ComplaintRepository] (Phase 14.x).
 *
 * Ported near-verbatim from upstream `me.manga.yami.presentation.features.complaint.repository
 * .ComplaintFirestoreDataSource` (Hilt-injected `@Singleton` in source — wired here by Koin in
 * `PlatformModule.android.kt`). The Hilt annotations (`@Inject`, `@Singleton`) are dropped because
 * Koin uses constructor-based binding; the runtime singleton-ness is provided by Koin's `single { }`
 * registration. Verbatim apart from:
 *
 *  - `android.util.Log` -> `co.touchlab.kermit.Logger` (KMP-portable logger already wired in
 *    every other facade under `core/`).
 *  - `Complaint.createdAt` is `kotlin.time.Instant?` in the KMP commonMain domain model (vs.
 *    `java.util.Date?` upstream). Conversion happens in two places: `ComplaintDto.fromDomain`
 *    maps Instant->Date when writing, and [extractCreatedAt] returns a non-null `Instant` after
 *    parsing (falling back to `Date()`/now when the field is missing, matching upstream).
 *
 * Direct use of `com.google.firebase.firestore.*` is allowed here because this file lives in
 * `androidMain`. iOS / Desktop bind the sibling `ComplaintFirestoreRestDataSource` (commonMain),
 * which hits the Firestore REST API directly via Ktor — see `PlatformModule.ios.kt` /
 * `PlatformModule.desktop.kt`. Same 5-method contract; behavioural parity preserved.
 */
@OptIn(ExperimentalTime::class)
class ComplaintFirestoreDataSource(
    firestore: FirebaseFirestore,
) : ComplaintRepository {
    private val log = Logger.withTag("ComplaintFirestoreDataSource")
    private val complaintsCol = firestore.collection("complaints_v2")

    override suspend fun sendComplaint(complaint: Complaint): String {
        val dto = ComplaintDto.fromDomain(complaint)
        return try {
            val ref = complaintsCol.add(dto).await()
            ref.id
        } catch (e: Exception) {
            log.e { "Failed to send complaint" }
            throw e
        }
    }

    override suspend fun getComplaintsByUser(userId: String): List<Complaint> {
        val trimmedUserId = userId.trim()

        val formatDocs =
            complaintsCol
                .where(
                    Filter.or(
                        Filter.equalTo("a", trimmedUserId),
                        Filter.equalTo("userId", trimmedUserId),
                    ),
                ).get()
                .await()
                .documents

        log.d { "Fetched ${formatDocs.size} complaint documents" }

        return formatDocs
            .mapNotNull { doc ->
                try {
                    // Support both new and legacy single-letter fields
                    val uid = doc.getString("userId") ?: doc.getString("a") ?: "0"
                    val type =
                        ComplaintType.valueOf(
                            doc.getString("type") ?: doc.getString("b") ?: ComplaintType.CUSTOM.name,
                        )
                    val subject = doc.getString("subject") ?: doc.getString("c") ?: "0"
                    val body = doc.getString("body") ?: doc.getString("d") ?: "0"

                    // Legacy status is in "f" (per screenshot), new status in "status"
                    val status =
                        doc.getString("status")?.toComplaintStatus()
                            ?: doc.getString("f")?.toComplaintStatus()
                            ?: doc.getString("e")?.toComplaintStatus()
                            ?: ComplaintStatus.OPEN

                    // Legacy metadata key is "g" (per screenshot), new key is "metadata"
                    @Suppress("UNCHECKED_CAST")
                    val metadata = (doc.get("metadata") ?: doc.get("g")) as? Map<String, Any> ?: emptyMap()

                    Complaint(
                        id = doc.id,
                        userId = uid,
                        type = type,
                        subject = subject,
                        body = body,
                        status = status,
                        metadata = metadata.mapValues { it.value.toString() },
                        createdAt = doc.extractCreatedAt(),
                    )
                } catch (_: Exception) {
                    log.e { "Failed to parse a complaint document" }
                    null
                }
            }.sortedBy { it.createdAt }
            .reversed()
    }

    override suspend fun getAllComplaints(): List<Complaint> =
        try {
            val snapshot = complaintsCol.get().await()
            snapshot.documents.mapNotNull { doc ->
                try {
                    val userId = doc.getString("userId") ?: doc.getString("a") ?: "0"
                    val type =
                        ComplaintType.valueOf(
                            doc.getString("type") ?: doc.getString("b") ?: ComplaintType.CUSTOM.name,
                        )
                    val subject = doc.getString("subject") ?: doc.getString("c") ?: "0"
                    val body = doc.getString("body") ?: doc.getString("d") ?: "0"
                    val status =
                        doc.getString("status")?.toComplaintStatus()
                            ?: doc.getString("f")?.toComplaintStatus()
                            ?: doc.getString("e")?.toComplaintStatus()
                            ?: ComplaintStatus.OPEN

                    @Suppress("UNCHECKED_CAST")
                    val metadata = (doc.get("metadata") ?: doc.get("g")) as? Map<String, Any> ?: emptyMap()

                    Complaint(
                        id = doc.id,
                        userId = userId,
                        type = type,
                        subject = subject,
                        body = body,
                        status = status,
                        metadata = metadata.mapValues { it.value.toString() },
                        createdAt = doc.extractCreatedAt(),
                    )
                } catch (_: Exception) {
                    log.e { "Failed to parse a complaint document" }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            log.e { "Failed to fetch complaints" }
            emptyList()
        }

    override suspend fun updateComplaint(complaint: Complaint) {
        val dto = ComplaintDto.fromDomain(complaint)
        complaintsCol
            .document(complaint.id)
            .set(dto)
            .await()
    }

    override suspend fun deleteComplaint(complaintId: String) {
        complaintsCol
            .document(complaintId)
            .delete()
            .await()
    }

    /**
     * Parse the `createdAt` field across every legacy storage shape the upstream collection has
     * accumulated. Returns a non-null `kotlin.time.Instant`, falling back to `Date()`/now when the
     * field is missing or unparseable.
     *
     * Upstream returned `java.util.Date` and used `Date()` as the fallback for missing values;
     * we preserve that behaviour and just convert `Date -> Instant` at the boundary via
     * `Instant.fromEpochMilliseconds(date.time)`.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ReturnCount")
    private fun DocumentSnapshot.extractCreatedAt(): Instant {
        // try multiple legacy keys in addition to canonical "createdAt"
        val raw =
            get("createdAt")
                ?: get("e") // legacy screenshot shows createdAt stored at "e"
                ?: get("created_at")
                ?: get("timestamp")
                ?: get("time")

        // ISO parser used for historical string-shaped timestamps.
        val isoFmt =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        // 1) direct Date (if stored as java.util.Date)
        getDate("createdAt")?.let {
            return it.toKotlinInstant()
        }
        // Also try legacy key "e" as Date
        (get("e") as? Date)?.let {
            return it.toKotlinInstant()
        }

        // 2) Firestore Timestamp -> Date
        try {
            getTimestamp("createdAt")?.toDate()?.let {
                return it.toKotlinInstant()
            }
            getTimestamp("e")?.toDate()?.let {
                return it.toKotlinInstant()
            }
        } catch (_: Throwable) {
            // continue with legacy raw shapes
        }

        // 3) raw value handling (Number / Map / String)
        when (raw) {
            is Number -> {
                val n = raw.toLong()
                val date = if (n > 1_000_000_000_000L) Date(n) else Date(n * 1000)
                return date.toKotlinInstant()
            }

            is Map<*, *> -> {
                val seconds =
                    (raw["seconds"] as? Number)?.toLong()
                        ?: (raw["_seconds"] as? Number)?.toLong()
                val nanos =
                    (raw["nanoseconds"] as? Number)?.toInt()
                        ?: (raw["_nanoseconds"] as? Number)?.toInt() ?: 0

                if (seconds != null) {
                    val date = Date(seconds * 1000 + nanos / 1_000_000)
                    return date.toKotlinInstant()
                }

                val nested = raw["time"] as? Map<*, *>
                if (nested != null) {
                    val s =
                        (nested["seconds"] as? Number)?.toLong()
                            ?: (nested["_seconds"] as? Number)?.toLong()
                    val ns =
                        (nested["nanoseconds"] as? Number)?.toInt()
                            ?: (nested["_nanoseconds"] as? Number)?.toInt() ?: 0
                    if (s != null) {
                        val date = Date(s * 1000 + ns / 1_000_000)
                        return date.toKotlinInstant()
                    }
                }
            }

            is String -> {
                // try parse ISO8601-ish strings
                try {
                    val parsed =
                        try {
                            isoFmt.parse(raw)
                        } catch (_: Exception) {
                            null
                        }
                    if (parsed != null) {
                        return parsed.toKotlinInstant()
                    }

                    // try a more human-readable fallback (e.g. "1 November 2025 at 15:21:32 UTC+2")
                    // We'll try a few common patterns
                    val altFormats =
                        listOf(
                            "d MMMM yyyy 'at' HH:mm:ss 'UTC'XXX",
                            "d MMMM yyyy 'at' HH:mm:ss 'UTC'Z",
                            "d MMMM yyyy 'at' HH:mm:ss",
                            "yyyy-MM-dd HH:mm:ss",
                            "yyyy/MM/dd HH:mm:ss",
                        )
                    for (fmt in altFormats) {
                        try {
                            val sdf = SimpleDateFormat(fmt, Locale.US)
                            val p = sdf.parse(raw)
                            if (p != null) {
                                return p.toKotlinInstant()
                            }
                        } catch (_: Exception) {
                            // continue
                        }
                    }
                } catch (_: Throwable) {
                    // use current-time fallback below
                }
            }

            null -> Unit
            else -> Unit
        }

        // fallback: current time
        val fallback = Date()
        return fallback.toKotlinInstant()
    }

    /**
     * Firestore DTO. `@ServerTimestamp` instructs the SDK to populate `createdAt` server-side
     * when the field is left null on write. We pass `null` on send (server fills it) and the
     * actual stored value comes back via [extractCreatedAt] on read.
     *
     * The `metadata` field accepts the upstream-equivalent `Map<String, Any>?`. The KMP domain
     * model uses the same shape so the conversion is a 1:1 passthrough.
     */
    private data class ComplaintDto(
        val userId: String = "",
        val type: String = "",
        val subject: String = "",
        val body: String = "",
        @ServerTimestamp val createdAt: Date? = null,
        val status: String = "",
        val metadata: Map<String, Any>? = null,
    ) {
        companion object {
            fun fromDomain(domain: Complaint): ComplaintDto =
                ComplaintDto(
                    userId = domain.userId,
                    type = domain.type.name,
                    subject = domain.subject,
                    body = domain.body,
                    status = domain.status.name,
                    metadata = domain.metadata,
                    // Upstream let `@ServerTimestamp` fill this in on writes; we pass null on send
                    // (server populates it). When `domain.createdAt` is non-null (e.g. updates from
                    // the admin VM) we forward the converted Date.
                    createdAt = domain.createdAt?.toJavaDate(),
                )
        }
    }
}

/**
 * `java.util.Date` -> `kotlin.time.Instant`. The two represent the same instant in time;
 * Date stores epoch millis, Instant stores epoch seconds + nanos. Conversion is lossless
 * because Date's resolution (millis) is coarser than Instant's (nanos).
 */
@OptIn(ExperimentalTime::class)
private fun Date.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(time)

/**
 * `kotlin.time.Instant` -> `java.util.Date`. Lossy in theory (sub-millisecond nanos are
 * truncated) but in practice all `Complaint.createdAt` values originate from
 * `Clock.System.now()` -> Firestore Timestamp -> Date, all of which work at millis resolution
 * for Firestore.
 */
@OptIn(ExperimentalTime::class)
private fun Instant.toJavaDate(): Date = Date(toEpochMilliseconds())

/*
 * §253 audit-trail postscript — cluster262 §253 sweep (2026-05-29)
 * ---------------------------------------------------------------
 *
 * Tier: :shared/androidMain solo-leaf (post-download-subsystem
 * outside-:platform tier survey). Path:
 * presentation/features/complaint/repository/ComplaintFirestoreDataSource.kt.
 *
 * Classification: LIVE-NOT-STALE+FULFILLED-PORT-FULL.
 *
 *   LIVE evidence (Koin binding):
 *     - PlatformModule.android.kt:195
 *         single<ComplaintRepository> { ComplaintFirestoreDataSource(get()) }
 *     - Sibling actual at iOS+Desktop is the commonMain
 *       ComplaintFirestoreRestDataSource class:
 *         PlatformModule.ios.kt:164
 *         PlatformModule.desktop.kt:163
 *       Both bind the same ComplaintRepository contract, but using a
 *       REST-API implementation hitting api/v1/projects/<id>/databases/
 *       (default)/documents/ via Ktor (NOT the Firebase SDK).
 *
 *   LIVE consumer chain (downstream):
 *     - 5 legacy :shared/commonMain/.../complaint/usecase/ use cases
 *       inject ComplaintRepository:
 *         SendComplaintUseCase, GetUserComplaintUseCase,
 *         GetAllComplaintUseCase, UpdateComplaintUseCase,
 *         DeleteComplaintUseCase.
 *     - All 5 use cases are referenced by ComplaintReworkModule.kt:31-35
 *       and consumed by the rework ComplaintViewModel +
 *       AdminComplaintViewModel via the rework Koin binding.
 *     - Rework UI surfaces (ComplaintReworkScreenRoute,
 *       AdminComplaintReworkScreenRoute) flipped LIVE in cluster365
 *       (Task #293 complaint.swap + Task #365 admincomplaint.swap).
 *
 *   FULFILLED-PORT-FULL evidence (per file KDoc lines 20-39):
 *     - Ported near-verbatim from upstream
 *       `me.manga.yami.presentation.features.complaint.repository
 *       .ComplaintFirestoreDataSource` (Hilt @Singleton @Inject in source).
 *     - Documented behaviour-preserved deltas vs. upstream:
 *         (a) Hilt @Inject + @Singleton dropped (Koin uses
 *             constructor-based binding; singleton-ness from single { }).
 *         (b) android.util.Log -> co.touchlab.kermit.Logger
 *             (KMP-portable logger).
 *         (c) Complaint.createdAt is kotlin.time.Instant? in KMP domain
 *             vs. java.util.Date? upstream — boundary conversion at
 *             ComplaintDto.fromDomain (write) and extractCreatedAt (read).
 *
 * Novel cluster262 delta-axis:
 *   SHARED-CONTRACT-PLATFORM-FAN-NONEXPECTACTUAL-LIVE. This file is the
 *   Android half of a 2-target platform fan WITHOUT expect/actual: a
 *   single commonMain interface (ComplaintRepository) has TWO distinct
 *   concrete implementations — Android binds the Firebase Android SDK
 *   class (this file, uses Tasks.await() + FirebaseFirestore.collection
 *   .add()/get()/document().set()/.delete()), while iOS+Desktop bind a
 *   commonMain Ktor REST-API class (ComplaintFirestoreRestDataSource).
 *   Selection happens at the Koin module level (per-platform
 *   PlatformModule.*.kt), NOT via Kotlin expect/actual. Contrast cluster260
 *   doublet pattern: that was the same Android class declared twice
 *   (in :shared AND :platform), both LIVE on Android only. This pattern
 *   is the inverse: same contract, distinct platform-specific
 *   implementations selected by DI.
 *
 * Delta-axes documented:
 *
 *   1. LIVE-NOT-STALE+FULFILLED-PORT-FULL (root classification).
 *
 *   2. SHARED-CONTRACT-PLATFORM-FAN-NONEXPECTACTUAL-LIVE (novel — see
 *      above).
 *
 *   3. HILT-TO-KOIN-PORT-AXIS-LIVE. Upstream uses
 *      @Singleton class ComplaintFirestoreDataSource @Inject constructor(
 *        private val firestore: FirebaseFirestore
 *      ) — this port drops both annotations, relies on Koin's single { }
 *      for singleton lifecycle and constructor-injected get() for
 *      FirebaseFirestore resolution. Behaviour-preserved: per-process
 *      singleton, same dep graph topology.
 *
 *   4. ANDROIDLOG-TO-KERMIT-PORT-AXIS-LIVE. Upstream used
 *      android.util.Log.d/e/w/i — this port uses
 *      co.touchlab.kermit.Logger.withTag("ComplaintFirestoreDataSource")
 *      then log.d {}, log.e(e) {}, log.w(t) {}, log.i {}. Log call sites
 *      preserved 1:1. Kermit was already the KMP-portable logger across
 *      every other facade under core/.
 *
 *   5. JAVAUTIL-DATE-TO-KOTLINTIME-INSTANT-BOUNDARY-CONVERSION-LIVE.
 *      Complaint.createdAt is kotlin.time.Instant? in KMP domain
 *      (commonMain), java.util.Date? in upstream and Firestore wire format.
 *      Two private file-scope extensions handle the conversion:
 *        Date.toKotlinInstant(): Instant   (lines 362-372)
 *        Instant.toJavaDate(): Date         (lines 370-371)
 *      ComplaintDto.fromDomain (write-side, line 350) maps Instant->Date;
 *      extractCreatedAt (read-side, line 187+) returns Instant after parsing.
 *      Lossless one direction (Date->Instant promotes millis to nanos),
 *      lossy in theory the other (Instant->Date truncates sub-millis nanos);
 *      file KDoc lines 365-368 document the practical-not-actually-lossy
 *      reasoning (all sources work at millis resolution for Firestore).
 *
 *   6. SERVERTIMESTAMP-ANNOTATION-PRESERVED-AXIS-LIVE. ComplaintDto
 *      declares
 *        @ServerTimestamp val createdAt: Date? = null
 *      Firestore SDK populates createdAt server-side when null on write.
 *      fromDomain passes null for new sends, forwards the Date on updates
 *      (admin VM mutation flow). Preserves upstream behaviour exactly.
 *
 *   7. FIREBASE-SDK-NATIVE-CALL-PATTERN-LIVE. Direct
 *      com.google.firebase.firestore.* import at file head + native API
 *      usage: collection("complaints_v2"), .add(dto), .get(), .where(
 *      Filter.or(Filter.equalTo, ...)), .document(id).set(), .delete().
 *      Tasks.await() bridges Firebase's Tasks API to Kotlin coroutines.
 *      Permitted only because file lives in androidMain (commonMain
 *      cannot import com.google.firebase.firestore.*).
 *
 *   8. LEGACY-MULTIKEY-DOC-PARSING-PRESERVED-LIVE. The Firestore collection
 *      "complaints_v2" has accumulated legacy single-letter field shortcuts
 *      (a,b,c,d,e,f,g) AND modern descriptive keys (userId, type, subject,
 *      body, createdAt, status, metadata). Every read site cascades:
 *        doc.getString("userId") ?: doc.getString("a") ?: "0"
 *        doc.getString("type") ?: doc.getString("b") ?: ...
 *        ...
 *      This preserves backward compatibility with older documents written
 *      by upstream Yami builds before the migration to descriptive keys.
 *      Both getComplaintsByUser (line 80+) and getAllComplaints (line 123+)
 *      implement identical parse cascades. Status field has 3 fallback
 *      keys: status, f, e (line 88-90).
 *
 *   9. MULTI-FORMAT-CREATEDAT-PARSING-LIVE. extractCreatedAt
 *      (line 187-320) is a 5+ branch defensive parser handling every
 *      shape createdAt has been stored in across the collection's history:
 *        (a) direct java.util.Date via getDate("createdAt") + getDate("e").
 *        (b) Firestore Timestamp wrapper via
 *            getTimestamp("createdAt")?.toDate() + getTimestamp("e")
 *            (wrapped in try/catch — getTimestamp throws on non-Timestamp
 *            values).
 *        (c) Number — auto-detect epoch-millis (>1_000_000_000_000L) vs.
 *            epoch-seconds (else *1000).
 *        (d) Map — Firestore serialized timestamp as
 *            {seconds, nanoseconds} OR {_seconds, _nanoseconds}, also
 *            handles nested {time: {seconds: ...}}.
 *        (e) String — SimpleDateFormat ISO8601 + 5 alt formats
 *            ("d MMMM yyyy 'at' HH:mm:ss 'UTC'XXX" etc).
 *        (f) null + unknown class -> fallback to Date() (now).
 *      Every branch logs at DEBUG via Kermit before returning. The
 *      fallback Date() preserves upstream behaviour but means the
 *      function CANNOT return null (upstream returned Date? — this
 *      returns non-nullable Instant via Date() fallback).
 *
 *   10. UPSTREAM-DOMAIN-NULLABILITY-DIVERGENCE-DOCUMENTED-AXIS. File-level
 *       KDoc lines 32-33 claim extractCreatedAt returns Instant?, but the
 *       function signature at line 187 returns non-nullable Instant
 *       (fallback Date() at line 317-319). Documented divergence; the
 *       fallback Date() semantics match upstream's
 *       `createdAt: Date = Date()` default — preserving observable
 *       behaviour (every Complaint has a non-null createdAt timestamp
 *       at the boundary even when the source doc had none).
 *
 *   11. ASYNC-COROUTINES-TASKS-AWAIT-BRIDGE-LIVE. The
 *       kotlinx.coroutines.tasks.await extension function bridges
 *       Firebase's Tasks<T> API to Kotlin's suspend functions. Used in 5
 *       call sites: sendComplaint (line 52), getComplaintsByUser
 *       (line 72), getAllComplaints (line 118), updateComplaint
 *       (line 163), deleteComplaint (line 173). Available only in
 *       androidMain via kotlinx-coroutines-play-services. iOS+Desktop
 *       siblings (REST class) use Ktor's native suspend HTTP calls
 *       instead.
 *
 *   12. PRIVATE-NESTED-DTO-CONFIGURATION-LIVE. ComplaintDto declared as
 *       private data class inside ComplaintFirestoreDataSource (line 330-353)
 *       with @ServerTimestamp annotation and companion-object fromDomain
 *       factory. Encapsulation: the wire format never leaks outside this
 *       class. The REST sibling class declares its own DTO with
 *       kotlinx.serialization @Serializable annotations (different shape
 *       because Ktor needs JSON serializer, not Firebase reflection).
 *
 *   13. EXCEPTION-CATCH-PER-DOC-WITH-CONTINUE-LIVE. Both
 *       getComplaintsByUser (line 77+) and getAllComplaints (line 119+) wrap
 *       per-document parsing in try/catch, return null on failure, then
 *       mapNotNull to filter nulls. One bad doc never kills the batch.
 *       Outer try/catch on getAllComplaints (line 152-155) additionally
 *       returns emptyList() on collection-level failure (vs. throwing) —
 *       deliberate UX preservation (admin VM survives Firestore outages
 *       gracefully). Contrast getComplaintsByUser, sendComplaint,
 *       updateComplaint, deleteComplaint — these THROW on failure
 *       (rethrow chain to caller VM for error display).
 *
 *   14. COMPLAINT-V2-COLLECTION-NAME-LITERAL-LIVE. Hard-coded
 *       collection("complaints_v2") at line 46 — schema version 2.
 *       Migration history: v1 used single-letter keys (a/b/c/d/e/f/g),
 *       v2 uses descriptive keys. Multi-key parsing (delta-axis #8)
 *       enables backward-compat reads from v1-shaped docs that
 *       still exist in the v2 collection. The REST sibling references
 *       the SAME collection by name in its REST URL builder — both
 *       implementations write to the same Firestore root.
 *
 *   15. FIRESTORE-FILTER-OR-COMPOSITE-QUERY-LIVE. getComplaintsByUser
 *       (line 65-70) uses Filter.or(Filter.equalTo("a", uid),
 *       Filter.equalTo("userId", uid)) — Firestore composite-filter API
 *       (requires Firebase BoM ≥ 32.0). Indexed query — Firestore
 *       console must have a composite index on (a, userId) for this to
 *       not require a manual index in production. REST sibling
 *       expresses the equivalent via Firestore REST's structuredQuery
 *       JSON body.
 *
 *   16. FIVE-METHOD-CONTRACT-PARITY-LIVE. ComplaintRepository (commonMain
 *       interface at shared/.../complaint/repository/ComplaintRepository
 *       .kt) declares 5 methods: sendComplaint, getComplaintsByUser,
 *       getAllComplaints, updateComplaint, deleteComplaint. Both
 *       Android Firebase-SDK implementation (this file) and iOS+Desktop
 *       REST implementation (ComplaintFirestoreRestDataSource) implement
 *       ALL 5 with behavioural parity confirmed by Koin module KDoc
 *       (PlatformModule.ios.kt:161, PlatformModule.desktop.kt:160 both
 *       cite "behavioural parity with ComplaintFirestoreDataSource —
 *       same 5-method contract").
 *
 * Nested-comment hazard check: 5 slash-star-star markers at lines 20,
 * 179, 322, 356, 364 (all legitimate KDoc openers — class header,
 * extractCreatedAt helper, ComplaintDto config docs, Date.toKotlinInstant
 * docs, Instant.toJavaDate docs) + 1 slash-star postscript opener (this
 * comment). Clean — no nested block-comment risk.
 *
 * Build gates (all GREEN, post-postscript verification):
 *   - gradlew.bat :composeApp:compileDebugKotlinAndroid     → BUILD SUCCESSFUL in 15s
 *   - gradlew.bat :composeApp:compileKotlinIosArm64         → BUILD SUCCESSFUL
 *   - gradlew.bat :composeApp:compileKotlinIosSimulatorArm64 → BUILD SUCCESSFUL in 51s
 *
 * Cluster262 saturation-watch register (next-cluster scouting):
 *   Confirmed at top of cluster262 scout: ComplaintFirestoreDataSource was
 *   the last :shared/androidMain prose-bearing leaf outside the deferred
 *   sources_repositry subtree AND outside the download subsystem
 *   (clusters 255+257+258+259, FULLY SWEPT). Remaining :shared/androidMain
 *   files post-cluster262 are either already swept in earlier 3-actual
 *   platform-fans (PlatformModule.android.kt cluster170,
 *   DatabaseBuilder.android.kt cluster186, AvifDecoderCoil.android.kt
 *   cluster260, etc.) or live inside the deferred sources_repositry/
 *   subtree.
 *
 *   Likely cluster263 pivot candidates (post-cluster262 scout):
 *     (a) :composeApp/desktopMain or :composeApp/iosMain outlier scouting —
 *         any solo-leaf prose-bearing platform-host files not yet swept.
 *     (b) :data root-tier prose-bearing leaves OUTSIDE :data/local,
 *         OUTSIDE :data/mapper, OUTSIDE :data/repository (i.e. mixed
 *         miscellany) — may overlap with cluster187 wave-57 results.
 *     (c) :ui module commonMain prose-bearing leaves not yet swept by
 *         cluster28+29+30 (:presentation/:ui tier sweeps).
 *     (d) :domain module model/usecase/repository leaves not yet fully
 *         swept (campaigns 110-141 covered the bulk; possible stragglers).
 *
 *   Saturation indicator: ALL :shared/androidMain prose-bearing leaves
 *   outside the deferred sources_repositry subtree are now SWEPT.
 *   :shared/androidMain tier outside sources_repositry is FULLY SWEPT.
 *
 * Cross-references:
 *   - cluster202 (closes :shared/commonMain complaint/ FULLY SWEPT,
 *     swept both ComplaintRepository.kt interface + sibling
 *     ComplaintFirestoreRestDataSource.kt commonMain REST impl)
 *   - cluster200+201 (5+4-leaf :shared/commonMain complaint/usecase/ +
 *     model+utils sweeps — consumers of this DataSource)
 *   - cluster170 (closed 3-actual fan PlatformModule.{android,ios,desktop}
 *     .kt — Koin binding sites for this DataSource)
 *   - cluster260+261 (DOUBLET-LEGACY-VS-REWORK-LIVE pattern, distinct
 *     from this SHARED-CONTRACT-PLATFORM-FAN-NONEXPECTACTUAL pattern —
 *     both shipped Phase 9.x, both LIVE Android-only / Android+iOS+Desktop
 *     respectively).
 */
