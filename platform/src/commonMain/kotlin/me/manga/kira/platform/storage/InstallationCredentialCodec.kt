package me.manga.kira.platform.storage

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.text.CharacterCodingException

/** Local codec outcomes only: no byte payload, including an empty one, proves physical absence. */
sealed interface InstallationCodecResult<out T> {
    class Value<T>(
        val value: T,
    ) : InstallationCodecResult<T> {
        override fun toString(): String = "InstallationCodecResult.Value(redacted)"
    }

    data object Corrupt : InstallationCodecResult<Nothing>

    data object TooLarge : InstallationCodecResult<Nothing>
}

/** Owned canonical plaintext, not an encrypted envelope or proof of durable storage. */
class InstallationEncodedPayload internal constructor(
    bytes: ByteArray,
) {
    private val encoded = bytes.copyOf()

    /** Returns a caller-owned copy; mutating it cannot alter this payload or subsequent encodes. */
    fun copyBytes(): ByteArray = encoded.copyOf()

    override fun toString(): String = "InstallationEncodedPayload(redacted)"
}

/** Closed schema-1 local format, unrelated to backend canonicalization or bootstrap authority. */
object InstallationCredentialCodec {
    const val MAX_ENCODED_BYTES: Int = 2048
    private const val FIELD_COUNT = 9

    /** Encodes all nine checked fields in fixed order, including an explicit null deletion key. */
    fun encode(record: InstallationCredentialRecord): InstallationCodecResult<InstallationEncodedPayload> =
        encodeInstallationObject(canonicalObject(record), MAX_ENCODED_BYTES)

    /** Rejects oversized bytes before copying/decoding and rejects, rather than repairs, aliases. */
    fun decode(bytes: ByteArray): InstallationCodecResult<InstallationCredentialRecord> {
        if (bytes.size > MAX_ENCODED_BYTES) return InstallationCodecResult.TooLarge
        val record = parseInstallationObject(bytes, FIELD_COUNT)?.let(::checkedRecord)
        return if (record != null && bytes.contentEquals(canonicalObject(record).toString().encodeToByteArray())) {
            InstallationCodecResult.Value(record)
        } else {
            InstallationCodecResult.Corrupt
        }
    }

    private fun checkedRecord(fields: JsonObject): InstallationCredentialRecord? {
        val schema = fields.installationInt("schemaVersion")
        val material = checkedMaterial(fields)
        val version = fields.installationLong("credentialVersion")
        val generation = fields.installationLong("localGeneration")
        val stateName = fields.installationString("state")
        val state = InstallationCredentialState.entries.firstOrNull { it.name == stateName }
        val key = fields.installationString("pendingDeletionKey")
        return when {
            schema == null -> null
            material == null -> null
            version == null -> null
            generation == null -> null
            state == null -> null
            fields["pendingDeletionKey"] != JsonNull && key == null -> null
            else ->
                InstallationCredentialRecord
                    .checked(schema, material, version, generation, state, key)
                    .codecValueOrNull()
        }
    }

    private fun checkedMaterial(fields: JsonObject): InstallationCredentialMaterial? {
        val id = fields.installationString("installationId")
        val secret = fields.installationString("secret")
        val platform = fields.installationString("platform")
        val scope = fields.installationString("dataScopeId")
        return when {
            id == null -> null
            secret == null -> null
            platform == null -> null
            scope == null -> null
            else -> InstallationCredentialMaterial.checked(id, secret, platform, scope).codecValueOrNull()
        }
    }

    private fun canonicalObject(record: InstallationCredentialRecord): JsonObject =
        buildJsonObject {
            put("schemaVersion", record.schemaVersion)
            put("installationId", record.material.installationId)
            put("secret", record.material.secret)
            put("credentialVersion", record.credentialVersion)
            put("localGeneration", record.localGeneration)
            put("platform", record.material.platform.name)
            put("dataScopeId", record.material.dataScopeId)
            put("state", record.state.name)
            put("pendingDeletionKey", record.pendingDeletionKey?.let(::JsonPrimitive) ?: JsonNull)
        }
}

// Shared only by the two closed installation codecs; no semantic pending-action parsing lives here.
internal fun encodeInstallationObject(
    fields: JsonObject,
    byteLimit: Int,
): InstallationCodecResult<InstallationEncodedPayload> {
    val bytes = fields.toString().encodeToByteArray()
    return if (bytes.size > byteLimit) {
        InstallationCodecResult.TooLarge
    } else {
        InstallationCodecResult.Value(InstallationEncodedPayload(bytes))
    }
}

// Callers enforce their raw byte ceiling first. No recursive JSON is admitted to the DOM parser.
internal fun parseInstallationObject(
    bytes: ByteArray,
    fieldCount: Int,
): JsonObject? =
    try {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        if (hasInstallationScalarShape(text, fieldCount)) Json.parseToJsonElement(text) as? JsonObject else null
    } catch (_: CharacterCodingException) {
        null
    } catch (_: SerializationException) {
        null
    }

private fun hasInstallationScalarShape(
    text: String,
    fieldCount: Int,
): Boolean {
    if (text.firstOrNull() != '{' || text.lastOrNull() != '}') return false
    var quoted = false
    var fields = 0
    val scalarShape =
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
                    fields <= fieldCount
                }
                else -> true
            }
        }
    return scalarShape && !quoted && fields == fieldCount
}

internal fun JsonObject.installationString(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

internal fun JsonObject.installationInt(key: String): Int? =
    (this[key] as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.intOrNull

internal fun JsonObject.installationLong(key: String): Long? =
    (this[key] as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.longOrNull

internal fun <T> InstallationValueResult<T>.codecValueOrNull(): T? =
    when (this) {
        is InstallationValueResult.Valid -> value
        is InstallationValueResult.Invalid -> null
    }
