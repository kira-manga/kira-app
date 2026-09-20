package me.manga.kira.data.local

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Notification identity from v14 remains enforced through the current generated Room schema. */
class NotificationSchema14Test {
    @Test
    fun roomAcceptsMigratedExportedV13AndBothSchemasEnforceNotificationUniqueness() = runBlocking {
        val root = Files.createTempDirectory("kira-notification-schema-")
        try {
            for (migrated in listOf(false, true)) {
                val path = root.resolve(if (migrated) "migrated.db" else "fresh.db")
                verifySchema(path, migrated)
            }
        } finally {
            check(root.toFile().deleteRecursively())
        }
    }

    private suspend fun verifySchema(path: Path, migrated: Boolean) {
        if (migrated) createExportedV13(path)
        val db = Room.databaseBuilder<MangaDatabase>(name = path.toString())
            .addMigrations(MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
            .addCallback(ReaderProgressConstraints)
            .addCallback(EffectiveSourceSelectionSchema)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            // First real query opens/validates the whole migrated schema, not just this table.
            val dao = db.notificationDao()
            if (migrated) {
                val old = dao.getAllNotifications().first().single()
                assertEquals(41L, old.id)
                assertEquals(11L, old.chapterId)
                assertTrue(old.isRead)
            }
            assertDiscoveryUniqueness(db)
        } finally {
            db.close()
        }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            assertEquals(17L, connection.number("PRAGMA user_version"))
            assertEquals(1L, connection.number("SELECT \"unique\" FROM pragma_index_list('notifications') WHERE name = 'index_notifications_chapterId'"))
        }
    }

    private suspend fun assertDiscoveryUniqueness(db: MangaDatabase) {
        val dao = db.notificationDao()
        val parent = parent()
        val parentId = db.libraryDeo().insertManga(parent)
        val chapters = listOf(SavedChapterEntity(mangaId = parentId, name = "2", number = "2", url = "new", date = null))
        val rows = db.libraryDeo().persistChapterDiscoveries(parent.api, parent.url, chapters, parentId)
        val row = rows.single()
        dao.updateNotification(row.copy(isRead = true))
        assertEquals(listOf(-1L), dao.insertNotificationsList(listOf(row.copy(id = 0))))
        assertEquals(row.copy(isRead = true), dao.getNotificationByChapterId(row.chapterId))
    }

    private fun createExportedV13(path: Path) {
        // Gradle Test's working directory is the owning module. Read the historical export itself;
        // do not restate a hand-maintained approximation of all eleven entity schemas.
        val schema = Path.of("schemas/me.manga.kira.data.local.MangaDatabase/13.json")
        val database = Json.parseToJsonElement(Files.readString(schema)).jsonObject.getValue("database").jsonObject
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            database.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val name = entity.getValue("tableName").jsonPrimitive.content
                connection.execSQL(entity.sql().replace("\${TABLE_NAME}", name))
                entity["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(index.jsonObject.sql().replace("\${TABLE_NAME}", name))
                }
            }
            database.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = 13")
            seedLegacyDuplicates(connection)
        }
    }

    private fun seedLegacyDuplicates(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO saved_manga
                (id, api, language, url, imageUrl, title, description, status, rating, genres,
                 savedTimestamp, lastOpenTimestamp, isLiked, isWatchingNow)
            VALUES (7, 'source', 'en', 'old', 'cover', 'Old', '', '', NULL, '[]', 1, 1, 0, 0)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO saved_chapters
                (id, mangaId, name, number, url, date, isDownloaded, isBookmarked, isRead, isNew,
                 lastReadPage, lastReadDate, localImagePaths, fetchedAt)
            VALUES (11, 7, '1', '1', 'chapter', NULL, 0, 0, 0, 1, 0, 0, '[]', 1)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO notifications
                (id, api, language, mangaId, mangaTitle, mangaImageUrl, mangaUrl, chapterId,
                 chapterNumber, chapterUrl, notificationDate, isRead, isDownloaded, localImagePaths)
            VALUES
                (41, 'source', 'en', 7, 'Old', 'cover', 'old', 11, '1', 'chapter', 1, 0, 0, '[]'),
                (42, 'source', 'en', 7, 'Old', 'cover', 'old', 11, '1', 'chapter', 2, 1, 0, '[]')
            """.trimIndent(),
        )
    }

    private fun JsonObject.sql(): String = getValue("createSql").jsonPrimitive.content

    private fun SQLiteConnection.number(sql: String): Long = prepare(sql).use {
        check(it.step())
        it.getLong(0)
    }

    private fun parent() = SavedMangaEntity(
        api = "source", language = "en", title = "New", url = "new", imageUrl = "cover", description = "",
        status = "", rating = null, genres = emptyList(), savedTimestamp = 1, lastOpenTimestamp = 1,
    )
}
