package me.manga.kira.data.local.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.presentation.features.download.data.DownloadingState
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal const val COMPLETION_SIZE_BYTES = 4_321L
internal const val REPEAT_SIZE_BYTES = 99L
internal const val ROLLBACK_SIZE_BYTES = 12_345L
internal const val IGNORED_WRITE_SIZE_BYTES = 500L
internal const val INELIGIBLE_SIZE_BYTES = 50L
private const val INVALID_SAVED_SIZE_BYTES = 10L

internal fun completionTest(block: suspend ChapterDownloadCompletionFixture.() -> Unit) =
    runTest {
        val fixture = ChapterDownloadCompletionFixture()
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

/** Only this completion suite owns the database and its native fault-injection triggers. */
internal class ChapterDownloadCompletionFixture {
    private val root = Files.createTempDirectory("kira-download-completion-")
    private val databasePath: String get() = root.resolve("completion.db").toString()
    private var nextManga = 0
    var db: MangaDatabase = openDatabase()
        private set
    val dao: ChapterDownloadDao get() = db.chapterDownloadingDao()

    fun close() {
        try {
            db.close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun openDatabase(): MangaDatabase =
        Room
            .databaseBuilder<MangaDatabase>(name = databasePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()

    fun reopen() {
        db.close()
        db = openDatabase()
    }

    fun executeWhileClosed(sql: String) {
        db.close()
        val connection = BundledSQLiteDriver().open(databasePath)
        try {
            connection.execSQL(sql)
        } finally {
            connection.close()
            db = openDatabase()
        }
    }

    fun rejectSavedCompletionWhenLedgerWritten(
        original: SeededDownload,
        size: Long,
    ) {
        // The trigger fires only after the actual earlier ledger UPDATE in this transaction.
        executeWhileClosed(
            """
            CREATE TRIGGER reject_saved_completion
            BEFORE UPDATE OF isDownloaded ON saved_chapters
            WHEN NEW.id = ${original.saved.id} AND NEW.isDownloaded = 1
              AND EXISTS (
                SELECT 1 FROM chapter_downloads
                WHERE id = ${original.download.id} AND chapterId = NEW.id
                  AND state = 'SUCCESS' AND progress = 100 AND sizeBytes = $size AND errorMsg IS NULL
              )
            BEGIN
                SELECT RAISE(ABORT, 'reject_saved_completion');
            END
            """.trimIndent(),
        )
    }

    private suspend fun seedManga(number: Int): Long =
        db.backupDao().insertMangaRow(
            SavedMangaEntity(
                api = "test",
                language = "en",
                url = "https://example.test/manga/$number",
                imageUrl = "https://example.test/cover.jpg",
                title = "Manga $number",
                description = "",
                status = "ongoing",
                rating = null,
                genres = emptyList(),
                savedTimestamp = 1L,
                lastOpenTimestamp = 2L,
            ),
        )

    suspend fun seed(
        state: DownloadingState,
        paths: List<String>,
    ): SeededDownload {
        val number = ++nextManga
        val saved = chapterToComplete(seedManga(number), number, paths)
        val chapterId = db.backupDao().insertChapterRow(saved)
        val download =
            ChapterDownloadEntity(
                number = saved.number,
                chapterId = chapterId,
                mangaId = saved.mangaId,
                api = "test",
                mangaTitle = "Manga $number",
                url = saved.url,
                state = state,
                progress = 63,
                errorMsg = "previous attempt",
                sizeBytes = 17L,
            )
        return SeededDownload(saved.copy(id = chapterId), download.copy(id = dao.insert(download)))
    }

    suspend fun assertInvalidSavedChapter(
        original: SeededDownload,
        expectedSaved: SavedChapterEntity?,
    ) {
        assertFailsWith<IllegalStateException> {
            dao.completeDownload(original.download, original.saved.localImagePaths, INVALID_SAVED_SIZE_BYTES)
        }
        assertEquals(original.download, dao.getDownloadByChapter(original.saved.id))
        assertEquals(expectedSaved, db.chapterDao().getChapterByIdSuspend(original.saved.id))
    }

    suspend fun assertUnchanged(original: SeededDownload) {
        assertEquals(original.download, dao.getDownloadByChapter(original.saved.id))
        assertEquals(original.saved, db.chapterDao().getChapterByIdSuspend(original.saved.id))
    }

    suspend fun assertCompleted(
        original: SeededDownload,
        sizeBytes: Long,
    ) {
        assertEquals(
            original.download.copy(
                state = DownloadingState.SUCCESS,
                progress = 100,
                sizeBytes = sizeBytes,
                errorMsg = null,
            ),
            dao.getDownloadByChapter(original.saved.id),
        )
        assertEquals(original.saved.copy(isDownloaded = true), db.chapterDao().getChapterByIdSuspend(original.saved.id))
    }
}

private fun chapterToComplete(
    mangaId: Long,
    number: Int,
    paths: List<String>,
) = SavedChapterEntity(
    mangaId = mangaId,
    name = "Chapter $number",
    number = number.toString(),
    url = "https://example.test/chapter/$number",
    date = null,
    isBookmarked = true,
    isRead = true,
    lastReadPage = 7,
    lastReadDate = 9L,
    localImagePaths = paths,
)

internal data class SeededDownload(
    val saved: SavedChapterEntity,
    val download: ChapterDownloadEntity,
)
