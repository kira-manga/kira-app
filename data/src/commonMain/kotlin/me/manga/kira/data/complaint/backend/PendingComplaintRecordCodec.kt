package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.manga.kira.platform.storage.InstallationValueResult
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.text.CharacterCodingException
import kotlin.time.Instant

/** Structural local-format outcomes, never physical absence, successful storage or remote authority. */
internal sealed interface PendingComplaintCodecResult<out T> {
    class Value<T>(
        val value: T,
    ) : PendingComplaintCodecResult<T> {
        override fun toString(): String = "PendingComplaintCodec.Value(redacted)"
    }

    data object Corrupt : PendingComplaintCodecResult<Nothing>

    data object TooLarge : PendingComplaintCodecResult<Nothing>
}

/** Closed local 19-scalar schema, NOT backend kcj-1 or a generic request/status parser. */
internal object PendingComplaintRecordCodec {
    const val MAX_BYTES: Int = PendingComplaintSlot.MAX_BYTES
    private const val FIELD_COUNT = 19

    fun encode(record: PendingComplaintRecord): PendingComplaintCodecResult<PendingComplaintSlot> {
        val bytes = canonical(record).toString().encodeToByteArray()
        if (bytes.size > MAX_BYTES) return PendingComplaintCodecResult.TooLarge
        return when (val slot = PendingComplaintSlot.checked(record.request.key, bytes)) {
            is InstallationValueResult.Valid -> PendingComplaintCodecResult.Value(slot.value)
            is InstallationValueResult.Invalid -> PendingComplaintCodecResult.Corrupt
        }
    }

    fun decode(slot: PendingComplaintSlot): PendingComplaintCodecResult<PendingComplaintRecord> =
        decode(
            slot.id,
            slot.bytes(),
        )

    fun decode(
        slotId: String,
        bytes: ByteArray,
    ): PendingComplaintCodecResult<PendingComplaintRecord> {
        if (bytes.size > MAX_BYTES) return PendingComplaintCodecResult.TooLarge
        val owned = bytes.copyOf()
        val record = parse(owned)?.let(::checkedRecord)
        return if (record != null &&
            slotId == record.request.key &&
            owned.contentEquals(canonical(record).toString().encodeToByteArray())
        ) {
            PendingComplaintCodecResult.Value(record)
        } else {
            PendingComplaintCodecResult.Corrupt
        }
    }

    private fun checkedRecord(fields: JsonObject): PendingComplaintRecord? {
        if (fields.keys != FIELD_NAMES) return null
        val schema = fields.int("schemaVersion")
        val binding = checkedBinding(fields)
        val request = checkedRequest(fields)
        val times = checkedTimes(fields)
        val state = PendingComplaintState.entries.firstOrNull { it.name == fields.string("state") }
        return when {
            schema == null -> null
            binding == null -> null
            request == null -> null
            times == null -> null
            state == null -> null
            else -> PendingComplaintRecord.restored(schema, binding, request, times, state)
        }
    }

    private fun checkedBinding(fields: JsonObject): PendingComplaintBinding? {
        val id = fields.string("installationId")
        val version = fields.long("credentialVersion")
        val generation = fields.long("localGeneration")
        val scope = fields.string("dataScopeId")
        return when {
            id == null -> null
            version == null -> null
            generation == null -> null
            scope == null -> null
            else -> PendingComplaintBinding.checked(id, version, generation, scope)
        }
    }

    private fun checkedRequest(fields: JsonObject): PendingComplaintRequest? {
        val operation = PendingComplaintOperation.entries.firstOrNull { it.name == fields.string("operation") }
        val target = fields.string("targetId")
        val parent = fields.string("parentId")
        val expected = fields.long("expectedVersion")
        val key = fields.string("idempotencyKey")
        val fingerprintVersion = fields.int("fingerprintVersion")
        val encodedFingerprint = fields.string("fingerprint")
        return when {
            operation == null -> null
            target == null -> null
            key == null -> null
            fingerprintVersion == null -> null
            encodedFingerprint == null -> null
            parent == null && fields["parentId"] != JsonNull -> null
            expected == null && fields["expectedVersion"] != JsonNull -> null
            else -> {
                val action = PendingComplaintAction.checked(operation, target, parent, expected)
                val fingerprint = PendingComplaintFingerprint.checked(fingerprintVersion, encodedFingerprint)
                if (action == null || fingerprint == null) {
                    null
                } else {
                    PendingComplaintRequest.checked(action, key, fingerprint)
                }
            }
        }
    }

