package me.manga.kira.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// Migration note (Phase 6): Room KMP's Migration receives androidx.sqlite.SQLiteConnection (not
// the Android-only androidx.sqlite.db.SupportSQLiteDatabase). The SQL itself is identical, only
// the receiver/extension changes. All 7 source migrations preserved verbatim with the same SQL.
//
// Migration object names (MIGRATION_X_Y vs Migration_4_5) preserved exactly as source uses them.

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        // 1) Create chapter_downloads with all columns (including mangaTitle)
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_downloads (
                id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                number          TEXT    NOT NULL,
                chapterId       INTEGER NOT NULL,
                mangaId         INTEGER NOT NULL,
                api             TEXT    NOT NULL,
                url             TEXT    NOT NULL,
                state           TEXT    NOT NULL,
                progress        INTEGER NOT NULL,
                errorMsg        TEXT,
                mangaTitle      TEXT
            )
            """.trimIndent(),
        )

        // 2) Create unique index on chapterId
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                index_chapter_downloads_chapterId
            ON chapter_downloads(chapterId)
            """.trimIndent(),
        )

        // 3) Add lastOpenTimestamp to saved_manga
        connection.execSQL(
            """
            ALTER TABLE saved_manga
            ADD COLUMN lastOpenTimestamp INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        // 1) Create the new table
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sources` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT    NOT NULL,
                `isEnabled` INTEGER NOT NULL DEFAULT 1,

                `priority` INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // 2) Create the unique index on name (to match @Index(unique = true))
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                index_sources_name
            ON sources(name)
            """.trimIndent(),
        )

        connection.execSQL(
            """
            ALTER TABLE sources
            ADD COLUMN language TEXT NOT NULL DEFAULT 'en'
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        // Add isLiked and isWatchingNow columns to saved_manga table
        connection.execSQL(
            """
            ALTER TABLE saved_manga
            ADD COLUMN isLiked INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )

        connection.execSQL(
            """
            ALTER TABLE saved_manga
            ADD COLUMN isWatchingNow INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
        connection.execSQL(
            """
            ALTER TABLE sources
            ADD COLUMN siteState TEXT NOT NULL DEFAULT 'WORKING'
            """.trimIndent(),
        )
    }
}

val Migration_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        // add baseUrl column with default empty string
        connection.execSQL(
            """
            ALTER TABLE sources
            ADD COLUMN baseUrl TEXT NOT NULL DEFAULT ''
            """.trimIndent(),
        )
        // add baseVersion column with default 0
        connection.execSQL(
            """
            ALTER TABLE sources
            ADD COLUMN baseVersion INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `sources` ADD COLUMN `imageBaseUrl` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `sources` ADD COLUMN `imageUrlVersion` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        // Add new isNew column (boolean -> INTEGER) with default 0 (false)
        connection.execSQL(
            """
            ALTER TABLE saved_chapters
            ADD COLUMN isNew INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        // 1. Create new table with name as primary key
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sources_new (
                name TEXT PRIMARY KEY NOT NULL,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                priority INTEGER NOT NULL,
                language TEXT NOT NULL DEFAULT 'en',
                siteState TEXT NOT NULL DEFAULT 'WORKING',
                baseUrl TEXT NOT NULL DEFAULT '',
                baseVersion INTEGER NOT NULL DEFAULT 0,
                imageBaseUrl TEXT NOT NULL DEFAULT '',
                imageUrlVersion INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        // 2. Copy data from old table (excluding id column)
        connection.execSQL(
            """
            INSERT INTO sources_new (name, isEnabled, priority, language, siteState, baseUrl, baseVersion, imageBaseUrl, imageUrlVersion)
            SELECT name, isEnabled, priority, language, siteState, baseUrl, baseVersion, imageBaseUrl, imageUrlVersion
            FROM sources
            """.trimIndent(),
        )

        // 3. Drop old table
        connection.execSQL("DROP TABLE sources")

        // 4. Rename new table to original name
        connection.execSQL("ALTER TABLE sources_new RENAME TO sources")
    }
}

// v8 -> v9: add the chapter_downloads.sizeBytes column (per-chapter download size in bytes, for
// the native size-display parity feature). Additive single-column ALTER with a NOT NULL DEFAULT 0
// so existing rows migrate cleanly; the 0-valued legacy rows are back-filled on the next launch by
// the startup download-reconcile (which computes the on-disk folder size for any SUCCESS row whose
// sizeBytes is still 0).
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            ALTER TABLE chapter_downloads
            ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
    }
}

// v9 -> v10:
//  (a) Rebuild chapter_downloads with an onDelete=CASCADE FK to saved_manga(id) + an index on the
//      FK child column `mangaId`, so a removed manga can never leave orphaned download-queue rows
//      that resurface a stale "downloaded" badge/size. SQLite can't ALTER-add a FK in place, so we
//      recreate the table; the INSERT...SELECT filters out any ALREADY-orphaned rows (mangaId with
//      no saved_manga) so the new FK is satisfiable. The created table/indices match Room's
//      generated schema for the updated ChapterDownloadEntity exactly (verified vs exported 10.json).
//      FK enforcement is now ON at connection open (ForeignKeysDriver), so we defer FK checks to
//      transaction commit via `PRAGMA defer_foreign_keys = TRUE` (honored inside the migration
//      transaction, unlike `PRAGMA foreign_keys`); the INSERT...SELECT already filters orphans, so
//      the new FK is satisfied at commit and the rebuild stays safe.
//  (b) Add saved_chapters.fetchedAt (epoch-millis discovery timestamp) for the NEW-badge 4-day
//      auto-expire; additive NOT NULL DEFAULT 0 so existing rows migrate cleanly.
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        // Defer FK enforcement to commit-time for the table rebuild below (FK is now ON per-connection).
        connection.execSQL("PRAGMA defer_foreign_keys = TRUE")
        // (a) chapter_downloads rebuild with CASCADE FK + mangaId index.
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_downloads_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `number` TEXT NOT NULL,
                `chapterId` INTEGER NOT NULL,
                `mangaId` INTEGER NOT NULL,
                `api` TEXT NOT NULL,
                `mangaTitle` TEXT,
                `url` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `progress` INTEGER NOT NULL,
                `errorMsg` TEXT,
                `sizeBytes` INTEGER NOT NULL,
                FOREIGN KEY(`mangaId`) REFERENCES `saved_manga`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `chapter_downloads_new` (id, number, chapterId, mangaId, api, mangaTitle, url, state, progress, errorMsg, sizeBytes)
            SELECT id, number, chapterId, mangaId, api, mangaTitle, url, state, progress, errorMsg, sizeBytes
            FROM `chapter_downloads`
            WHERE mangaId IN (SELECT id FROM saved_manga)
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `chapter_downloads`")
        connection.execSQL("ALTER TABLE `chapter_downloads_new` RENAME TO `chapter_downloads`")
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_chapter_downloads_chapterId` ON `chapter_downloads` (`chapterId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_downloads_mangaId` ON `chapter_downloads` (`mangaId`)",
        )

        // (b) saved_chapters.fetchedAt for the NEW-badge 4-day expiry.
        connection.execSQL(
            "ALTER TABLE saved_chapters ADD COLUMN fetchedAt INTEGER NOT NULL DEFAULT 0",
        )
    }
}

// v10 -> v11 (Sources Migration — Phase 1): add the single-row `source_config_cache` table — the
// durable cache tier of the generic-sources config `ConfigStore` (was in-memory only, lost on
// process death). Pure additive CREATE TABLE; the column list/types/PK must match Room's generated
// schema for SourceConfigCacheEntity exactly (id INTEGER PK NOT NULL, rawJson TEXT NOT NULL,
// revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL).
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `source_config_cache` (
                `id` INTEGER NOT NULL,
                `rawJson` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `updatedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster185.staleKdocSweep.cascade,
 * Task #676, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eighty-eighth sibling of the cluster57-184
 * sweep continuum — leaf 4/5 of the wave-55 commonMain :data/local
 * closing-tier 5-leaf batch; Migrations.kt 4/5).
 *
 *  (a) Inline migration-note comment "Migration note (Phase 6): Room KMP's
 *  Migration receives androidx.sqlite.SQLiteConnection (not the Android-only
 *  androidx.sqlite.db.SupportSQLiteDatabase) + The SQL itself is identical,
 *  only the receiver/extension changes + All 7 source migrations preserved
 *  verbatim with the same SQL + Migration object names (MIGRATION_X_Y vs
 *  Migration_4_5) preserved exactly as source uses them" — LIVE-NOT-STALE
 *  for the 7-migration surface AND FULFILLED-PORT for the Phase 6 Room KMP
 *  migration receiver-type port: verified `androidx.sqlite.SQLiteConnection`
 *  + `androidx.sqlite.execSQL` imports (lines 4-5); verified every
 *  `override fun migrate(connection: SQLiteConnection)` signature across
 *  all 7 Migration objects (no `SupportSQLiteDatabase` re-emergence in
 *  source tree); verified `connection.execSQL(...)` is the LIVE extension
 *  call on the new receiver type across all 7 migrations; verified the
 *  4 underscore-style + 1 mixed-style naming convention (MIGRATION_1_2,
 *  MIGRATION_2_3, MIGRATION_3_4, Migration_4_5, MIGRATION_5_6,
 *  MIGRATION_6_7, MIGRATION_7_8) preserved exactly — the `Migration_4_5`
 *  mixed-case is the legacy name and is reached as-is by
 *  `MangaDatabaseFactory.kt` line 30 in the `addMigrations(...)` chain
 *  (no rename to MIGRATION_4_5).
 *
 *  (b) 7 Migration object declarations (MIGRATION_1_2 + MIGRATION_2_3 +
 *  MIGRATION_3_4 + Migration_4_5 + MIGRATION_5_6 + MIGRATION_6_7 +
 *  MIGRATION_7_8) — LIVE-NOT-STALE; all 7 reached by
 *  `MangaDatabaseFactory.buildMangaDatabase()` via the
 *  `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
 *  Migration_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)` chain.
 *  Each migration carries its original SQL verbatim per the Phase 6 port
 *  spec — no SQL re-statement or simplification this sweep. The
 *  MIGRATION_7_8 table-rebuild (sources → sources_new → sources) is
 *  the most complex (4-step CREATE-INSERT-DROP-RENAME) and is LIVE per
 *  the SourcesEntity primary-key change from auto-generated id to name
 *  (cross-referenced against `data/local/entity/SourcesEntity.kt`).
 *
 *  (c) Inline numbered step-comments inside MIGRATION_7_8 (`// 1. Create
 *  new table with name as primary key` through `// 4. Rename new table
 *  to original name`) — LIVE-NOT-STALE documentation aid; each numbered
 *  comment annotates the `connection.execSQL(...)` call it precedes, and
 *  the 4-step numbering matches the LIVE 4-call sequence inside the
 *  migration body.
 *
 * Verified: 7 Migration object declarations + 2 imports
 * (androidx.room.migration.Migration + androidx.sqlite.SQLiteConnection
 * + androidx.sqlite.execSQL) + zero `SupportSQLiteDatabase` re-emergence.
 * Sibling: MangaDatabase (cluster185 prior sibling); MangaDatabaseFactory
 * (cluster185 closing sibling). LEAF 4/5 of the cluster185 commonMain
 * :data/local closing-tier 5-leaf batch. Compound classification:
 * LIVE-NOT-STALE + FULFILLED-PORT for the Phase 6 Room KMP migration
 * receiver-type port. The mixed-case `Migration_4_5` legacy naming is
 * preserved verbatim per the audit-trail-preservation convention.
 * Original Phase-6 migration-note prose preserved verbatim.
 *
 * CORRECTION (#33 / B14, 2026-06-12): every "7 migration" assertion in this postscript is STALE —
 * MIGRATION_8_9 (v8->v9 chapter_downloads.sizeBytes) and MIGRATION_9_10 (v9->v10 CASCADE-FK rebuild +
 * saved_chapters.fetchedAt) were added later, so this file declares 9 Migration objects
 * (MIGRATION_1_2 .. MIGRATION_9_10) and all 9 are registered in buildMangaDatabase(); the database is
 * version = 10. The verbose postscript prose is retained as lineage per the audit-trail-preservation
 * convention.
 */

