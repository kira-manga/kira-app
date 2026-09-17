package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.platform.storage.InstallationCredentialRecord
import kotlin.time.Instant

/** Content-free failures; none authorizes recreation, credential rewriting, pending deletion or replay. */
internal enum class ComplaintSessionFailure {
    CLOSED,
    TRANSPORT,
    TIMEOUT,
    ORIGIN,
    CONTRACT,
    HEADERS,
    MEDIA,
    ENCODING,
    TOO_LARGE,
    LENGTH,
    UTF8,
    RESPONSE,
    INVALIDATED,
    EXPIRED,
}

internal sealed interface ComplaintSessionResult {
    class Ready(
        val session: ComplaintSessionResponse,
    ) : ComplaintSessionResult {
        override fun toString(): String = "ComplaintSession.Ready(redacted)"
    }

    data class Failed(
        val reason: ComplaintSessionFailure,
    ) : ComplaintSessionResult

    /** A verified problem fact is not lifecycle or replacement authority; the coordinator must recheck its binding. */
    data class HttpFailure(
        val status: Int,
        val problem: ComplaintSessionProblem? = null,
    ) : ComplaintSessionResult

    class LocalFailure(
        val outcome: Outcome<Nothing>,
    ) : ComplaintSessionResult {
        override fun toString(): String = "ComplaintSession.LocalFailure(redacted)"
    }
}

/** The only session problem interpreted here, from the strict bounded ApiError reader. */
internal enum class ComplaintSessionProblem { INSTALLATION_NOT_FOUND, }

/**
 * Closed response metadata, memory-only. Parsing does not verify a JWT signature or authorize dispatch.
 * Only the dedicated transport supplies wire bytes; the manager separately fences freshness/local state.
 */
internal class ComplaintSessionResponse private constructor(
    private val accessToken: String,
    val binding: PendingComplaintBinding,
    val issuedAt: Instant,
) {
    val expiresInSeconds: Long get() = EXPIRES_IN_SECONDS

    internal fun authorizationValue(): String = BEARER_PREFIX + accessToken

    override fun toString(): String = "ComplaintSessionResponse(redacted)"

    companion object {
        const val EXPIRES_IN_SECONDS = 900L
        private const val MAX_AUTHORIZATION_BYTES = 4 * 1_024
        private const val BEARER_PREFIX = "Bearer "
        private val COMPACT_TOKEN = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val UTC_INSTANT =
            Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z")

        fun decode(
            text: String,
            expected: InstallationCredentialRecord,
        ): ComplaintSessionResult =
            try {
                decodeFields(SessionObjectReader(text).read(), expected)
            } catch (_: SessionJsonFailure) {
                ComplaintSessionResult.Failed(ComplaintSessionFailure.RESPONSE)
            } catch (_: SerializationException) {
                ComplaintSessionResult.Failed(ComplaintSessionFailure.RESPONSE)
            }

        private fun decodeFields(
            fields: Map<String, String>,
            expected: InstallationCredentialRecord,
        ): ComplaintSessionResult {
            if (fields.keys != SESSION_FIELDS) return ComplaintSessionResult.Failed(ComplaintSessionFailure.RESPONSE)
            val binding = binding(fields, expected)
            val token = fields.getValue("accessToken")
            return when {
                binding == null -> ComplaintSessionResult.Failed(ComplaintSessionFailure.INVALIDATED)
                fields.getValue("tokenType") != "Bearer" ||
                    !validToken(token) ||
                    fields.getValue("expiresInSeconds").toLongOrNull() != EXPIRES_IN_SECONDS ->
                    ComplaintSessionResult.Failed(ComplaintSessionFailure.RESPONSE)
                else -> {
                    val issuedAt = issuedAt(fields.getValue("issuedAt"))
                    if (issuedAt == null) {
                        ComplaintSessionResult.Failed(ComplaintSessionFailure.RESPONSE)
                    } else {
                        ComplaintSessionResult.Ready(ComplaintSessionResponse(token, binding, issuedAt))
                    }
                }
            }
        }

        private fun binding(
            fields: Map<String, String>,
            expected: InstallationCredentialRecord,
        ): PendingComplaintBinding? {
            val version = fields.getValue("credentialVersion").toLongOrNull() ?: return null
            return PendingComplaintBinding
                .checked(
                    fields.getValue("installationId"),
                    version,
                    expected.localGeneration,
                    fields.getValue("dataScopeId"),
                )?.takeIf { it.matches(expected) }
        }

        private fun validToken(token: String): Boolean =
            token.length + BEARER_PREFIX.length <= MAX_AUTHORIZATION_BYTES && COMPACT_TOKEN.matches(token)

        private fun issuedAt(value: String): Instant? {
            if (!UTC_INSTANT.matches(value)) return null
            return try {
                Instant.parse(value)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** Narrow seven-field flat-object scanner. Decoded duplicate keys are rejected before any object map collapse. */
private class SessionObjectReader(
    private val text: String,
) {
    private var position = 0

    fun read(): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        expect('{')
        if (!take('}')) {
            do {
                if (fields.size == SESSION_FIELDS.size) invalidSessionJson()
                val key = string()
                if (key !in SESSION_FIELDS || key in fields) invalidSessionJson()
                expect(':')
                fields[key] = if (key in SESSION_STRING_FIELDS) string() else integer()
                if (take('}')) break
                expect(',')
            } while (true)
        }
        whitespace()
        if (position != text.length) invalidSessionJson()
        return fields
    }

    private fun string(): String {
        whitespace()
        val start = position
        expect('"')
        while (position < text.length) {
            when (text[position++]) {
                '\\' -> {
                    if (position == text.length) invalidSessionJson()
                    position++
                }
                '"' -> {
                    val value = Json.parseToJsonElement(text.substring(start, position)) as? JsonPrimitive
                    return value?.takeIf { it.isString }?.content ?: invalidSessionJson()
                }
                in '\u0000'..'\u001f' -> invalidSessionJson()
            }
        }
        invalidSessionJson()
    }

    private fun integer(): String {
        whitespace()
        val start = position
        while (position < text.length && text[position] in '0'..'9') position++
        val value = text.substring(start, position)
        val number = value.toLongOrNull() ?: invalidSessionJson()
        if (value != number.toString()) invalidSessionJson()
        return value
    }

    private fun expect(character: Char) {
        if (!take(character)) invalidSessionJson()
    }

    private fun take(character: Char): Boolean {
        whitespace()
        if (text.getOrNull(position) != character) return false
        position++
        return true
    }

    private fun whitespace() {
        while (position < text.length && text[position] in " \t\r\n") position++
    }
}

private class SessionJsonFailure : Exception()

private fun invalidSessionJson(): Nothing = throw SessionJsonFailure()

private val SESSION_STRING_FIELDS = setOf("installationId", "accessToken", "tokenType", "dataScopeId", "issuedAt")
private val SESSION_FIELDS = SESSION_STRING_FIELDS + setOf("credentialVersion", "expiresInSeconds")