    private fun checkedTimes(fields: JsonObject): PendingComplaintTimes? {
        val created = fields.instant("createdAt")
        val issued = fields.instant("sessionIssuedAt")
        val safeUntil = fields.instant("serverReceiptSafeUntil")
        return if (created == null || issued == null || safeUntil == null) {
            null
        } else {
            PendingComplaintTimes.checked(created, issued)?.takeIf { it.serverReceiptSafeUntil == safeUntil }
        }
    }

    private fun canonical(record: PendingComplaintRecord): JsonObject =
        buildJsonObject {
            put("schemaVersion", record.schemaVersion)
            put("installationId", record.binding.installationId)
            put("credentialVersion", record.binding.credentialVersion)
            put("localGeneration", record.binding.localGeneration)
            put("dataScopeId", record.binding.dataScopeId)
            put("operation", record.request.action.operation.name)
            put("targetId", record.request.action.targetId)
            put(
                "parentId",
                record.request.action.parentId
                    ?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("idempotencyKey", record.request.key)
            put("fingerprintVersion", record.request.fingerprint.version)
            put("fingerprint", record.request.fingerprint.encoded)
            put(
                "expectedVersion",
                record.request.action.expectedVersion
                    ?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("createdAtSeconds", record.times.createdAt.epochSeconds)
            put("createdAtNanos", record.times.createdAt.nanosecondsOfSecond)
            put("sessionIssuedAtSeconds", record.times.sessionIssuedAt.epochSeconds)
            put("sessionIssuedAtNanos", record.times.sessionIssuedAt.nanosecondsOfSecond)
            put("serverReceiptSafeUntilSeconds", record.times.serverReceiptSafeUntil.epochSeconds)
            put("serverReceiptSafeUntilNanos", record.times.serverReceiptSafeUntil.nanosecondsOfSecond)
            put("state", record.state.name)
        }

    private fun parse(bytes: ByteArray): JsonObject? =
        try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            if (scalarShape(text)) Json.parseToJsonElement(text) as? JsonObject else null
        } catch (_: CharacterCodingException) {
            null
        } catch (_: SerializationException) {
            null
        }

    // Bound shape before DOM parsing. Every admitted value is ASCII; escaping/nesting cannot hide keys or payloads.
    private fun scalarShape(text: String): Boolean {
        if (text.firstOrNull() != '{' || text.lastOrNull() != '}') return false
        var quoted = false
        var fields = 0
        val valid =
            (1 until text.lastIndex).all { index ->
                val character = text[index]
                when {
                    character !in ' '..'~' || character == '\\' -> false
                    character == '"' -> {
                        quoted = !quoted
                        true
                    }
                    !quoted && character in "{}[]" -> false
                    !quoted && character == ':' -> {
                        fields++
                        fields <= FIELD_COUNT
                    }
                    else -> true
                }
            }
        return valid && !quoted && fields == FIELD_COUNT
    }

    private val FIELD_NAMES =
        setOf(
            "schemaVersion",
            "installationId",
            "credentialVersion",
            "localGeneration",
            "dataScopeId",
            "operation",
            "targetId",
            "parentId",
            "idempotencyKey",
            "fingerprintVersion",
            "fingerprint",
            "expectedVersion",
            "createdAtSeconds",
            "createdAtNanos",
            "sessionIssuedAtSeconds",
            "sessionIssuedAtNanos",
            "serverReceiptSafeUntilSeconds",
            "serverReceiptSafeUntilNanos",
            "state",
        )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.longOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.intOrNull

private fun JsonObject.instant(prefix: String): Instant? {
    val seconds = long(prefix + "Seconds")
    val nanos = int(prefix + "Nanos")
    return if (seconds == null || nanos == null) null else pendingInstant(seconds, nanos)
}
