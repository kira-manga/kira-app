package me.manga.kira.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies that existing library rows survive the v13 author-column migration. */
class Migration12To13Test {
    private lateinit var connection: SQLiteConnection

    @BeforeTest
    fun open() {
        connection = BundledSQLiteDriver().open(":memory:")
        connection.execSQL(
            """
            CREATE TABLE `saved_manga` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `api` TEXT NOT NULL,
                `language` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `imageUrl` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `rating` TEXT,
                `genres` TEXT NOT NULL,
                `savedTimestamp` INTEGER NOT NULL,
                `lastOpenTimestamp` INTEGER NOT NULL,
                `isLiked` INTEGER NOT NULL,
                `isWatchingNow` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `saved_manga` (
                api, language, url, imageUrl, title, description, status, rating, genres,
                savedTimestamp, lastOpenTimestamp, isLiked, isWatchingNow
            ) VALUES (
                'azora', 'ar', 'https://example/manga', 'https://example/cover',
                'Existing', 'Existing description', 'Ongoing', '4.8', '[]',
                1, 2, 0, 0
            )
            """.trimIndent(),
        )
    }

    @AfterTest
    fun close() = connection.close()

    @Test
    fun migration_preserves_existing_rows_and_defaults_author_to_empty() {
        MIGRATION_12_13.migrate(connection)

        connection.prepare(
            "SELECT title, description, author FROM saved_manga WHERE id = 1",
        ).use { statement ->
            check(statement.step())
            assertEquals("Existing", statement.getText(0))
            assertEquals("Existing description", statement.getText(1))
            assertEquals("", statement.getText(2))
        }
    }
}
