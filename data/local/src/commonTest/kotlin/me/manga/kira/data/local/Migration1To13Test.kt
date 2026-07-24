package me.manga.kira.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the complete supported upgrade path from the oldest application schema to v13.
 *
 * This starts with representative v1 library/chapter rows, adds data to tables at the version
 * where those tables first exist, then runs every production [Migration] in order. The focused
 * v9 -> v10 through v12 -> v13 tests cover their edge cases separately; this test guards against
 * a missing/reordered migration and proves old library data reaches the current schema.
 */
class Migration1To13Test {
    private lateinit var connection: SQLiteConnection

    @BeforeTest
    fun open() {
        connection = BundledSQLiteDriver().open(":memory:")
        createVersionOneSchema()
        seedVersionOneData()
    }

    @AfterTest
    fun close() = connection.close()

    @Test
    fun oldest_schema_reaches_v13_without_losing_library_data() {
        MIGRATION_1_2.migrate(connection)
        connection.execSQL(
            "INSERT INTO chapter_downloads " +
                "(number, chapterId, mangaId, api, url, state, progress, errorMsg, mangaTitle) " +
                "VALUES ('1', 11, 7, 'legacy', 'https://legacy/chapter/1', 'SUCCESS', 100, NULL, 'Legacy Manga')",
        )
        connection.execSQL(
            "INSERT INTO chapter_downloads " +
                "(number, chapterId, mangaId, api, url, state, progress, errorMsg, mangaTitle) " +
                "VALUES ('orphan', 99, 999, 'legacy', 'https://legacy/chapter/orphan', 'FAILED', 0, NULL, 'Orphan')",
        )

        MIGRATION_2_3.migrate(connection)
        connection.execSQL(
            "INSERT INTO sources (name, isEnabled, priority, language) VALUES ('Legacy Source', 1, 3, 'ar')",
        )

        productionMigrations.drop(2).forEach { it.migrate(connection) }

        assertEquals("Legacy Manga", text("SELECT title FROM saved_manga WHERE id = 7"))
        assertEquals(0L, number("SELECT lastOpenTimestamp FROM saved_manga WHERE id = 7"))
        assertEquals(0L, number("SELECT isLiked FROM saved_manga WHERE id = 7"))
        assertEquals(0L, number("SELECT isWatchingNow FROM saved_manga WHERE id = 7"))
        assertEquals("", text("SELECT author FROM saved_manga WHERE id = 7"))

        assertEquals("Chapter 1", text("SELECT name FROM saved_chapters WHERE id = 11"))
        assertEquals(0L, number("SELECT isNew FROM saved_chapters WHERE id = 11"))
        assertEquals(0L, number("SELECT fetchedAt FROM saved_chapters WHERE id = 11"))

        assertEquals(1L, number("SELECT COUNT(*) FROM chapter_downloads"), "orphan removed at v9 -> v10")
        assertEquals(0L, number("SELECT sizeBytes FROM chapter_downloads WHERE chapterId = 11"))
        assertEquals(
            1L,
            number(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_chapter_downloads_mangaId'",
            ),
        )

        assertEquals("Legacy Source", text("SELECT name FROM sources"))
        assertEquals("ar", text("SELECT language FROM sources"))
        assertEquals("WORKING", text("SELECT siteState FROM sources"))
        assertEquals("", text("SELECT baseUrl FROM sources"))
        assertEquals(0L, number("SELECT imageUrlVersion FROM sources"))

        assertEquals(
            1L,
            number("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'source_config_cache'"),
        )
        assertEquals(0L, number("SELECT COUNT(*) FROM source_config_cache"))
        assertEquals(
            1L,
            number("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'active_source_catalog'"),
        )
    }

    private fun createVersionOneSchema() {
        connection.execSQL(
            """
            CREATE TABLE saved_manga (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                api TEXT NOT NULL,
                language TEXT NOT NULL,
                url TEXT NOT NULL,
                imageUrl TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                status TEXT NOT NULL,
                rating TEXT,
                genres TEXT NOT NULL,
                savedTimestamp INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE saved_chapters (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                mangaId INTEGER NOT NULL,
                name TEXT NOT NULL,
                number TEXT NOT NULL,
                url TEXT NOT NULL,
                date INTEGER,
                isDownloaded INTEGER NOT NULL,
                isBookmarked INTEGER NOT NULL,
                isRead INTEGER NOT NULL,
                lastReadPage INTEGER NOT NULL,
                lastReadDate INTEGER NOT NULL,
                localImagePaths TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun seedVersionOneData() {
        connection.execSQL(
            """
            INSERT INTO saved_manga
                (id, api, language, url, imageUrl, title, description, status, rating, genres, savedTimestamp)
            VALUES
                (7, 'legacy', 'ar', 'https://legacy/manga/1', 'https://legacy/cover.jpg',
                 'Legacy Manga', 'kept through every migration', 'Ongoing', '4.0', '["action"]', 123)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO saved_chapters
                (id, mangaId, name, number, url, date, isDownloaded, isBookmarked, isRead,
                 lastReadPage, lastReadDate, localImagePaths)
            VALUES
                (11, 7, 'Chapter 1', '1', 'https://legacy/chapter/1', NULL, 0, 1, 1, 4, 456, '[]')
            """.trimIndent(),
        )
    }

    private fun number(
        sql: String,
        message: String? = null,
    ): Long =
        connection.prepare(sql).use { statement ->
            check(statement.step()) { message ?: "query returned no row: $sql" }
            statement.getLong(0)
        }

    private fun text(sql: String): String =
        connection.prepare(sql).use { statement ->
            check(statement.step()) { "query returned no row: $sql" }
            statement.getText(0)
        }

    private companion object {
        val productionMigrations =
            listOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                Migration_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
            )
    }
}
