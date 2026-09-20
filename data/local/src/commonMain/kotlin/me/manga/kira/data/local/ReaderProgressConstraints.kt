package me.manga.kira.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room entities cannot declare CHECK clauses. These validation triggers supply the same guards
 * on fresh creation and migration, without an on-open repair or a fictitious exported schema.
 */
object ReaderProgressConstraints : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        install(connection)
    }

    internal fun install(connection: SQLiteConnection) {
        validationTriggers(
            connection,
            "reader_work_state",
            "typeof(NEW.workGeneration) <> 'integer' OR NEW.workGeneration < 0",
        )
        validationTriggers(connection, "reader_chapter_state", invalidChapter)
        validationTriggers(connection, "reader_legacy_cleanup", invalidReceipt)
    }

    private fun validationTriggers(connection: SQLiteConnection, table: String, invalid: String) {
        listOf("INSERT", "UPDATE").forEach { operation ->
            connection.execSQL(
                """
                CREATE TRIGGER `${table}_validate_${operation.lowercase()}`
                BEFORE $operation ON `$table` WHEN $invalid
                BEGIN SELECT RAISE(ABORT, 'Invalid reader progress state'); END
                """.trimIndent(),
            )
        }
    }

    private val invalidChapter =
        """
        typeof(NEW.chapterGeneration) <> 'integer' OR NEW.chapterGeneration < 0
        OR (NEW.pageIndex IS NOT NULL AND
            (typeof(NEW.pageIndex) <> 'integer' OR NEW.pageIndex < 0 OR NEW.pageIndex > ${Int.MAX_VALUE}))
        """.trimIndent()

    private val invalidReceipt =
        """
        typeof(NEW.capturedWorkGeneration) <> 'integer' OR NEW.capturedWorkGeneration < 0
        OR typeof(NEW.capturedChapterGeneration) <> 'integer' OR NEW.capturedChapterGeneration < 0
        OR NEW.disposition NOT IN ('COPIED', 'SUPERSEDED')
        OR NEW.state NOT IN ('PENDING', 'ACKED', 'CONFLICT')
        """.trimIndent()
}
