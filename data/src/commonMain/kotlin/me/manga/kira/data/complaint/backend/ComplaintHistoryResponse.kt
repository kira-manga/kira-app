package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistoryPlatform
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintNotice
import me.manga.kira.domain.model.complaint.ComplaintOwnerFields
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import kotlin.time.Instant

internal class ComplaintHistoryPage(
    val notices: List<ComplaintNotice>,
    val items: List<ComplaintOwnerRow>,
    val nextCursor: String?,
    val mismatches: Set<ComplaintHistoryMismatch> = emptySet(),
) {
    override fun toString(): String = "ComplaintHistoryPage(redacted)"
}

internal object ComplaintHistoryResponse {
    fun decode(text: String): AppResult<ComplaintHistoryPage> = try {
        val root = ComplaintHistoryJson(text).read()
        if (root.keys != setOf("notices", "items", "nextCursor")) invalidHistory()
        val notices = array(root, "notices").map { notice(it as? JsonObject ?: invalidHistory()) }
        val items = array(root, "items").map { owner(it as? JsonObject ?: invalidHistory()) }
        val cursor = root.nullableString("nextCursor")
        if (cursor != null && !validHistoryCursor(cursor)) invalidHistory()
        if (notices.size > MAX_NOTICES || notices.map { it.noticeKey }.toSet().size != notices.size) invalidHistory()
        val ids = notices.map { it.id } + items.map { it.id }
        if (ids.toSet().size != ids.size) invalidHistory()
        AppResult.Success(ComplaintHistoryPage(notices, items, cursor, mismatches(notices, items)))
    } catch (_: InvalidComplaintHistory) {
        malformedHistory()
    } catch (_: SerializationException) {
        malformedHistory()
    } catch (_: IllegalArgumentException) {
        malformedHistory()
    }

    private fun array(root: JsonObject, key: String): JsonArray = root[key] as? JsonArray ?: invalidHistory()

    private fun notice(row: JsonObject): ComplaintNotice {
        if (row.keys != NOTICE_FIELDS || row.string("kind") != "NOTICE" || row.string("status") != "PINNED") {
            invalidHistory()
        }
        val id = row.string("id").also { if (!UUID.matches(it)) invalidHistory() }
        val created = instant(row.string("createdAt"))
        val updated = instant(row.string("updatedAt"))
        if (updated < created) invalidHistory()
        return ComplaintNotice(id, noticeKey(row.string("noticeKey")), created, updated, version(row))
    }

    private fun owner(row: JsonObject): ComplaintOwnerRow {
        // Future kinds remain inside the bounded, closed common envelope, not an open JSON object.
        if (row.keys.any { it !in ITEM_FIELDS }) invalidHistory()
        val kind = token(row.string("kind"))
        if (kind != "REPORT" && kind != "REPLY") {
            if (kind == "NOTICE") invalidHistory() // Known NOTICE belongs to the notices array only.
            return unknown(row, kind)
        }
        val noticeReply = "noticeKey" in row
        if (row.keys != if (noticeReply) OWNER_FIELDS + "noticeKey" else OWNER_FIELDS) invalidHistory()
        val fields = ownerFields(row)
        val type = type(row.string("type"))
        val replyTo = row.nullableString("replyToId")?.also { if (!UUID.matches(it)) invalidHistory() }
        return when {
            kind == "REPORT" && !noticeReply && replyTo == null ->
                ComplaintOwnerRow.Report(fields, type, subject(row))
            kind == "REPLY" && !noticeReply && replyTo != null ->
                ComplaintOwnerRow.Reply(fields, type, subject(row), replyTo)
            kind == "REPLY" && noticeReply && replyTo != null &&
                (type as? ComplaintHistoryType.Known)?.value == ComplaintType.CUSTOM -> {
                // CUSTOM/null are structural constants, not open enum positions in this matrix.
                if (row.nullableString("subject") != null) invalidHistory()
                ComplaintOwnerRow.NoticeReply(fields, noticeKey(row.string("noticeKey")), replyTo)
            }
            else -> invalidHistory()
        }
    }

    private fun unknown(row: JsonObject, kind: String): UnknownComplaintItem {
        if (!row.keys.containsAll(COMMON_FIELDS) || row.values.any { it !is JsonPrimitive }) invalidHistory()
        val id = row.string("id").also { if (!UUID.matches(it)) invalidHistory() }
        val created = instant(row.string("createdAt"))
        val updated = instant(row.string("updatedAt"))
        if (updated < created) invalidHistory()
        return UnknownComplaintItem(id, kind, created, updated)
    }

    private fun type(value: String): ComplaintHistoryType {
        val known = ComplaintType.entries.singleOrNull { it.name == token(value) }
        return known?.let { ComplaintHistoryType.Known(it) } ?: ComplaintHistoryType.Unrecognized
    }

