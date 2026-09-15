package me.manga.kira.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Focused legacy-corruption cases against production migration SQL and the bundled SQLite engine. */
class Migration13To14Test {
    private lateinit var connection: SQLiteConnection

    @BeforeTest
    fun open() {
        connection = BundledSQLiteDriver().open(":memory:")
        connection.execSQL("CREATE TABLE saved_manga (id INTEGER PRIMARY KEY NOT NULL)")
        connection.execSQL(
            """
            CREATE TABLE saved_chapters (
                id INTEGER PRIMARY KEY NOT NULL, mangaId INTEGER NOT NULL, url TEXT NOT NULL,
                isRead INTEGER NOT NULL, isDownloaded INTEGER NOT NULL, localImagePaths TEXT NOT NULL,
                UNIQUE(mangaId, url)
            )
            """.trimIndent(),
        )
        connection.createLegacyNotificationsTable()
        connection.execSQL("INSERT INTO saved_manga VALUES (1), (2)")
        connection.execSQL(
            """
            INSERT INTO saved_chapters VALUES
                (10, 1, 'shared', 0, 1, '["a/page"]'),
                (20, 2, 'shared', 0, 1, '["b/page"]'),
                (21, 2, 'other', 1, 0, '[]')
            """.trimIndent(),
        )
    }

    @AfterTest
    fun close() = connection.close()

    @Test
    fun repairsScopeKeepsOldestIdAndMergesReadIntentWithoutForeignDownloadPaths() {
        notification(100, 1, 10, "shared", read = false)
        notification(101, 1, 10, "shared", read = true)
        notification(102, 2, 10, "shared", read = false) // B incorrectly points to A's chapter.
        notification(103, 2, 20, "shared", read = true)
        notification(104, 2, 10, "missing", read = true) // No matching owned chapter.
        notification(105, 999, 10, "shared", read = true) // Removed/unknown parent.
        notification(106, 2, 0, "other", read = false) // Invalid ID is repairable by scoped URL.
        notification(107, 2, 999, "shared", read = false)

        MIGRATION_13_14.migrate(connection)

        assertSurvivingRows()
        assertEquals(3L, number("SELECT COUNT(*) FROM saved_chapters"))
        assertEquals(2L, number("SELECT COUNT(*) FROM saved_manga"))
        assertEquals(0L, number("SELECT isRead FROM saved_chapters WHERE id = 10"), "migration must not mutate chapter user state")
        assertFails { notification(200, 2, 20, "shared", read = false) }
        assertEquals(3L, number("SELECT COUNT(*) FROM notifications"))
        assertUniqueChapterIndex()
    }

    private fun assertSurvivingRows() {
        connection.prepare(
            "SELECT id, mangaId, chapterId, notificationDate, isRead, isDownloaded, localImagePaths FROM notifications ORDER BY id",
        ).use { rows ->
            for ((id, mangaId, chapterId) in listOf(Triple(100L, 1L, 10L), Triple(102L, 2L, 20L), Triple(106L, 2L, 21L))) {
                assertTrue(rows.step())
                assertEquals(id, rows.getLong(0))
                assertEquals(mangaId, rows.getLong(1))
                assertEquals(chapterId, rows.getLong(2))
                assertEquals(id, rows.getLong(3), "date/position is retained from the oldest ID")
                assertEquals(1L, rows.getLong(4), "read intent is OR-ed across duplicates and the owned chapter")
                assertEquals(if (chapterId == 21L) 0L else 1L, rows.getLong(5))
                assertEquals(when (chapterId) { 10L -> "[\"a/page\"]"; 20L -> "[\"b/page\"]"; else -> "[]" }, rows.getText(6))
            }
            assertFalse(rows.step(), "unresolvable rows are discarded, not rebound by a global chapter ID")
        }
    }

    @Test
    fun emptyNotificationsStillAcquireTheSameUniqueIndex() {
        MIGRATION_13_14.migrate(connection)
        assertUniqueChapterIndex()
        notification(100, 1, 10, "shared", read = false)
        assertFails { notification(101, 1, 10, "shared", read = true) }
        assertEquals(1L, number("SELECT COUNT(*) FROM notifications"))
    }

    private fun notification(id: Long, mangaId: Long, chapterId: Long, url: String, read: Boolean) {
        connection.prepare(
            """
            INSERT INTO notifications
                (id, api, language, mangaId, mangaTitle, mangaImageUrl, mangaUrl, chapterId,
                 chapterNumber, chapterUrl, notificationDate, isRead, isDownloaded, localImagePaths)
            VALUES (?, 'source', 'en', ?, 'Same title', 'old cover', 'old parent url', ?, '1', ?, ?, ?, 1, '["wrong/page"]')
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, id)
            statement.bindLong(2, mangaId)
            statement.bindLong(3, chapterId)
            statement.bindText(4, url)
            statement.bindLong(5, id)
            statement.bindLong(6, if (read) 1L else 0L)
            statement.step()
        }
    }

    private fun assertUniqueChapterIndex() {
        assertEquals(1L, number("SELECT \"unique\" FROM pragma_index_list('notifications') WHERE name = 'index_notifications_chapterId'"))
        connection.prepare("SELECT name FROM pragma_index_info('index_notifications_chapterId')").use {
            assertTrue(it.step())
            assertEquals("chapterId", it.getText(0))
            assertFalse(it.step())
        }
    }

    private fun number(sql: String): Long = connection.prepare(sql).use {
        check(it.step())
        it.getLong(0)
    }
}

/** The v1–v13 notification table; shared only by migration fixtures, not a fresh-schema oracle. */
internal fun SQLiteConnection.createLegacyNotificationsTable() {
    execSQL(
        """
        CREATE TABLE notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, api TEXT NOT NULL, language TEXT NOT NULL,
            mangaId INTEGER NOT NULL, mangaTitle TEXT NOT NULL, mangaImageUrl TEXT NOT NULL, mangaUrl TEXT NOT NULL,
            chapterId INTEGER NOT NULL, chapterNumber TEXT NOT NULL, chapterUrl TEXT NOT NULL,
            notificationDate INTEGER NOT NULL, isRead INTEGER NOT NULL, isDownloaded INTEGER NOT NULL,
            localImagePaths TEXT NOT NULL
        )
        """.trimIndent(),
    )
}
