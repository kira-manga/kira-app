package me.manga.kira.data.repository

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.core.result.map
import me.manga.kira.data.local.MangaWriteTransaction
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.mapper.toDomainDetails
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshRequest
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

    fun repository(boundary: MangaWriteTransaction = RoomMangaWriteTransaction(db)) =
        LibraryTestRuntime(db, files, artifactRuntime.ownership, boundary = boundary).repository

    suspend fun parent(label: String = "one"): SavedMangaEntity {
        val manga = SavedMangaEntity(
            api = "source", language = "en", url = "https://current.test/$label",
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

/** Supplies retained metadata explicitly to the typed refresh; there is no persist-only production API. */
internal fun SavedMangaEntity.discoveryRequest(chapters: List<Chapter>) = LibraryRefreshRequest(
    savedIdentity(), FetchedWorkDetails(savedIdentity().locator, toDomainDetails(emptyList()).copy(chapters = chapters)),
)

internal suspend fun LibraryRepositoryImpl.discover(parent: SavedMangaEntity, chapters: List<Chapter>) =
    refresh(listOf(parent.discoveryRequest(chapters)), notify = true).map { it.single().addedChapters }

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