    private fun status(value: String): ComplaintHistoryStatus {
        val known = ComplaintStatus.entries.singleOrNull { it.name == token(value) }
        return known?.let { ComplaintHistoryStatus.Known(it) } ?: ComplaintHistoryStatus.Unrecognized
    }

    private fun token(value: String): String = value.also {
        if (it.length !in 1..64 || it.any { char -> char !in '!'..'~' }) invalidHistory()
    }

    private fun mismatches(
        notices: List<ComplaintNotice>,
        items: List<ComplaintOwnerRow>,
    ): Set<ComplaintHistoryMismatch> = buildSet {
        // There is deliberately no assumed live localization catalog in this unselected slice.
        if (notices.isNotEmpty()) add(ComplaintHistoryMismatch.NOTICE_KEY)
        for (item in items) {
            when (item) {
                is UnknownComplaintItem -> add(ComplaintHistoryMismatch.KIND)
                is ComplaintOwnerRow.Content -> {
                    if (item.fields.status is ComplaintHistoryStatus.Unrecognized) add(ComplaintHistoryMismatch.STATUS)
                    if (item.type is ComplaintHistoryType.Unrecognized) add(ComplaintHistoryMismatch.TYPE)
                    if (item is ComplaintOwnerRow.NoticeReply) add(ComplaintHistoryMismatch.NOTICE_KEY)
                }
            }
        }
    }

    private fun ownerFields(row: JsonObject): ComplaintOwnerFields {
        val id = row.string("id").also { if (!UUID_V4.matches(it)) invalidHistory() }
        val version = version(row)
        val tag = row.string("actionTag")
        if (tag != "\"complaint-$id-v$version\"") invalidHistory()
        val status = status(row.string("status"))
        val created = instant(row.string("createdAt"))
        val updated = instant(row.string("updatedAt"))
        if (updated < created) invalidHistory()
        val closure = row.nullableString("closureReason")?.also { boundedText(it, 1, 500, 2_000) }
        if (status is ComplaintHistoryStatus.Known &&
            (status.value == ComplaintStatus.CLOSED) != (closure != null)) invalidHistory()
        return ComplaintOwnerFields(
            id, row.string("body").also { boundedText(it, 1, 1_000, 4_000) }, status, created, updated,
            version, tag, diagnostic(row, "appVersion", 64, 256),
            ComplaintHistoryPlatform.entries.singleOrNull { it.name == row.string("platform") } ?: invalidHistory(),
            diagnostic(row, "osVersion", 128, 512), diagnostic(row, "manufacturer", 128, 512),
            diagnostic(row, "deviceModel", 128, 512), closure,
        )
    }

    private fun diagnostic(row: JsonObject, key: String, points: Int, bytes: Int): String? =
        row.nullableString(key)?.also { boundedText(it, 0, points, bytes) }

    private fun subject(row: JsonObject): String = row.string("subject").also { boundedText(it, 1, 200, 800) }

    private fun version(row: JsonObject): Long = row.number("version").also { if (it < 1) invalidHistory() }

    private fun noticeKey(value: String): String = value.also { if (!NOTICE_KEY.matches(it)) invalidHistory() }

    private fun instant(value: String): Instant {
        if (!UTC.matches(value)) invalidHistory()
        return Instant.parse(value)
    }

    private const val MAX_NOTICES = 16
    private val NOTICE_KEY = Regex("[a-z0-9._-]{1,96}")
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val UTC = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z")
    private val COMMON_FIELDS = setOf("id", "kind", "createdAt", "updatedAt")
    private val NOTICE_FIELDS = setOf("id", "kind", "noticeKey", "status", "createdAt", "updatedAt", "version")
    private val OWNER_FIELDS = setOf(
        "id", "kind", "type", "subject", "body", "status", "createdAt", "updatedAt", "version", "actionTag",
        "appVersion", "platform", "osVersion", "manufacturer", "deviceModel", "closureReason", "replyToId",
    )
    private val ITEM_FIELDS = OWNER_FIELDS + NOTICE_FIELDS
}

/** Opaque envelope only; the backend, not the app, authenticates the payload/MAC. */
internal fun validHistoryCursor(value: String): Boolean =
    value.length <= 2_048 && Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matches(value)

internal fun malformedHistory(): AppResult.Failure = AppResult.Failure(AppError.Network.Serialization())

/** Reject malformed surrogate/control sequences and non-normalized server prose, never repair it. */
internal fun boundedText(value: String, minimum: Int, maximum: Int, bytes: Int) {
    if (value != value.trim() || value.encodeToByteArray().size > bytes) invalidHistory()
    var position = 0
    var points = 0
    while (position < value.length) {
        val char = value[position++]
        if (char < ' ' && char != '\t' && char != '\n' || char in '\u007f'..'\u009f') invalidHistory()
        if (char.isHighSurrogate()) {
            if (position == value.length || !value[position++].isLowSurrogate()) invalidHistory()
        } else if (char.isLowSurrogate()) {
            invalidHistory()
        }
        points++
    }
    if (points !in minimum..maximum) invalidHistory()
}
