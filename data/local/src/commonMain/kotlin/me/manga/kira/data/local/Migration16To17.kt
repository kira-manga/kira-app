package me.manga.kira.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Adds progress anchors/receipts and effective selection; every existing table and row is untouched. */
val MIGRATION_16_17: Migration =
    object : Migration(16, 17) {
        override fun migrate(connection: SQLiteConnection) {
            createReaderWorkState(connection)
            createReaderChapterState(connection)
            createReaderLegacyCleanup(connection)
            ReaderProgressConstraints.install(connection)
            EffectiveSourceSelectionSchema.migrate(connection)
        }
    }

private fun createReaderWorkState(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `reader_work_state` (
            `workId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `api` TEXT NOT NULL,
            `workUrl` TEXT NOT NULL,
            `workGeneration` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX `index_reader_work_state_api_workUrl` ON `reader_work_state` (`api`, `workUrl`)",
    )
}

private fun createReaderChapterState(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `reader_chapter_state` (
            `chapterId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `workId` INTEGER NOT NULL,
            `chapterUrl` TEXT NOT NULL,
            `chapterGeneration` INTEGER NOT NULL DEFAULT 0,
            `pageIndex` INTEGER,
            FOREIGN KEY (`workId`) REFERENCES `reader_work_state` (`workId`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX `index_reader_chapter_state_workId_chapterUrl` " +
            "ON `reader_chapter_state` (`workId`, `chapterUrl`)",
    )
}

private fun createReaderLegacyCleanup(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE `reader_legacy_cleanup` (
            `legacyKey` TEXT NOT NULL,
            `capturedPayload` TEXT NOT NULL,
            `chapterId` INTEGER NOT NULL,
            `capturedWorkGeneration` INTEGER NOT NULL,
            `capturedChapterGeneration` INTEGER NOT NULL,
            `disposition` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            PRIMARY KEY (`legacyKey`, `capturedPayload`),
            FOREIGN KEY (`chapterId`) REFERENCES `reader_chapter_state` (`chapterId`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX `index_reader_legacy_cleanup_chapterId` ON `reader_legacy_cleanup` (`chapterId`)")
}
