package me.manga.kira.core.util.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import okio.FileSystem
import okio.Path
import org.junit.Assert.assertEquals
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal const val CHAPTER_NOTIFICATION_CHANNEL = "me.manga.kira.new_chapters"

/** Real generated Room DAOs; individual failure tests delegate only the explicitly faulted call. */
internal class NotificationRoomFixture(private val context: Context) : AutoCloseable {
    private val previousNativeProperties = NATIVE_PROPERTIES.associateWith(System::getProperty)
    private val nativeRoot = Files.createTempDirectory(context.cacheDir.toPath(), "notification-room-").toFile()
    val db: MangaDatabase =
        try {
            stageOwnedNative()
            Room.inMemoryDatabaseBuilder<MangaDatabase>(context)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        } catch (failure: Throwable) {
            runCatching(::releaseOwnedNative).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

    private fun stageOwnedNative() {
        check(System.getProperty("os.name") == "Linux") { "This pinned host fixture requires Linux x64" }
        check(System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        val staged = File(checkNotNull(System.getProperty("kira.notification.sqlite.native")))
        val bytes = staged.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == SQLITE_LINUX_NATIVE_SHA256) { "SQLite JNI input does not match the pinned host runtime" }
        // Each Robolectric sandbox owns a distinct native path; never reuse another loader's copy.
        val nativeDirectory = File(nativeRoot, "native").apply { check(mkdir()) }
        File(nativeDirectory, "libsqliteJni.so").writeBytes(bytes)
        System.setProperty(NATIVE_PATH_PROPERTY, nativeDirectory.absolutePath)
        System.setProperty(NATIVE_NAME_PROPERTY, "libsqliteJni.so")
    }

    fun repository(
        chapters: ChapterDao = db.chapterDao(),
        notifications: NotificationDao = db.notificationDao(),
    ) =
        LibraryRepository(
            mangaDao = db.mangaDao(),
            chapterDao = chapters,
            libraryDeo = db.libraryDeo(),
            notificationDao = notifications,
            historyDao = db.historyDao(),
            fileService = FileService(UnusedFiles),
        )

    fun helper(
        covers: NotificationCovers,
        repository: LibraryRepository = repository(),
        notifications: NotificationDao = db.notificationDao(),
        context: Context = this.context,
    ) = ChapterNotificationHelper(context, notifications, repository, covers)

    fun chapterInserts(insert: suspend (List<SavedChapterEntity>) -> List<Long>): ChapterDao =
        object : ChapterDao by db.chapterDao() {
            override suspend fun insertChaptersSafely(chapters: List<SavedChapterEntity>) = insert(chapters)
        }

    fun notificationInserts(insert: suspend (List<ChapterNotification>) -> List<Long>): NotificationDao =
        object : NotificationDao by db.notificationDao() {
            override suspend fun insertNotificationsList(notifications: List<ChapterNotification>) =
                insert(notifications)
        }

    suspend fun manga(
        label: String = "manga",
        cover: String = "https://cover.example/image.png",
        title: String = label,
    ): SavedMangaEntity {
        val manga =
            SavedMangaEntity(
                api = "notification-fixture",
                language = "en",
                url = "https://manga.example/$label",
                imageUrl = cover,
                title = title,
                description = "fixture",
                status = "Ongoing",
                rating = null,
                genres = emptyList(),
                savedTimestamp = 1,
                lastOpenTimestamp = 1,
            )
        return manga.copy(id = db.libraryDeo().insertManga(manga))
    }

    fun chapters(manga: SavedMangaEntity, count: Int) =
        (1..count).map { number ->
            SavedChapterEntity(
                mangaId = manga.id,
                name = "Chapter $number",
                number = number.toString(),
                url = "${manga.url}/chapter-$number",
                date = LocalDate(2026, 9, 1),
            )
        }

    suspend fun updates() = db.notificationDao().getAllNotifications().first().sortedBy { it.id }

    suspend fun assertStoredWithRealChapterIds(rows: List<ChapterNotification>) {
        assertEquals(rows.sortedBy { it.id }, updates())
        val chapters = db.chapterDao().getChaptersByMangaIdR(rows.first().mangaId).associateBy { it.url }
        assertEquals(rows.size, chapters.size)
        rows.forEach { assertEquals(chapters.getValue(it.chapterUrl).id, it.chapterId) }
    }

    override fun close() {
        // Callers join all Room users first. If close fails, retain native state for the owner.
        db.close()
        releaseOwnedNative()
    }

    private fun releaseOwnedNative() {
        try {
            previousNativeProperties.forEach { (name, value) ->
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        } finally {
            check(nativeRoot.deleteRecursively()) { "Could not remove this fixture's SQLite native copy" }
        }
    }

    private object UnusedFiles : AppFileSystem {
        override val filesDir: Path get() = error("Notification tests must not access files")
        override val cacheDir: Path get() = error("Notification tests must not access cache")

        override fun fileSystem(): FileSystem = error("Notification tests must not delete files")
    }

    private companion object {
        const val NATIVE_PATH_PROPERTY = "androidx.sqlite.driver.bundled.path"
        const val NATIVE_NAME_PROPERTY = "androidx.sqlite.driver.bundled.name"
        val NATIVE_PROPERTIES = listOf(NATIVE_PATH_PROPERTY, NATIVE_NAME_PROPERTY)
        const val SQLITE_LINUX_NATIVE_SHA256 = "3033fdaed2b94c078a0deacb3aa8d011041f4773ff3a83599ac8a979cc8ed380"
    }
}

/** Counts actual in-memory OkHttp requests and native BitmapFactory calls, not wire behavior. */
internal class NotificationNativeCoverWitness {
    val requests = AtomicInteger()
    private val bounds = AtomicInteger()
    private val pixels = AtomicInteger()
    val loader =
        NotificationCoverLoader(
            notificationCoverCalls(requests = requests),
            NotificationCoverDecoder { bytes, offset, length, options ->
                if (options.inJustDecodeBounds) bounds.incrementAndGet() else pixels.incrementAndGet()
                BitmapFactory.decodeByteArray(bytes, offset, length, options)
            },
        )

    fun assertSingleDecode() {
        assertEquals(1, requests.get())
        assertEquals(1, bounds.get())
        assertEquals(1, pixels.get())
    }

    fun assertIdle() {
        assertEquals(0, requests.get())
        assertEquals(0, bounds.get())
        assertEquals(0, pixels.get())
    }
}

internal class MissingNotificationService(context: Context) : ContextWrapper(context) {
    var lookups = 0
        private set

    override fun getSystemService(name: String): Any? {
        if (name == Context.NOTIFICATION_SERVICE) {
            lookups++
            throw SecurityException("fixture_notification_service")
        }
        return super.getSystemService(name)
    }
}

/** Retains ordinary shadow behavior and adds posting order plus a narrow channel-failure seam. */
@Implements(NotificationManager::class)
internal class NotificationPostingShadow : ShadowNotificationManager() {
    val posted = CopyOnWriteArrayList<Pair<Int, Notification>>()
    val rejectedChannels = AtomicInteger()

    @Volatile
    var failingChannel: String? = null

    @Implementation
    override fun notify(id: Int, notification: Notification) {
        super.notify(id, notification)
        posted.add(id to notification)
    }

    @Implementation
    override fun createNotificationChannel(channel: NotificationChannel) {
        if (channel.id == failingChannel) {
            rejectedChannels.incrementAndGet()
            throw SecurityException("fixture_notification_channel")
        }
        super.createNotificationChannel(channel)
    }
}
