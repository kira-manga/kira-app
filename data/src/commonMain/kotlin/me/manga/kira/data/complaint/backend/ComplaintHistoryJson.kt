package me.manga.kira.data.complaint.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Closed shallow wire shapes only. Detect duplicates before JsonObject could collapse them. */
internal class ComplaintHistoryJson(private val text: String, private val maximumBytes: Int = MAX_LIST_BYTES) {
    private var position = 0

    fun read(): JsonObject {
        if (text.encodeToByteArray().size > maximumBytes) invalidHistory()
        val result = value(0) as? JsonObject ?: invalidHistory()
        whitespace()
        if (position != text.length) invalidHistory()
        return result
    }

    private fun value(depth: Int): JsonElement {
        if (depth > MAX_DEPTH) invalidHistory()
        whitespace()
        val character = text.getOrNull(position) ?: invalidHistory()
        return when (character) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> JsonPrimitive(string())
            'n' -> {
                if (!text.startsWith("null", position)) invalidHistory()
                position += 4
                JsonNull
            }
            in '0'..'9' -> integer()
            else -> invalidHistory()
        }
    }

    private fun objectValue(depth: Int): JsonObject {
        whitespace()
        val start = position
        expect('{')
        val fields = linkedMapOf<String, JsonElement>()
        if (!take('}')) {
            do {
                if (fields.size == MAX_FIELDS) invalidHistory()
                val key = string()
                if (key.length > MAX_KEY || key in fields) invalidHistory()
                expect(':')
                fields[key] = value(depth + 1)
                if (take('}')) break
                expect(',')
            } while (true)
        }
        // Every owner row/notice is an independently bounded serialized object, including escaping.
        if (depth == 2 && text.substring(start, position).encodeToByteArray().size > MAX_ROW_BYTES) invalidHistory()
        return JsonObject(fields)
    }

    private fun arrayValue(depth: Int): JsonArray {
        expect('[')
        val entries = mutableListOf<JsonElement>()
        if (!take(']')) {
            do {
                if (entries.size == MAX_ARRAY) invalidHistory()
                entries += value(depth + 1)
                if (take(']')) break
                expect(',')
            } while (true)
        }
        return JsonArray(entries)
    }

    private fun string(): String {
        whitespace()
        val start = position
        expect('"')
        while (position < text.length && position - start <= MAX_ROW_BYTES) {
            when (text[position++]) {
                '\\' -> {
                    if (position == text.length) invalidHistory()
                    position++
                }
                '"' -> {
                    val raw = text.substring(start, position)
                    if (raw.encodeToByteArray().size > MAX_ROW_BYTES) invalidHistory()
                    val parsed = Json.parseToJsonElement(raw) as? JsonPrimitive
                    val result = parsed?.takeIf { it.isString }?.content ?: invalidHistory()
                    validUnicode(result)
                    return result
                }
                in '\u0000'..'\u001f' -> invalidHistory()
            }
        }
        invalidHistory()
    }

    private fun validUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char < ' ' && char != '\t' && char != '\n' || char in '\u007f'..'\u009f') invalidHistory()
            if (char.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) invalidHistory()
            } else if (char.isLowSurrogate()) {
                invalidHistory()
            }
        }
    }

    private fun integer(): JsonPrimitive {
        val start = position
        while (position < text.length && text[position] in '0'..'9') position++
        val raw = text.substring(start, position)
        val number = raw.toLongOrNull() ?: invalidHistory()
        if (number.toString() != raw) invalidHistory()
        return JsonPrimitive(number)
    }

    private fun expect(character: Char) {
        if (!take(character)) invalidHistory()
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

    private companion object {
        const val MAX_LIST_BYTES = 2 * 1_024 * 1_024
        const val MAX_DEPTH = 3
        const val MAX_FIELDS = 24
        const val MAX_ARRAY = 50
        const val MAX_KEY = 32
        const val MAX_ROW_BYTES = 32 * 1_024
    }
}

internal class InvalidComplaintHistory : Exception()

internal fun invalidHistory(): Nothing = throw InvalidComplaintHistory()

internal fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidHistory()

internal fun JsonObject.nullableString(name: String): String? =
    if (get(name) === JsonNull) null else string(name)

internal fun JsonObject.number(name: String): Long {
    val value = get(name) as? JsonPrimitive ?: invalidHistory()
    if (value.isString || value === JsonNull) invalidHistory()
    return value.content.toLongOrNull() ?: invalidHistory()
}
