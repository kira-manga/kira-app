package me.manga.kira.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Driver-level test of [MIGRATION_9_10] (the v9 -> v10 schema bump that adds a CASCADE FK + mangaId
 * index to `chapter_downloads` and a `fetchedAt` column to `saved_chapters`). Runs the migration's
 * raw SQL against an in-memory [BundledSQLiteDriver] connection seeded with the v9 shape — no
 * room-testing dependency needed. Room's own post-migration schema validation is covered separately
 * by the exported 10.json matching this DDL.
 */
class Migration9To10Test {

    private lateinit var conn: SQLiteConnection

    @BeforeTest
    fun open() {
        conn = BundledSQLiteDriver().open(":memory:")
        // Minimal v9 shapes (only the columns the migration reads/needs).
        conn.execSQL("CREATE TABLE saved_manga (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        conn.execSQL(
            """
            CREATE TABLE saved_chapters (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `isNew` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        conn.execSQL(
            """
            CREATE TABLE chapter_downloads (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `number` TEXT NOT NULL, `chapterId` INTEGER NOT NULL, `mangaId` INTEGER NOT NULL,
                `api` TEXT NOT NULL, `mangaTitle` TEXT, `url` TEXT NOT NULL, `state` TEXT NOT NULL,
                `progress` INTEGER NOT NULL, `errorMsg` TEXT, `sizeBytes` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        conn.execSQL("CREATE UNIQUE INDEX `index_chapter_downloads_chapterId` ON chapter_downloads (`chapterId`)")
    }

    @AfterTest
    fun close() = conn.close()

    private fun count(sql: String): Long = conn.prepare(sql).use { st ->
        st.step()
        st.getLong(0)
    }

    private fun seedDownload(chapterId: Long, mangaId: Long) {
        conn.execSQL(
            "INSERT INTO chapter_downloads (number, chapterId, mangaId, api, url, state, progress, sizeBytes) " +
                "VALUES ('1', $chapterId, $mangaId, 'src', 'u/$chapterId', 'SUCCESS', 100, 42)",
        )
    }

    @Test
    fun migration_purges_orphans_keeps_live_rows_and_adds_fetchedAt() {
        conn.execSQL("INSERT INTO saved_manga (id) VALUES (1)")
        seedDownload(chapterId = 10, mangaId = 1)   // live (parent exists)
        seedDownload(chapterId = 20, mangaId = 999) // orphan (no parent saved_manga)
        conn.execSQL("INSERT INTO saved_chapters (id, isNew) VALUES (1, 1)")

        MIGRATION_9_10.migrate(conn)

        assertEquals(1L, count("SELECT COUNT(*) FROM chapter_downloads"), "orphan row purged, live row kept")
        assertEquals(1L, count("SELECT mangaId FROM chapter_downloads"), "surviving row is the live one")
        // fetchedAt column added with default 0 for pre-existing rows.
        assertEquals(0L, count("SELECT fetchedAt FROM saved_chapters WHERE id = 1"))
    }

    @Test
    fun cascade_fk_deletes_download_rows_when_manga_deleted() {
        conn.execSQL("INSERT INTO saved_manga (id) VALUES (1)")
        seedDownload(chapterId = 10, mangaId = 1)

        MIGRATION_9_10.migrate(conn)

        // The runtime enables FK enforcement (ForeignKeysOnCallback onOpen); replicate it here.
        conn.execSQL("PRAGMA foreign_keys = ON")
        conn.execSQL("DELETE FROM saved_manga WHERE id = 1")

        assertEquals(0L, count("SELECT COUNT(*) FROM chapter_downloads"), "CASCADE removed the child download row")
    }
}
