package me.manga.kira.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Uses the committed historical export and its real identity hash; never fabricates schema17. */
internal fun createReaderVersion(file: File, version: Int): Map<String, String> {
    check(!file.exists())
    require(version in 13..16)
    val schema = File("schemas/me.manga.kira.data.local.MangaDatabase/$version.json")
    val database = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
    check(database.getValue("version").jsonPrimitive.content == version.toString())
    return BundledSQLiteDriver().open(file.absolutePath).use { connection ->
        connection.execSQL("PRAGMA foreign_keys = ON")
        database.getValue("entities").jsonArray.forEach { createOldEntity(connection, it.jsonObject) }
        database.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
        seedReaderOldWork(connection)
        seedReaderOldChapter(connection)
        connection.execSQL("PRAGMA user_version = $version")
        oldReaderTableDefinitions(connection)
    }
}

private fun createOldEntity(connection: SQLiteConnection, entity: JsonObject) {
    val table = entity.getValue("tableName").jsonPrimitive.content
    fun expand(sql: String): String = sql.replace("\${TABLE_NAME}", table)
    connection.execSQL(expand(entity.getValue("createSql").jsonPrimitive.content))
    entity["indices"]?.jsonArray?.forEach { index ->
        connection.execSQL(expand(index.jsonObject.getValue("createSql").jsonPrimitive.content))
    }
}

private fun seedReaderOldWork(connection: SQLiteConnection) {
    connection.execSQL(
        """
        INSERT INTO saved_manga
            (id, api, language, url, imageUrl, title, description, author, status, rating, genres,
             savedTimestamp, lastOpenTimestamp, isLiked, isWatchingNow)
        VALUES (7, 'source', 'en', 'https://reader.test/work', 'https://reader.test/cover',
                'Preserved title', 'Preserved description', 'Preserved author', 'ongoing', '4.1',
                '["action"]', 101, 202, 1, 1)
        """.trimIndent(),
    )
}

private fun seedReaderOldChapter(connection: SQLiteConnection) {
    connection.execSQL(
        """
        INSERT INTO saved_chapters
            (id, mangaId, name, number, url, date, isDownloaded, isBookmarked, isRead,
             lastReadPage, lastReadDate, localImagePaths, isNew, fetchedAt)
        VALUES (11, 7, 'Preserved chapter', '1', 'https://reader.test/work/chapter/one', NULL,
                1, 1, 1, 4, 303, '["/offline/chapter.cbz"]', 1, 404)
        """.trimIndent(),
    )
}

private fun oldReaderTableDefinitions(connection: SQLiteConnection): Map<String, String> =
    connection.prepare("SELECT name, sql FROM sqlite_master WHERE type = 'table' ORDER BY name").use { statement ->
        buildMap {
            while (statement.step()) put(statement.getText(0), statement.getText(1))
        }
    }
