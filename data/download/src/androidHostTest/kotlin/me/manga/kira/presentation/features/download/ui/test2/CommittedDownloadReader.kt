package me.manga.kira.presentation.features.download.ui.test2

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import me.manga.kira.data.local.converter.StringListConverter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Read both rows in one fresh native SELECT while Room is still held after its native COMMIT. */
internal fun assertNativeCommittedRows(
    connection: SQLiteConnection,
    original: DownloadRowsSeed,
    paths: List<String>,
) {
    connection.prepare(COMMITTED_ROWS_SQL).use { statement ->
        statement.bindLong(1, original.saved.id)
        assertTrue(statement.step())
        assertNativeLedger(statement, original, paths.size * PAGE_PNG.size.toLong())
        assertNativeSaved(statement, original, paths)
        assertFalse(statement.step())
    }
}

private fun assertNativeLedger(
    statement: SQLiteStatement,
    original: DownloadRowsSeed,
    sizeBytes: Long,
) {
    assertEquals(original.download.id, statement.getLong(statement.column("downloadId")))
    assertEquals(original.download.chapterId, statement.getLong(statement.column("chapterId")))
    assertEquals(original.download.mangaId, statement.getLong(statement.column("downloadMangaId")))
    assertEquals(original.download.url, statement.getText(statement.column("downloadUrl")))
    assertEquals("SUCCESS", statement.getText(statement.column("state")))
    assertEquals(DOWNLOAD_COMPLETION_PROGRESS.toLong(), statement.getLong(statement.column("progress")))
    assertEquals(sizeBytes, statement.getLong(statement.column("sizeBytes")))
    assertTrue(statement.isNull(statement.column("errorMsg")))
}

private fun assertNativeSaved(
    statement: SQLiteStatement,
    original: DownloadRowsSeed,
    paths: List<String>,
) {
    assertEquals(original.saved.id, statement.getLong(statement.column("savedId")))
    assertEquals(original.saved.mangaId, statement.getLong(statement.column("savedMangaId")))
    assertEquals(original.saved.url, statement.getText(statement.column("savedUrl")))
    assertEquals(1L, statement.getLong(statement.column("isDownloaded")))
    assertEquals(paths, StringListConverter().fromString(statement.getText(statement.column("localImagePaths"))))
}

private fun SQLiteStatement.column(name: String): Int = (0 until getColumnCount()).single { getColumnName(it) == name }

private const val COMMITTED_ROWS_SQL =
    """
    SELECT downloads.id AS downloadId, downloads.chapterId, downloads.mangaId AS downloadMangaId,
           downloads.url AS downloadUrl, downloads.state, downloads.progress, downloads.sizeBytes,
           downloads.errorMsg, saved.id AS savedId, saved.mangaId AS savedMangaId,
           saved.url AS savedUrl, saved.isDownloaded, saved.localImagePaths
      FROM chapter_downloads AS downloads
      JOIN saved_chapters AS saved ON saved.id = downloads.chapterId
     WHERE downloads.chapterId = ?
    """
