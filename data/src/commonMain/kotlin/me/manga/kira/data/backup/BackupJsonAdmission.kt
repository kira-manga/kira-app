package me.manga.kira.data.backup

import me.manga.kira.platform.backup.BackupByteBudget
import me.manga.kira.platform.backup.BackupImportLimitExceeded
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.InvalidBackupArchive

/** Checks ignored JSON as well as schema fields BEFORE kotlinx.serialization allocates DTO lists. */
internal fun admitBackupJson(
    bytes: ByteArray,
    policy: BackupImportPolicy,
    checkpoint: () -> Unit,
): String {
    if (bytes.size.toLong() > policy.json.maxBytes) throw BackupImportLimitExceeded()
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (failure: IllegalArgumentException) {
            throw InvalidBackupArchive(failure)
        }
    BackupJsonAdmission(text, policy, checkpoint).validate()
    return text
}

private class BackupJsonAdmission(
    text: String,
    private val policy: BackupImportPolicy,
    private val checkpoint: () -> Unit,
) {
    private val cursor = BackupJsonCursor(text, policy.json, checkpoint)
    private val mangas = BackupByteBudget(policy.records.maxMangas.toLong())
    private val records = BackupByteBudget(policy.records.maxChaptersAndHistory.toLong())

    fun validate() {
        checkpoint()
        if (cursor.peek() != '{') throw InvalidBackupArchive()
        value(JsonRole.ROOT, 0)
        if (cursor.peek() != null) throw InvalidBackupArchive()
        checkpoint()
    }

    private fun value(role: JsonRole, depth: Int, stringLimit: Int = policy.json.maxStringBytes) {
        when (cursor.peek() ?: throw InvalidBackupArchive()) {
            '{' -> readObject(role, depth + 1)
            '[' -> readArray(role, depth + 1)
            '"' -> cursor.string(stringLimit)
            't' -> cursor.literal("true")
            'f' -> cursor.literal("false")
            'n' -> cursor.literal("null")
            '-', in '0'..'9' -> cursor.number()
            else -> throw InvalidBackupArchive()
        }
    }

    private fun readObject(role: JsonRole, depth: Int) {
        checkDepth(depth)
        cursor.expect('{')
        if (cursor.take('}')) return
        val known = knownBackupFields(role)
        val seen = mutableSetOf<String>()
        do {
            checkpoint()
            val key = cursor.string(policy.json.maxStringBytes, capture = true)
            if (key in known && !seen.add(key)) throw InvalidBackupArchive()
            cursor.expect(':')
            val limit =
                if (role == JsonRole.MANGA && key == "description") {
                    policy.json.maxDescriptionBytes
                } else {
                    policy.json.maxStringBytes
                }
            value(fieldRole(role, key), depth, limit)
            if (cursor.take('}')) return
            cursor.expect(',')
        } while (true)
    }

    private fun readArray(role: JsonRole, depth: Int) {
        checkDepth(depth)
        cursor.expect('[')
        if (cursor.take(']')) return
        do {
            checkpoint()
            val child = when (role) {
                JsonRole.MANGAS -> JsonRole.MANGA.also { mangas.consume(1) }
                JsonRole.CHAPTERS -> JsonRole.CHAPTER.also { records.consume(1) }
                JsonRole.HISTORY_ITEMS -> JsonRole.HISTORY.also { records.consume(1) }
                else -> JsonRole.UNKNOWN
            }
            value(child, depth)
            if (cursor.take(']')) return
            cursor.expect(',')
        } while (true)
    }

    private fun checkDepth(depth: Int) {
        if (depth > policy.json.maxDepth) throw BackupImportLimitExceeded()
    }
}

private enum class JsonRole { ROOT, MANGAS, MANGA, CHAPTERS, CHAPTER, HISTORY_ITEMS, HISTORY, UNKNOWN }

private fun fieldRole(role: JsonRole, key: String): JsonRole =
    when {
        role == JsonRole.ROOT && key == "mangas" -> JsonRole.MANGAS
        role == JsonRole.ROOT && key == "history" -> JsonRole.HISTORY_ITEMS
        role == JsonRole.MANGA && key == "chapters" -> JsonRole.CHAPTERS
        else -> JsonRole.UNKNOWN
    }

private fun knownBackupFields(role: JsonRole): Set<String> =
    when (role) {
        JsonRole.ROOT -> ROOT_FIELDS
        JsonRole.MANGA -> MANGA_FIELDS
        JsonRole.CHAPTER -> CHAPTER_FIELDS
        JsonRole.HISTORY -> HISTORY_FIELDS
        else -> emptySet()
    }

private val ROOT_FIELDS =
    setOf("formatVersion", "appVersion", "dbVersion", "platform", "createdAtEpochMs", "includesDownloads", "mangas", "history")
private val MANGA_FIELDS =
    setOf(
        "api", "language", "url", "imageUrl", "title", "description", "author", "status", "rating", "genres",
        "savedTimestamp", "lastOpenTimestamp", "isLiked", "isWatchingNow", "chapters",
    )
private val CHAPTER_FIELDS =
    setOf("name", "number", "url", "dateEpochDay", "isRead", "isBookmarked", "lastReadDate", "resumePage", "downloadEntry")
private val HISTORY_FIELDS =
    setOf(
        "api", "language", "mangaUrl", "mangaTitle", "mangaImageUrl", "chapterUrl", "chapterTitle",
        "lastReadDateEpochMs", "lastReadPage", "totalPages",
    )
