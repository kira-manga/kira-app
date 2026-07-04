package me.manga.yamiapk.presentation.features.complaint.repository

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.tasks.await
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType
import me.manga.yamiapk.presentation.features.complaint.utils.toComplaintStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComplaintFirestoreDataSource @Inject constructor(
    firestore: FirebaseFirestore,
) : ComplaintRepository {

    private val complaintsCol = firestore.collection("complaints_v2")

    override suspend fun sendComplaint(complaint: Complaint): String {
        val dto = ComplaintDto.fromDomain(complaint)
        return try {
            Log.d("ComplaintSend", "Sending complaint for user: ${dto.userId}, subject: ${dto.subject}")
            val ref = complaintsCol.add(dto).await()
            Log.d("ComplaintSend", "Complaint sent successfully with ID: ${ref.id}")
            ref.id
        } catch (e: Exception) {
            Log.e("ComplaintSend", "Error sending complaint: ${e.message}", e)
            throw e
        }
    }

    override suspend fun getComplaintsByUser(userId: String): List<Complaint> {
        val trimmedUserId = userId.trim()
        Log.d("ComplaintFetchUser", "Fetching complaints for userId: $trimmedUserId")

        val formatDocs = complaintsCol.where(
            Filter.or(
                Filter.equalTo("a", trimmedUserId),
                Filter.equalTo("userId", trimmedUserId),
            )
        )
            .get()
            .await()
            .documents

        Log.d("ComplaintFetchUser", "Fetched ${formatDocs.size} complaint documents")

        return formatDocs.mapNotNull { doc ->
            try {
                // Support both new and legacy single-letter fields
                val uid = doc.getString("userId") ?: doc.getString("a") ?: "0"
                val type = ComplaintType.valueOf(
                    doc.getString("type") ?: doc.getString("b") ?: ComplaintType.CUSTOM.name
                )
                val subject = doc.getString("subject") ?: doc.getString("c") ?: "0"
                val body = doc.getString("body") ?: doc.getString("d") ?: "0"

                // Legacy status is in "f" (per screenshot), new status in "status"
                val status = doc.getString("status")?.toComplaintStatus()
                    ?: doc.getString("f")?.toComplaintStatus()
                    ?: doc.getString("e")?.toComplaintStatus()
                    ?: ComplaintStatus.OPEN

                // Legacy metadata key is "g" (per screenshot), new key is "metadata"
                val metadata = (doc.get("metadata") ?: doc.get("g")) as? Map<String, Any> ?: emptyMap()

                Log.d("ComplaintFetchUser", "Parsed complaint: id=${doc.id}, status=$status")

                Complaint(
                    id = doc.id,
                    userId = uid,
                    type = type,
                    subject = subject,
                    body = body,
                    status = status,
                    metadata = metadata.mapValues { it.value.toString() },
                    createdAt = doc.extractCreatedAt()
                )
            } catch (e: Exception) {
                Log.e("ComplaintFetchUser", "Error parsing complaint doc: ${doc.id}, ${e.message}", e)
                null
            }
        }.sortedBy { it.createdAt }.reversed()
    }

    override suspend fun getAllComplaints(): List<Complaint> {
        return try {
            val snapshot = complaintsCol.get().await()
            snapshot.documents.mapNotNull { doc ->
                Log.i("ComplaintParseDebug_data", doc.data.toString())
                Log.i("ComplaintParseDebug_toString", doc.toString())
                try {
                    val userId = doc.getString("userId") ?: doc.getString("a") ?: "0"
                    val type = ComplaintType.valueOf(
                        doc.getString("type") ?: doc.getString("b") ?: ComplaintType.CUSTOM.name
                    )
                    val subject = doc.getString("subject") ?: doc.getString("c") ?: "0"
                    val body = doc.getString("body") ?: doc.getString("d") ?: "0"
                    val status = doc.getString("status")?.toComplaintStatus()
                        ?: doc.getString("f")?.toComplaintStatus()
                        ?: doc.getString("e")?.toComplaintStatus()
                        ?: ComplaintStatus.OPEN

                    val metadata = (doc.get("metadata") ?: doc.get("g")) as? Map<String, Any> ?: emptyMap()

                    Complaint(
                        id = doc.id,
                        userId = userId,
                        type = type,
                        subject = subject,
                        body = body,
                        status = status,
                        metadata = metadata.mapValues { it.value.toString() },
                        createdAt = doc.extractCreatedAt()
                    )
                } catch (e: Exception) {
                    Log.e("ComplaintParse", "failed to parse doc ${doc.id}: ${e.message}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("ComplaintFetchAll", "failed to fetch all complaints: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun updateComplaint(complaint: Complaint) {
        val dto = ComplaintDto.fromDomain(complaint)
        try {
            complaintsCol.document(complaint.id)
                .set(dto)
                .await()
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun deleteComplaint(complaintId: String) {
        try {
            complaintsCol.document(complaintId)
                .delete()
                .await()
        } catch (e: Exception) {
            throw e
        }
    }

    private fun DocumentSnapshot.extractCreatedAt(logTag: String = "CreatedAtParser"): Date {
        val docId = id

        // try multiple legacy keys in addition to canonical "createdAt"
        val raw = get("createdAt")
            ?: get("e")        // legacy screenshot shows createdAt stored at "e"
            ?: get("created_at")
            ?: get("timestamp")
            ?: get("time")
            ?: null

        // pretty date formatter for logs (UTC ISO)
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        Log.d(logTag, "doc=$docId createdAt raw=${raw?.toString() ?: "null"} type=${raw?.javaClass?.name ?: "null"}")

        // 1) direct Date (if stored as java.util.Date)
        getDate("createdAt")?.let {
            Log.d(logTag, "doc=$docId branch=getDate(createdAt) -> ${isoFmt.format(it)}")
            return it
        }
        // Also try legacy key "e" as Date
        (get("e") as? Date)?.let {
            Log.d(logTag, "doc=$docId branch=getDate(e) -> ${isoFmt.format(it)}")
            return it
        }

        // 2) Firestore Timestamp -> Date
        try {
            getTimestamp("createdAt")?.toDate()?.let {
                Log.d(logTag, "doc=$docId branch=getTimestamp(createdAt) -> ${isoFmt.format(it)}")
                return it
            }
            getTimestamp("e")?.toDate()?.let {
                Log.d(logTag, "doc=$docId branch=getTimestamp(e) -> ${isoFmt.format(it)}")
                return it
            }
        } catch (t: Throwable) {
            Log.w(logTag, "doc=$docId getTimestamp() failed: ${t.message}")
        }

        // 3) raw value handling (Number / Map / String)
        when (raw) {
            is Number -> {
                val n = raw.toLong()
                val date = if (n > 1_000_000_000_000L) Date(n) else Date(n * 1000)
                val kind = if (n > 1_000_000_000_000L) "millis" else "seconds"
                Log.d(logTag, "doc=$docId branch=Number ($kind) n=$n -> ${isoFmt.format(date)}")
                return date
            }

            is Map<*, *> -> {
                Log.d(logTag, "doc=$docId branch=Map keys=${raw.keys}")

                val seconds =
                    (raw["seconds"] as? Number)?.toLong()
                        ?: (raw["_seconds"] as? Number)?.toLong()
                val nanos =
                    (raw["nanoseconds"] as? Number)?.toInt()
                        ?: (raw["_nanoseconds"] as? Number)?.toInt() ?: 0

                if (seconds != null) {
                    val date = Date(seconds * 1000 + nanos / 1_000_000)
                    Log.d(logTag, "doc=$docId parsed map -> seconds=$seconds nanos=$nanos -> ${isoFmt.format(date)}")
                    return date
                }

                val nested = raw["time"] as? Map<*, *>
                if (nested != null) {
                    val s = (nested["seconds"] as? Number)?.toLong()
                        ?: (nested["_seconds"] as? Number)?.toLong()
                    val ns = (nested["nanoseconds"] as? Number)?.toInt()
                        ?: (nested["_nanoseconds"] as? Number)?.toInt() ?: 0
                    if (s != null) {
                        val date = Date(s * 1000 + ns / 1_000_000)
                        Log.d(logTag, "doc=$docId parsed nested time -> ${isoFmt.format(date)}")
                        return date
                    }
                }
            }

            is String -> {
                // try parse ISO8601-ish strings
                try {
                    val parsed = try {
                        isoFmt.parse(raw)
                    } catch (_: Exception) {
                        null
                    }
                    if (parsed != null) {
                        Log.d(logTag, "doc=$docId branch=String(ISO) -> ${isoFmt.format(parsed)}")
                        return parsed
                    }

                    // try a more human-readable fallback (e.g. "1 November 2025 at 15:21:32 UTC+2")
                    // We'll try a few common patterns
                    val altFormats = listOf(
                        "d MMMM yyyy 'at' HH:mm:ss 'UTC'XXX",
                        "d MMMM yyyy 'at' HH:mm:ss 'UTC'Z",
                        "d MMMM yyyy 'at' HH:mm:ss",
                        "yyyy-MM-dd HH:mm:ss",
                        "yyyy/MM/dd HH:mm:ss"
                    )
                    for (fmt in altFormats) {
                        try {
                            val sdf = SimpleDateFormat(fmt, Locale.US)
                            val p = sdf.parse(raw)
                            if (p != null) {
                                Log.d(logTag, "doc=$docId branch=String(alt:$fmt) -> ${isoFmt.format(p)}")
                                return p
                            }
                        } catch (_: Exception) { /* continue */ }
                    }
                } catch (t: Throwable) {
                    Log.w(logTag, "doc=$docId string parse failed: ${t.message}")
                }
            }

            null -> {
                Log.d(logTag, "doc=$docId createdAt is null")
            }

            else -> {
                Log.d(logTag, "doc=$docId createdAt unknown raw class: ${raw.javaClass.name}")
            }
        }

        // fallback: current time
        val fallback = Date()
        Log.w(logTag, "doc=$docId branch=fallback -> ${isoFmt.format(fallback)}")
        return fallback
    }

    private data class ComplaintDto(
        val userId: String,
        val type: String,
        val subject: String,
        val body: String,
        @ServerTimestamp val createdAt: Date? = null,
        val status: String,
        val metadata: Map<String, Any>? = null
    ) {
        companion object {
            fun fromDomain(domain: Complaint): ComplaintDto = ComplaintDto(
                userId = domain.userId,
                type = domain.type.name,
                subject = domain.subject,
                body = domain.body,
                status = domain.status.name,
                metadata = domain.metadata,
                createdAt = domain.createdAt
            )
        }
    }
}
