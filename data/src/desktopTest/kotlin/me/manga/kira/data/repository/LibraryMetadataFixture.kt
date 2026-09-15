package me.manga.kira.data.repository

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.FileSystem
import okio.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import me.manga.kira.presentation.features.library.domain.LibraryRepository as WorkerLibraryRepository

/** Real file-backed Room for the two production metadata entry points; no user files are accessed. */
internal class LibraryMetadataFixture : AutoCloseable {
    private val root = Files.createTempDirectory("kira-library-metadata-")
    val sql = LibraryMetadataSql()
    var db: MangaDatabase = openDatabase()
        private set
    private val files = object : AppFileSystem {
        override val filesDir: Path get() = error("Metadata tests cannot access user files")
        override val cacheDir: Path get() = error("Metadata tests cannot access user cache")
        override fun fileSystem(): FileSystem = error("Metadata tests cannot delete files")
    }

    private fun openDatabase() = Room.databaseBuilder<MangaDatabase>(root.resolve("metadata.db").toString())
        .setDriver(sql)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    private var artifactRuntime = ArtifactTestRuntime(db, files)

    fun shared(mangaDao: MangaDao = db.mangaDao()) = LibraryRepositoryImpl(
        mangaDao, db.libraryDeo(), db.chapterDao(), db.notificationDao(), db.historyDao(),
        db.chapterDownloadingDao(), FakeDownloadRepository(), FileService(files),
        RecordingReadProgressRepository(), MetadataDispatchers, artifactRuntime.ownership,
    )

    fun worker() = WorkerLibraryRepository(
        db.mangaDao(), db.chapterDao(), db.libraryDeo(), db.notificationDao(), db.historyDao(), FileService(files),
    )

    suspend fun seed(): LibraryMetadataSnapshot {
        val candidate = SavedMangaEntity(
            api = "source", language = "en", title = "Metadata", url = "https://manga.test/metadata",
            imageUrl = OLD_COVER, description = "Keep description", author = "Keep author", status = "Ongoing",
            rating = "4.5", genres = listOf("Adventure"), savedTimestamp = 11, lastOpenTimestamp = 22,
        )
        val manga = candidate.copy(id = db.libraryDeo().insertManga(candidate))
        val chapter = SavedChapterEntity(mangaId = manga.id, name = "One", number = "1", url = "chapter/1")
        val chapterId = db.chapterDao().insertChaptersSafely(listOf(chapter)).single()
        db.historyDao().insertHistory(metadataHistory(manga, manga.id, "old-parent-url"))
        db.historyDao().insertHistory(metadataHistory(manga, 0L, manga.url))
        db.notificationDao().insertNotificationsList(listOf(ChapterNotification(
            api = manga.api, language = manga.language, mangaId = manga.id, mangaTitle = manga.title,
            mangaImageUrl = manga.imageUrl, mangaUrl = manga.url, chapterId = chapterId,
            chapterNumber = "1", chapterUrl = chapter.url, notificationDate = LocalDate(2026, 9, 1),
            isRead = true, isDownloaded = true, localImagePaths = listOf("owned/page.webp"),
        )))
        return snapshot(manga.id)
    }

    suspend fun snapshot(id: Long) = LibraryMetadataSnapshot(
        requireNotNull(db.mangaDao().getMangaById(id)),
        db.historyDao().getAllHistory().first().sortedBy { it.id },
        db.notificationDao().getAllNotifications().first().sortedBy { it.id },
    )

    fun reopen() {
        db.close()
        db = openDatabase()
        artifactRuntime = ArtifactTestRuntime(db, files)
    }

    override fun close() {
        db.close()
        check(root.toFile().deleteRecursively())
    }
}

internal const val OLD_COVER = "https://cover.test/old.webp"
internal const val NEW_COVER = "https://cover.test/new.webp"

internal data class LibraryMetadataSnapshot(
    val manga: SavedMangaEntity,
    val history: List<HistoryItemD>,
    val notifications: List<ChapterNotification>,
) {
    fun withCover(imageUrl: String) = copy(
        manga = manga.copy(imageUrl = imageUrl),
        history = history.map { it.copy(mangaImageUrl = imageUrl) },
        notifications = notifications.map { it.copy(mangaImageUrl = imageUrl) },
    )
}

private fun metadataHistory(manga: SavedMangaEntity, mangaId: Long, mangaUrl: String) = HistoryItemD(
    api = manga.api, language = manga.language, mangaId = mangaId, mangaUrl = mangaUrl,
    mangaTitle = manga.title, mangaImageUrl = manga.imageUrl, chapterUrl = "chapter/1", chapterTitle = "One",
    isDownloaded = true, localImagePaths = listOf("owned/page.webp"),
    lastReadDate = LocalDateTime(2026, 9, 1, 12, 0), lastReadPage = 7, totalPages = 20,
)

private object MetadataDispatchers : DispatcherProvider {
    override val main = Dispatchers.Default
    override val mainImmediate = Dispatchers.Default
    override val default = Dispatchers.Default
    override val io = Dispatchers.IO
    override val unconfined = Dispatchers.Unconfined
}

/** Faults after the actual generated history UPDATE, inside Room's transaction. */
internal class LibraryMetadataSql(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver by delegate {
    val historyWrites = AtomicInteger()
    @Volatile
    var afterHistoryWrite: () -> Unit = {}

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        connection.execSQL("PRAGMA foreign_keys = ON")
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                val historyWrite = sql.trimStart().startsWith("UPDATE history_items", true)
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        val result = statement.step()
                        if (historyWrite) {
                            historyWrites.incrementAndGet()
                            afterHistoryWrite()
                        }
                        return result
                    }
                }
            }
        }
    }
}
