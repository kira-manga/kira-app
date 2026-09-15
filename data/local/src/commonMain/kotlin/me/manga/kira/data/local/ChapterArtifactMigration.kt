package me.manga.kira.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** DB14 is the separately owned notification-identity migration; artifacts add only DB15. */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_artifacts` (
                `chapterId` INTEGER NOT NULL,
                `mangaId` INTEGER NOT NULL,
                `chapterUrl` TEXT NOT NULL,
                `token` TEXT,
                `operation` TEXT,
                `retiring` INTEGER NOT NULL,
                `downloadId` INTEGER,
                `pendingRelativePath` TEXT,
                `pendingSizeBytes` INTEGER,
                `ownsPendingPath` INTEGER NOT NULL,
                `committedToken` TEXT,
                `committedRelativePath` TEXT,
                `retiredRelativePath` TEXT,
                PRIMARY KEY(`chapterId`)
            )
            """.trimIndent(),
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_artifacts_mangaId` ON `chapter_artifacts` (`mangaId`)")
    }
}
