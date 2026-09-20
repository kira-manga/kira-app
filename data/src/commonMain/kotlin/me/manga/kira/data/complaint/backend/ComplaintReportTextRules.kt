package me.manga.kira.data.complaint.backend

/** Frozen normalized field bounds: Unicode scalar counts and strict UTF-8 bytes, not UTF-16 length. */
@Suppress("MagicNumber")
internal enum class ComplaintReportField(
    val minimum: Int,
    val maximum: Int,
    val maximumBytes: Int,
) {
    SUBJECT(1, 200, 800),
    BODY(5, 500, 2_000),
    APP_VERSION(0, 64, 256),
    OS_VERSION(0, 128, 512),
    MANUFACTURER(0, 128, 512),
    DEVICE_MODEL(0, 128, 512),
}

internal enum class ComplaintReportRejection {
    REQUIRED,
    TOO_SHORT,
    TOO_LONG,
    FORBIDDEN_CONTROL,
    MALFORMED_UNICODE,
    NORMALIZATION_MISMATCH,
}

internal class ComplaintReportTextRejected(
    val field: ComplaintReportField,
    val reason: ComplaintReportRejection,
) : RuntimeException("Complaint report text rejected.")

/** No platform trim/normalization tables and no malformed-surrogate replacement during encoding. */
internal object ComplaintReportTextRules {
    // Bounds direct-call scratch work. The HTTP owner must still enforce its aggregate raw 16KiB body cap first.
    private const val MAX_INPUT_CODE_UNITS = 16_384

    fun normalize(
        value: String,
        field: ComplaintReportField,
    ): String = normalize(value, field, field.minimum)

    /** Reply's one-scalar minimum does not weaken the report BODY contract. */
    fun replyBody(value: String): String = normalize(value, ComplaintReportField.BODY, 1)

    /** Edit-only bound: neither report creation nor reply creation gains this larger body limit. */
    fun editBody(value: String): String =
        normalize(value, ComplaintReportField.BODY, 1, MAX_EDIT_POINTS, MAX_EDIT_BYTES)

    private fun normalize(
        value: String,
        field: ComplaintReportField,
        minimum: Int,
        maximum: Int = field.maximum,
        maximumBytes: Int = field.maximumBytes,
    ): String {
        if (value.length > MAX_INPUT_CODE_UNITS) reject(field, ComplaintReportRejection.TOO_LONG)
        val lineNormalized = value.replace("\r\n", "\n")
        validateCharacters(lineNormalized, field)
        val normalized = lineNormalized.trim(::isReportWhitespace)
        val points = scalarCount(normalized)
        if (points < minimum) {
            reject(field, if (points == 0) ComplaintReportRejection.REQUIRED else ComplaintReportRejection.TOO_SHORT)
        }
        if (points > maximum || normalized.encodeToByteArray().size > maximumBytes) {
            reject(field, ComplaintReportRejection.TOO_LONG)
        }
        return normalized
    }

    private fun validateCharacters(
        value: String,
        field: ComplaintReportField,
    ) {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            when {
                character.isHighSurrogate() -> {
                    if (index == value.length || !value[index++].isLowSurrogate()) {
                        reject(field, ComplaintReportRejection.MALFORMED_UNICODE)
                    }
                }
                character.isLowSurrogate() -> reject(field, ComplaintReportRejection.MALFORMED_UNICODE)
                character < ' ' && character != '\t' && character != '\n' ->
                    reject(field, ComplaintReportRejection.FORBIDDEN_CONTROL)
                character in '\u007f'..'\u009f' -> reject(field, ComplaintReportRejection.FORBIDDEN_CONTROL)
            }
        }
    }

    private fun scalarCount(value: String): Int {
        var index = 0
        var count = 0
        while (index < value.length) {
            if (value[index++].isHighSurrogate()) index++
            count++
        }
        return count
    }

    private fun reject(
        field: ComplaintReportField,
        reason: ComplaintReportRejection,
    ): Nothing = throw ComplaintReportTextRejected(field, reason)

    private const val MAX_EDIT_POINTS = 1_000
    private const val MAX_EDIT_BYTES = 4_000
}

private fun isReportWhitespace(value: Char): Boolean =
    when (value) {
        '\t', '\n', ' ', '\u00a0', '\u1680', in '\u2000'..'\u200a',
        '\u2028', '\u2029', '\u202f', '\u205f', '\u3000',
        -> true
        else -> false
    }
