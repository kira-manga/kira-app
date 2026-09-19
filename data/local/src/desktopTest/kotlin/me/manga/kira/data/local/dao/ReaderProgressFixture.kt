package me.manga.kira.data.local.dao

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import me.manga.kira.data.local.EffectiveSourceSelectionSchema
import me.manga.kira.data.local.MIGRATION_13_14
import me.manga.kira.data.local.MIGRATION_14_15
import me.manga.kira.data.local.MIGRATION_15_16
import me.manga.kira.data.local.MIGRATION_16_17
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.ReaderProgressConstraints
import me.manga.kira.data.local.entity.ReaderLegacyCapture
import me.manga.kira.data.local.entity.ReaderLegacyCleanupEntity
import me.manga.kira.data.local.entity.ReaderLegacyDisposition
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** File-backed generated Room adapters, not simulated progress or transaction implementations. */
internal class ReaderProgressFixture : Closeable {
    private val directory = Files.createTempDirectory("kira-reader-progress-").toFile()
    val file: File = File(directory, "progress.db")
    private var opened: MangaDatabase? = null
    val db: MangaDatabase get() = opened ?: openDatabase().also { opened = it }
    val progress: ReaderProgressDao get() = db.readerProgressDao()
    val cleanup: ReaderLegacyCleanupDao get() = db.readerLegacyCleanupDao()

    fun reopen() {
        opened?.close()
        opened = null
    }

    override fun close() {
        reopen()
        check(directory.deleteRecursively()) { "Reader fixture directory was not removed" }
    }

    suspend fun anchor(
        chapterUrl: String = READER_CHAPTER_URL,
        api: String = READER_API,
        workUrl: String = READER_WORK_URL,
    ): ReaderProgressSnapshot = progress.ensureSnapshot(api, workUrl, chapterUrl)

    suspend fun <T> writer(block: suspend () -> T): T =
        db.useWriterConnection { connection -> connection.immediateTransaction { block() } }

    suspend fun execute(sql: String) {
        db.useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
    }

    suspend fun number(sql: String): Long =
        db.useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                check(statement.step())
                statement.getLong(0)
            }
        }

    suspend fun definitions(type: String): Map<String, String> =
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT name, sql FROM sqlite_master WHERE type = ? ORDER BY name") { statement ->
                statement.bindText(1, type)
                buildMap {
                    while (statement.step()) {
                        if (!statement.isNull(1)) put(statement.getText(0), statement.getText(1))
                    }
                }
            }
        }

    suspend fun savedFamily(): ReaderSavedFamily {
        val draft = metadataManga(url = READER_WORK_URL, api = READER_API)
        val work = draft.copy(id = db.backupDao().insertMangaRow(draft))
        check(work.id > 0)
        val draftChapter = SavedChapterEntity(
            mangaId = work.id,
            name = "Saved chapter",
            number = "1",
            url = READER_CHAPTER_URL,
            date = null,
            lastReadPage = 4,
        )
        val chapter = draftChapter.copy(id = db.backupDao().insertChapterRow(draftChapter))
        check(chapter.id > 0)
        return ReaderSavedFamily(work, chapter)
    }

    private fun openDatabase(): MangaDatabase =
        Room.databaseBuilder<MangaDatabase>(name = file.absolutePath)
            .addMigrations(MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
            .addCallback(ReaderProgressConstraints)
            .addCallback(EffectiveSourceSelectionSchema)
            .setDriver(ReaderProgressForeignKeysDriver(BundledSQLiteDriver()))
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
}

private class ReaderProgressForeignKeysDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection =
        delegate.open(fileName).also { connection ->
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.prepare("PRAGMA foreign_keys").use { statement ->
                check(statement.step() && statement.getLong(0) == 1L)
            }
        }
}

internal data class ReaderSavedFamily(val work: SavedMangaEntity, val chapter: SavedChapterEntity)

internal fun readerReceipt(
    snapshot: ReaderProgressSnapshot,
    key: String = "legacy-key",
    payload: String = "$READER_CHAPTER_URL|7",
    disposition: ReaderLegacyDisposition = ReaderLegacyDisposition.COPIED,
): ReaderLegacyCleanupEntity =
    ReaderLegacyCleanupEntity(
        legacyKey = key,
        capturedPayload = payload,
        chapterId = snapshot.chapterId,
        capture = ReaderLegacyCapture(snapshot.workGeneration, snapshot.chapterGeneration, disposition),
    )

internal const val READER_API = "source"
internal const val READER_WORK_URL = "https://reader.test/work"
internal const val READER_CHAPTER_URL = "$READER_WORK_URL/chapter/one"
