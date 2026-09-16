package me.manga.kira.platform.storage

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Closed identifier-free marker format; decoding a marker does not authorize deletion. */
object CredentialCleanupMarkerCodec {
    private const val FIELD_COUNT = 3

    /** Encodes only schema, expected generation and reason, including an explicit null generation. */
    fun encode(marker: CredentialCleanupMarker): InstallationCodecResult<InstallationEncodedPayload> =
        encodeInstallationObject(canonicalObject(marker), CredentialCleanupMarker.MAX_ENCODED_BYTES)

    /** Applies the raw 512-byte ceiling before strict decoding; malformed bytes are never Missing. */
    fun decode(bytes: ByteArray): InstallationCodecResult<CredentialCleanupMarker> {
        if (bytes.size > CredentialCleanupMarker.MAX_ENCODED_BYTES) return InstallationCodecResult.TooLarge
        val marker = parseInstallationObject(bytes, FIELD_COUNT)?.let(::checkedMarker)
        return if (marker != null && bytes.contentEquals(canonicalObject(marker).toString().encodeToByteArray())) {
            InstallationCodecResult.Value(marker)
        } else {
            InstallationCodecResult.Corrupt
        }
    }

    private fun checkedMarker(fields: JsonObject): CredentialCleanupMarker? {
        val schema = fields.installationInt("schemaVersion")
        val reasonName = fields.installationString("reason")
        val reason = CredentialCleanupReason.entries.firstOrNull { it.name == reasonName }
        val generation = fields.installationLong("expectedGeneration")
        return when {
            schema == null -> null
            reason == null -> null
            fields["expectedGeneration"] != JsonNull && generation == null -> null
            else -> CredentialCleanupMarker.checked(schema, generation, reason).codecValueOrNull()
        }
    }

    private fun canonicalObject(marker: CredentialCleanupMarker): JsonObject =
        buildJsonObject {
            put("schemaVersion", marker.schemaVersion)
            put("expectedGeneration", marker.expectedGeneration?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", marker.reason.name)
        }
}
