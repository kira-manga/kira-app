package me.manga.kira.data.repository

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.FileSystem
import okio.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Owns a real Room file and narrowly faults actual SQL, not the generated transaction wrapper. */
internal class RefreshDiscoveryFixture : AutoCloseable {
    private val root = Files.createTempDirectory("kira-refresh-discovery-")
    val sql = RefreshDiscoverySql()
    var db: MangaDatabase = openDatabase()
        private set
    val files = object : AppFileSystem {
        override val filesDir: Path get() = error("No refresh test may access user files")
        override val cacheDir: Path get() = error("No refresh test may access user cache")
        override fun fileSystem(): FileSystem = error("No refresh test may delete user files")
    }

    var artifactRuntime = ArtifactTestRuntime(db, files)
        private set

    private fun openDatabase() = Room.databaseBuilder<MangaDatabase>(root.resolve("refresh.db").toString())
        .setDriver(sql)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    fun repository(library: LibraryDeo = db.libraryDeo()) = LibraryRepositoryImpl(
        db.mangaDao(), library, db.chapterDao(), db.notificationDao(), db.historyDao(),
        db.chapterDownloadingDao(), FakeDownloadRepository(), FileService(files),
        RecordingReadProgressRepository(), IoDispatchers, artifactRuntime.ownership,
    )

    suspend fun parent(label: String = "one"): SavedMangaEntity {
        val manga = SavedMangaEntity(
            api = "source", language = "en", url = "https://manga.test/$label",
            imageUrl = "https://cover.test/$label", title = "Same title", description = "",
            status = "", rating = null, genres = emptyList(), savedTimestamp = 1, lastOpenTimestamp = 2,
        )
        return manga.copy(id = db.libraryDeo().insertManga(manga))
    }

    suspend fun updates() = db.notificationDao().getAllNotifications().first().sortedBy { it.id }

    fun reopen() {
        db.close()
        db = openDatabase()
        artifactRuntime = ArtifactTestRuntime(db, files)
    }

    fun executeWhileClosed(sql: String) {
        db.close()
        try {
            BundledSQLiteDriver().open(root.resolve("refresh.db").toString()).use { it.execSQL(sql) }
        } finally {
            db = openDatabase()
        artifactRuntime = ArtifactTestRuntime(db, files)
        }
    }

    override fun close() {
        db.close()
        check(root.toFile().deleteRecursively())
    }
}

internal fun refreshChapter(number: String = "1") = Chapter(number, "Chapter $number", "chapter/$number", null, false, false)

internal fun SavedMangaEntity.refreshManga() = Manga(api, language, title, url, imageUrl, null, genres)

private object IoDispatchers : DispatcherProvider {
    override val main = Dispatchers.Default
    override val mainImmediate = Dispatchers.Default
    override val default = Dispatchers.Default
    override val io = Dispatchers.IO
    override val unconfined = Dispatchers.Unconfined
}

internal class RefreshDiscoverySql(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver by delegate {
    val notificationInserts = AtomicInteger()

    @Volatile
    var beforeNotificationInsert: (Int) -> Unit = {}

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        connection.execSQL("PRAGMA foreign_keys = ON")
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                val notificationInsert = sql.trimStart().startsWith("INSERT", true) && sql.contains("`notifications`")
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        if (notificationInsert) beforeNotificationInsert(notificationInserts.incrementAndGet())
                        return statement.step()
                    }
                }
            }
        }
    }
}

/** Releases two repository callers together, before either enters the real Room transaction. */
internal class RefreshDiscoveryBarrier(private val real: LibraryDeo) : LibraryDeo by real {
    private val entered = AtomicInteger()
    private val ready = CompletableDeferred<Unit>()

    override suspend fun persistChapterDiscoveries(
        api: String,
        mangaUrl: String,
        chapters: List<SavedChapterEntity>,
        expectedMangaId: Long?,
    ): List<ChapterNotification> {
        if (entered.incrementAndGet() == 2) ready.complete(Unit)
        ready.await()
        return real.persistChapterDiscoveries(api, mangaUrl, chapters, expectedMangaId)
    }
}
