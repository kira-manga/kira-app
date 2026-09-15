package me.manga.kira.data.backup

import me.manga.kira.platform.backup.BackupByteBudget
import me.manga.kira.platform.backup.BackupImportLimitExceeded
import me.manga.kira.platform.backup.BackupJsonLimits
import me.manga.kira.platform.backup.InvalidBackupArchive

/** Bounded lexical pass over already byte-admitted UTF-8. Only property names are materialized. */
internal class BackupJsonCursor(
    private val text: String,
    private val limits: BackupJsonLimits,
    private val checkpoint: () -> Unit,
) {
    private var index = 0
    private val tokens = BackupByteBudget(limits.maxTokens)

    fun peek(): Char? {
        skipWhitespace()
        return text.getOrNull(index)
    }

    fun take(expected: Char): Boolean {
        if (peek() != expected) return false
        tokens.consume(1)
        advance()
        return true
    }

    fun expect(expected: Char) {
        if (!take(expected)) throw InvalidBackupArchive()
    }

    fun string(limit: Int, capture: Boolean = false): String {
        expect('"')
        val captured = if (capture) StringBuilder() else null
        var bytes = 0
        while (index < text.length && text[index] != '"') {
            val first = stringUnit()
            val second = if (first.isHighSurrogate()) stringUnit() else null
            if (first.isLowSurrogate() || (second != null && !second.isLowSurrogate())) throw InvalidBackupArchive()
            val width = when {
                second != null -> UTF8_PAIR_BYTES
                first.code < UTF8_ONE_END -> 1
                first.code < UTF8_TWO_END -> 2
                else -> 3
            }
            if (width > limit - bytes) throw BackupImportLimitExceeded()
            bytes += width
            captured?.append(first)
            if (second != null) captured?.append(second)
        }
        if (index == text.length) throw InvalidBackupArchive()
        advance()
        return captured?.toString().orEmpty()
    }

    fun literal(value: String) {
        tokens.consume(1)
        for (character in value) {
            if (text.getOrNull(index) != character) throw InvalidBackupArchive()
            advance()
        }
    }

    fun number() {
        tokens.consume(1)
        val start = index
        if (text.getOrNull(index) == '-') advance()
        if (text.getOrNull(index) == '0') {
            advance()
            if (text.getOrNull(index).isJsonDigit()) throw InvalidBackupArchive()
        } else {
            digits(start)
        }
        if (text.getOrNull(index) == '.') {
            advance()
            digits(start)
        }
        if (text.getOrNull(index) == 'e' || text.getOrNull(index) == 'E') {
            advance()
            if (text.getOrNull(index) == '+' || text.getOrNull(index) == '-') advance()
            digits(start)
        }
        if (index - start > limits.maxStringBytes) throw BackupImportLimitExceeded()
    }

    private fun digits(start: Int) {
        if (!text.getOrNull(index).isJsonDigit()) throw InvalidBackupArchive()
        while (text.getOrNull(index).isJsonDigit()) {
            advance()
            if (index - start > limits.maxStringBytes) throw BackupImportLimitExceeded()
        }
    }

    private fun stringUnit(): Char {
        val value = text.getOrNull(index) ?: throw InvalidBackupArchive()
        advance()
        if (value.code < JSON_SPACE || value == '"') throw InvalidBackupArchive()
        return if (value == '\\') escapedUnit() else value
    }

    private fun escapedUnit(): Char {
        val value = text.getOrNull(index) ?: throw InvalidBackupArchive()
        advance()
        return when (value) {
            '"', '\\', '/' -> value
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> unicodeUnit()
            else -> throw InvalidBackupArchive()
        }
    }

    private fun unicodeUnit(): Char {
        var code = 0
        repeat(UNICODE_HEX_DIGITS) {
            val character = text.getOrNull(index) ?: throw InvalidBackupArchive()
            val digit = when (character) {
                in '0'..'9' -> character.code - '0'.code
                in 'a'..'f' -> character.code - 'a'.code + HEX_LETTER_OFFSET
                in 'A'..'F' -> character.code - 'A'.code + HEX_LETTER_OFFSET
                else -> throw InvalidBackupArchive()
            }
            code = code * HEX_RADIX + digit
            advance()
        }
        return code.toChar()
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in JSON_WHITESPACE) advance()
    }

    private fun advance() {
        index++
        if (index % CHECKPOINT_CHARACTERS == 0) checkpoint()
    }
}

private fun Char?.isJsonDigit(): Boolean = this != null && this in '0'..'9'

private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
private const val JSON_SPACE = 0x20
private const val UTF8_ONE_END = 0x80
private const val UTF8_TWO_END = 0x800
private const val UTF8_PAIR_BYTES = 4
private const val UNICODE_HEX_DIGITS = 4
private const val HEX_RADIX = 16
private const val HEX_LETTER_OFFSET = 10
private const val CHECKPOINT_CHARACTERS = 1024
