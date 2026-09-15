package me.manga.kira.data.repository

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import okio.FileSystem
import platform.Foundation.NSLock
import platform.Foundation.NSUUID

/** Production Room/KSP construction on an owned Native file; the driver hook only injects cancellation. */
internal class IosRoomCancellationFixture {
    private val system = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kira-room-cancel-${NSUUID().UUIDString}"
    val sql = IosRoomCancellationSql()

    init {
        system.createDirectory(root, mustCreate = true)
    }

    var db: MangaDatabase = openDatabase()
        private set

    suspend fun seed(): IosRoomCancellationSnapshot {
        val candidate =
            SavedMangaEntity(
                api = "native-room",
                language = "en",
                url = "https://manga.test/native-room",
                imageUrl = "https://cover.test/native-room-old.webp",
                title = "Native Room",
                description = "Preserve description",
                author = "Preserve author",
                status = "Ongoing",
                rating = "4.5",
                genres = listOf("Adventure"),
                savedTimestamp = 11,
                lastOpenTimestamp = 22,
                isLiked = true,
                isWatchingNow = true,
            )
        val manga = candidate.copy(id = db.libraryDeo().insertManga(candidate))
        val chapter =
            SavedChapterEntity(
                mangaId = manga.id,
                name = "Chapter 1",
                number = "1",
                url = "${manga.url}/chapter/1",
                date = LocalDate(2026, 9, 1),
                isDownloaded = true,
                isBookmarked = true,
                isRead = true,
                lastReadPage = 7,
                lastReadDate = 17,
                localImagePaths = listOf("owned/page.webp"),
            )
        val chapterId = db.chapterDao().insertChaptersSafely(listOf(chapter)).single()
        val history =
            HistoryItemD(
                api = manga.api,
                language = manga.language,
                mangaId = manga.id,
                mangaUrl = "https://manga.test/old-native-room-url",
                mangaTitle = manga.title,
                mangaImageUrl = manga.imageUrl,
                chapterUrl = chapter.url,
                chapterTitle = chapter.name,
                isDownloaded = true,
                localImagePaths = chapter.localImagePaths,
                lastReadDate = LocalDateTime(2026, 9, 1, 12, 0),
                lastReadPage = chapter.lastReadPage,
                totalPages = 20,
            )
        db.historyDao().insertHistory(history)
        db.historyDao().insertHistory(history.copy(mangaId = 0, mangaUrl = manga.url))
        db.notificationDao().insertNotificationsList(
            listOf(
                ChapterNotification(
                    api = manga.api,
                    language = manga.language,
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    mangaImageUrl = manga.imageUrl,
                    mangaUrl = manga.url,
                    chapterId = chapterId,
                    chapterNumber = chapter.number,
                    chapterUrl = chapter.url,
                    notificationDate = LocalDate(2026, 9, 1),
                    isRead = true,
                    isDownloaded = true,
                    localImagePaths = chapter.localImagePaths,
                ),
            ),
        )
        return snapshot(manga.id)
    }

    suspend fun snapshot(mangaId: Long) =
        IosRoomCancellationSnapshot(
            manga = requireNotNull(db.mangaDao().getMangaById(mangaId)),
            chapters = db.chapterDao().getChaptersByMangaIdR(mangaId).sortedBy { it.id },
            history = db.historyDao().getAllHistory().first().sortedBy { it.id },
            notifications = db.notificationDao().getAllNotifications().first().sortedBy { it.id },
        )

    fun reopen() {
        db.close()
        db = openDatabase()
    }

    fun close() {
        db.close()
        system.deleteRecursively(root)
    }

    private fun openDatabase(): MangaDatabase =
        Room
            .databaseBuilder<MangaDatabase>(name = (root / "cancellation.db").toString())
            .setDriver(sql)
            .setQueryCoroutineContext(platformIoDispatcher)
            .build()
}

internal data class IosRoomCancellationSnapshot(
    val manga: SavedMangaEntity,
    val chapters: List<SavedChapterEntity>,
    val history: List<HistoryItemD>,
    val notifications: List<ChapterNotification>,
) {
    fun withCover(imageUrl: String) =
        copy(
            manga = manga.copy(imageUrl = imageUrl),
            history = history.map { it.copy(mangaImageUrl = imageUrl) },
            notifications = notifications.map { it.copy(mangaImageUrl = imageUrl) },
        )
}

/** Only observes real statement completion and cancels the caller; Room owns every transaction. */
internal class IosRoomCancellationSql(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver by delegate {
    private val lock = NSLock()
    private var cancellation: Pair<Write, Job>? = null
    private var hits = 0
    val cancellationHits: Int get() = locked { hits }

    fun cancelAfterNotificationInsert(job: Job) = arm(Write.NOTIFICATION_INSERT, job)

    fun cancelAfterHistoryUpdate(job: Job) = arm(Write.HISTORY_UPDATE, job)

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        // Match the shipping factory's per-connection FK enforcement, including pooled writers.
        connection.execSQL("PRAGMA foreign_keys = ON")
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                val write =
                    when {
                        sql.trimStart().startsWith("INSERT", true) && sql.contains("`notifications`") ->
                            Write.NOTIFICATION_INSERT
                        sql.trimStart().startsWith("UPDATE history_items", true) -> Write.HISTORY_UPDATE
                        else -> null
                    }
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        val result = statement.step()
                        if (write != null) afterWrite(write)
                        return result
                    }
                }
            }
        }
    }

    private fun arm(write: Write, job: Job) =
        locked {
            check(cancellation == null && hits == 0)
            cancellation = write to job
        }

    private fun afterWrite(write: Write) {
        val caller =
            locked {
                cancellation?.takeIf { it.first == write }?.second?.also {
                    cancellation = null
                    hits++
                }
            }
        // Do not hold the witness lock while invoking arbitrary coroutine cancellation handlers.
        caller?.cancel(CancellationException("cancel_inside_native_room_sql"))
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private enum class Write { NOTIFICATION_INSERT, HISTORY_UPDATE }
}
