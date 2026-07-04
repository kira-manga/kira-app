package me.manga.yamiapk.data.local


import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1) Create chapter_downloads with all columns (including mangaTitle)
        db.execSQL("""
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
        """.trimIndent())

        // 2) Create unique index on chapterId
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS
                index_chapter_downloads_chapterId
            ON chapter_downloads(chapterId)
        """.trimIndent())

        // 3) Add lastOpenTimestamp to saved_manga
        db.execSQL("""
            ALTER TABLE saved_manga
            ADD COLUMN lastOpenTimestamp INTEGER NOT NULL DEFAULT 0
        """.trimIndent())
    }
}
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1) Create the new table
        db.execSQL("""
      CREATE TABLE IF NOT EXISTS `sources` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT    NOT NULL,
        `isEnabled` INTEGER NOT NULL DEFAULT 1,
        
        `priority` INTEGER NOT NULL
      )
    """.trimIndent())

        // 2) Create the unique index on name (to match your @Index(unique = true))
        db.execSQL("""
      CREATE UNIQUE INDEX IF NOT EXISTS 
        index_sources_name 
      ON sources(name)
    """.trimIndent())

        db.execSQL("""
      ALTER TABLE sources
      ADD COLUMN language TEXT NOT NULL DEFAULT 'en'
    """.trimIndent())
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add isLiked and isWatchingNow columns to saved_manga table
        db.execSQL("""
            ALTER TABLE saved_manga
            ADD COLUMN isLiked INTEGER NOT NULL DEFAULT 0
        """.trimIndent())

        db.execSQL("""
            ALTER TABLE saved_manga
            ADD COLUMN isWatchingNow INTEGER NOT NULL DEFAULT 0
        """.trimIndent())
        db.execSQL("""
            ALTER TABLE sources
            ADD COLUMN siteState TEXT NOT NULL DEFAULT 'WORKING'
        """.trimIndent())
    }
}

val Migration_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // add baseUrl column with default empty string
        db.execSQL("""
      ALTER TABLE sources 
      ADD COLUMN baseUrl TEXT NOT NULL DEFAULT ''
    """.trimIndent())
        // add baseVersion column with default 0
        db.execSQL("""
      ALTER TABLE sources 
      ADD COLUMN baseVersion INTEGER NOT NULL DEFAULT 0
    """.trimIndent())
    }



}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // replace "sources" with the actual table name in your @Entity(tableName = ...)
        db.execSQL("ALTER TABLE `sources` ADD COLUMN `imageBaseUrl` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `sources` ADD COLUMN `imageUrlVersion` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add new isNew column (boolean -> INTEGER) with default 0 (false)
        db.execSQL("""
            ALTER TABLE saved_chapters
            ADD COLUMN isNew INTEGER NOT NULL DEFAULT 0
        """.trimIndent())
    }
}


val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create new table with name as primary key
        db.execSQL("""
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
        """.trimIndent())

        // 2. Copy data from old table (excluding id column)
        db.execSQL("""
            INSERT INTO sources_new (name, isEnabled, priority, language, siteState, baseUrl, baseVersion, imageBaseUrl, imageUrlVersion)
            SELECT name, isEnabled, priority, language, siteState, baseUrl, baseVersion, imageBaseUrl, imageUrlVersion
            FROM sources
        """.trimIndent())

        // 3. Drop old table
        db.execSQL("DROP TABLE sources")

        // 4. Rename new table to original name
        db.execSQL("ALTER TABLE sources_new RENAME TO sources")
    }
}