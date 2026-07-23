package me.manga.kira.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies that v12 invalidates the unsafe full-document cache and creates catalog storage. */
class Migration11To12Test {
    private lateinit var connection: SQLiteConnection

    @BeforeTest
    fun open() {
        connection = BundledSQLiteDriver().open(":memory:")
        MIGRATION_10_11.migrate(connection)
        connection.execSQL(
            """
            INSERT INTO source_config_cache (id, rawJson, revision, updatedAtEpochMs)
            VALUES (0, 'old-45-source-envelope', 100, 1)
            """.trimIndent(),
        )
    }

    @AfterTest
    fun close() = connection.close()

    @Test
    fun migration_discards_v11_cache_and_creates_empty_atomic_catalog_tables() {
        MIGRATION_11_12.migrate(connection)

        assertEquals(0L, count("SELECT COUNT(*) FROM source_config_cache"))
        TABLES.forEach { table ->
            assertEquals(
                1L,
                count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'"),
                "$table must exist",
            )
            assertEquals(0L, count("SELECT COUNT(*) FROM $table"), "$table must start empty")
        }
    }

    private fun count(sql: String): Long =
        connection.prepare(sql).use { statement ->
            check(statement.step())
            statement.getLong(0)
        }

    private companion object {
        val TABLES =
            listOf(
                "source_catalog_manifests",
                "source_catalog_entries",
                "source_revision_artifacts",
                "active_source_catalog",
            )
    }
}
