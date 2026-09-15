package me.manga.kira.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Historical Room export -> production migration -> generated Room validation, never a fake SQL schema. */
class ChapterArtifactSchema15Test {
    @Test
    fun roomMigratesExported14WithoutChangingDownloadHistoryAndCustodySurvivesParentDeletion() = runBlocking {
        val root = Files.createTempDirectory("kira-artifact-schema-")
        val path = root.resolve("migrated.db")
        try {
            createExported14(path)
            val db = Room.databaseBuilder<MangaDatabase>(path.toString())
                .addMigrations(MIGRATION_14_15, MIGRATION_15_16)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
            try {
                // The first generated query opens Room and checks the ENTIRE migrated schema.
                assertTrue(db.chapterArtifactDao().getUnsettled().isEmpty())
                val saved = assertNotNull(db.chapterDao().getChapterByIdSuspend(11))
                assertTrue(saved.isDownloaded)
                assertTrue(saved.isBookmarked)
                assertEquals(listOf("/old-container/chapter_11.cbz"), saved.localImagePaths)
                assertEquals(21L, db.chapterDownloadingDao().getDownloadByChapter(11)?.id)
                assertEquals(123L, db.chapterDownloadingDao().getDownloadByChapter(11)?.sizeBytes)
                val notification = db.notificationDao().getAllNotifications().first().single()
                assertEquals(41L, notification.id)
                assertTrue(notification.isRead)
                assertEquals(saved.localImagePaths, notification.localImagePaths)

                val receipt = ChapterArtifactEntity(
                    chapterId = 11, mangaId = 7, chapterUrl = "chapter", token = TOKEN,
                    operation = ChapterArtifactOperation.DELETE,
                )
                db.chapterArtifactDao().insert(receipt)
                db.libraryDeo().removeMangaWithChapters(7)
                assertNull(db.chapterDao().getChapterByIdSuspend(11))
                assertEquals(receipt, db.chapterArtifactDao().get(11), "FK cascades cannot erase file custody")
            } finally {
                db.close()
            }
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.prepare("PRAGMA user_version").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(16L, statement.getLong(0))
                }
                connection.prepare("PRAGMA foreign_key_list('chapter_artifacts')").use { assertFalse(it.step()) }
            }
        } finally {
            check(root.toFile().deleteRecursively())
        }
    }

    @Test
    fun compilerExportRetainsEvery14EntityAndAddsOnlyArtifactCustody() {
        // 15.json MUST be emitted by actual Room/KSP in the gate. This test does not create it.
        val before = exported(14).getValue("entities").jsonArray.associate { it.jsonObject.tableEntry() }
        val after = exported(15).getValue("entities").jsonArray.associate { it.jsonObject.tableEntry() }
        assertEquals(before.keys + "chapter_artifacts", after.keys)
        before.forEach { (table, entity) -> assertEquals(entity, after[table], "Unrelated v14 entity changed: $table") }
        val artifact = assertNotNull(after["chapter_artifacts"])
        // Room exports omit empty foreign-key arrays; a present nonempty array must still fail.
        assertTrue(artifact["foreignKeys"]?.jsonArray.orEmpty().isEmpty())
        assertEquals(1, artifact.getValue("indices").jsonArray.size)
    }

    @Test
    fun roomMigratesExported15RetainingLegacyReceiptWithoutInventingSourceCleanupAuthority() = runBlocking {
        val root = Files.createTempDirectory("kira-conversion-schema-")
        val path = root.resolve("migrated.db")
        try {
            createExported14(path, version = 15)
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.execSQL("""INSERT INTO chapter_artifacts
                    (chapterId,mangaId,chapterUrl,token,operation,retiring,downloadId,ownsPendingPath)
                    VALUES (11,7,'chapter','$TOKEN','convert',1,21,0)""")
            }
            val db = Room.databaseBuilder<MangaDatabase>(path.toString())
                .addMigrations(MIGRATION_15_16).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val receipt = assertNotNull(db.chapterArtifactDao().get(11))
                assertEquals(TOKEN, receipt.token)
                assertTrue(receipt.retiring)
                assertNull(receipt.conversionSourceRoster)
                assertEquals(123L, db.chapterDownloadingDao().getDownloadByChapter(11)?.sizeBytes)
            } finally { db.close() }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test
    fun compilerExport16AddsOnlyTheNullableConversionRosterToHistorical15() {
        val before = exported(15).getValue("entities").jsonArray.associate { it.jsonObject.tableEntry() }
        val after = exported(16).getValue("entities").jsonArray.associate { it.jsonObject.tableEntry() }
        assertEquals(before.keys, after.keys)
        before.filterKeys { it != "chapter_artifacts" }.forEach { (table, entity) -> assertEquals(entity, after[table]) }
        val prior = assertNotNull(before["chapter_artifacts"])
        val next = assertNotNull(after["chapter_artifacts"])
        val fields = next.getValue("fields").jsonArray
        assertEquals(prior.getValue("fields").jsonArray.toList(), fields.dropLast(1))
        val roster = fields.last().jsonObject
        assertEquals("conversionSourceRoster", roster.getValue("columnName").jsonPrimitive.content)
        assertEquals("TEXT", roster.getValue("affinity").jsonPrimitive.content)
        // Genuine Room exports omit this key when its default value is false.
        assertFalse(roster["notNull"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(prior["primaryKey"], next["primaryKey"])
        assertEquals(prior["indices"], next["indices"])
        assertEquals(prior["foreignKeys"], next["foreignKeys"])
    }

    private fun createExported14(path: Path, version: Int = 14) {
        val database = exported(version)
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            database.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                // The historical export omits this key for tables without indices.
                entity["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            database.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = $version")
            connection.execSQL("""
                INSERT INTO saved_manga
                    (id, api, language, url, imageUrl, title, description, status, rating, genres,
                     savedTimestamp, lastOpenTimestamp, isLiked, isWatchingNow)
                VALUES (7, 'source', 'en', 'manga', 'cover', 'Saved', '', '', NULL, '[]', 1, 1, 0, 0)
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO saved_chapters
                    (id, mangaId, name, number, url, date, isDownloaded, isBookmarked, isRead, isNew,
                     lastReadPage, lastReadDate, localImagePaths, fetchedAt)
                VALUES (11, 7, '1', '1', 'chapter', NULL, 1, 1, 0, 0, 0, 0, '["/old-container/chapter_11.cbz"]', 1)
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO chapter_downloads (id, number, chapterId, mangaId, api, url, state, progress, errorMsg, mangaTitle, sizeBytes)
                VALUES (21, '1', 11, 7, 'source', 'chapter', 'SUCCESS', 100, NULL, 'Saved', 123)
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO notifications
                    (id, api, language, mangaId, mangaTitle, mangaImageUrl, mangaUrl, chapterId,
                     chapterNumber, chapterUrl, notificationDate, isRead, isDownloaded, localImagePaths)
                VALUES (41, 'source', 'en', 7, 'Saved', 'cover', 'manga', 11, '1', 'chapter', 1, 1, 1,
                    '["/old-container/chapter_11.cbz"]')
            """.trimIndent())
        }
    }

    private fun exported(version: Int): JsonObject = Json.parseToJsonElement(
        Files.readString(Path.of("schemas/me.manga.kira.data.local.MangaDatabase/$version.json")),
    ).jsonObject.getValue("database").jsonObject

    private fun JsonObject.tableEntry() = getValue("tableName").jsonPrimitive.content to this

    private companion object { const val TOKEN = "11111111-1111-4111-8111-111111111111" }
}
