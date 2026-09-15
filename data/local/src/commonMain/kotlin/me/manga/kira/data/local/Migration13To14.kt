package me.manga.kira.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Makes notification discovery unique. Repair old URL-only chapter bindings using the stored
 * manga ID + chapter URL, discard unresolvable rows, then keep the oldest ID for each chapter.
 * Read intent is merged rather than reset; download flags/paths come from the correctly owned
 * saved chapter, never from a notification that may have pointed at another manga's chapter.
 * Room runs this migration in a transaction. No chapter or manga row is removed or rewritten.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(connection: SQLiteConnection) {
        repairOwnedChapterIds(connection)
        // The old table has no chapter index. Bound duplicate-state lookups for long update histories.
        connection.execSQL("CREATE INDEX notification_discovery_repair ON notifications (chapterId)")
        mergeSurvivingState(connection)
        connection.execSQL(
            "DELETE FROM notifications WHERE id NOT IN (SELECT MIN(id) FROM notifications GROUP BY chapterId)",
        )
        connection.execSQL("DROP INDEX notification_discovery_repair")
        connection.execSQL("CREATE UNIQUE INDEX index_notifications_chapterId ON notifications (chapterId)")
    }
}

private fun repairOwnedChapterIds(connection: SQLiteConnection) {
    connection.execSQL(
        """
        DELETE FROM notifications
        WHERE NOT EXISTS (
            SELECT 1 FROM saved_chapters AS chapter
            JOIN saved_manga AS manga ON manga.id = chapter.mangaId
            WHERE chapter.mangaId = notifications.mangaId
              AND chapter.url = notifications.chapterUrl
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        UPDATE notifications SET chapterId = (
            SELECT id FROM saved_chapters
            WHERE mangaId = notifications.mangaId AND url = notifications.chapterUrl
        )
        """.trimIndent(),
    )
}

private fun mergeSurvivingState(connection: SQLiteConnection) {
    connection.execSQL(
        """
        UPDATE notifications SET
            isRead = CASE WHEN EXISTS (
                SELECT 1 FROM notifications AS duplicate
                WHERE duplicate.chapterId = notifications.chapterId AND duplicate.isRead = 1
            ) OR (SELECT isRead FROM saved_chapters WHERE id = notifications.chapterId) = 1
                THEN 1 ELSE 0 END,
            isDownloaded = (SELECT isDownloaded FROM saved_chapters WHERE id = notifications.chapterId),
            localImagePaths = (SELECT localImagePaths FROM saved_chapters WHERE id = notifications.chapterId)
        WHERE id IN (SELECT MIN(id) FROM notifications GROUP BY chapterId)
        """.trimIndent(),
    )
}
