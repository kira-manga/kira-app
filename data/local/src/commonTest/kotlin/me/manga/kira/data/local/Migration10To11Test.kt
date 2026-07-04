package me.manga.kira.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Driver-level test of [MIGRATION_10_11] (the v10 -> v11 bump that adds the single-row
 * `source_config_cache` table — the durable generic-sources config cache, Sources Migration
 * Phase 1). Runs the migration's raw SQL against an in-memory [BundledSQLiteDriver] connection — no
 * room-testing dependency needed. Room's own post-migration schema validation is covered separately
 * by the exported 11.json matching this DDL (verified identical to Room's generated createSql).
 */
class Migration10To11Test {

    private lateinit var conn: SQLiteConnection

    @BeforeTest
    fun open() {
        conn = BundledSQLiteDriver().open(":memory:")
    }

    @AfterTest
    fun close() = conn.close()

    private fun count(sql: String): Long = conn.prepare(sql).use { st ->
        st.step()
        st.getLong(0)
    }

    private fun text(sql: String): String = conn.prepare(sql).use { st ->
        st.step()
        st.getText(0)
    }

    @Test
    fun migration_creates_empty_source_config_cache_table() {
        MIGRATION_10_11.migrate(conn)

        // Table exists and is empty on a fresh migration.
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='source_config_cache'"),
            "source_config_cache table created",
        )
        assertEquals(0L, count("SELECT COUNT(*) FROM source_config_cache"), "table starts empty")
    }

    @Test
    fun cache_row_can_be_written_and_read_back() {
        MIGRATION_10_11.migrate(conn)

        conn.execSQL(
            "INSERT INTO source_config_cache (id, rawJson, revision, updatedAtEpochMs) " +
                "VALUES (0, '{\"revision\":7}', 7, 1234)",
        )

        // Single-row semantics: id is the PK pinned to 0; the document round-trips.
        assertEquals(1L, count("SELECT COUNT(*) FROM source_config_cache"))
        assertEquals(7L, count("SELECT revision FROM source_config_cache WHERE id = 0"))
        assertEquals("{\"revision\":7}", text("SELECT rawJson FROM source_config_cache WHERE id = 0"))
    }
}
