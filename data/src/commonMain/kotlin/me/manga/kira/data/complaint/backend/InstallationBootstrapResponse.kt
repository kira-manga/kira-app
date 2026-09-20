package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Structural decoding only. Only the fixed HTTPS exchange may supply bootstrap to the coordinator. */
internal class InstallationBootstrapResponse private constructor(
    val dataScopeId: String,
) {
    override fun toString(): String = "InstallationBootstrapResponse(redacted)"

    companion object {
        private const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
        private val SCOPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun decode(text: String): InstallationEnrollmentResult<InstallationBootstrapResponse> =
            try {
                val scope = BootstrapObjectReader(text).read()
                if (scope == LIVE_SCOPE || SCOPE.matches(scope)) {
                    InstallationEnrollmentResult.Ready(InstallationBootstrapResponse(scope))
                } else {
                    InstallationEnrollmentResult.Failed(ComplaintSessionFailure.RESPONSE)
                }
            } catch (_: BootstrapJsonFailure) {
                InstallationEnrollmentResult.Failed(ComplaintSessionFailure.RESPONSE)
            } catch (_: SerializationException) {
                InstallationEnrollmentResult.Failed(ComplaintSessionFailure.RESPONSE)
            }
    }
}

/** Two closed fields; decoded duplicate keys are rejected before any map could collapse them. */
private class BootstrapObjectReader(
    private val text: String,
) {
    private var position = 0

    fun read(): String {
        expect('{')
        val first = string()
        expect(':')
        val scope =
            when (first) {
                "dataScopeId" ->
                    string().also {
                        second("contractVersion")
                        expect('1')
                    }
                "contractVersion" -> {
                    expect('1')
                    second("dataScopeId")
                    string()
                }
                else -> invalidBootstrapJson()
            }
        expect('}')
        whitespace()
        if (position != text.length) invalidBootstrapJson()
        return scope
    }

    private fun second(key: String) {
        expect(',')
        if (string() != key) invalidBootstrapJson()
        expect(':')
    }

    private fun string(): String {
        whitespace()
        val start = position
        expect('"')
        while (position < text.length) {
            when (text[position++]) {
                '\\' -> {
                    if (position == text.length) invalidBootstrapJson()
                    position++
                }
                '"' -> {
                    val value = Json.parseToJsonElement(text.substring(start, position)) as? JsonPrimitive
                    return value?.takeIf { it.isString }?.content ?: invalidBootstrapJson()
                }
                in '\u0000'..'\u001f' -> invalidBootstrapJson()
            }
        }
        invalidBootstrapJson()
    }

    private fun expect(character: Char) {
        whitespace()
        if (text.getOrNull(position) != character) invalidBootstrapJson()
        position++
    }

    private fun whitespace() {
        while (position < text.length && text[position] in " \t\r\n") position++
    }
}

private class BootstrapJsonFailure : Exception()

private fun invalidBootstrapJson(): Nothing = throw BootstrapJsonFailure()
