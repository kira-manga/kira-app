package me.manga.kira.presentation.features.download.ui.test2

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import me.manga.kira.data.download.selection.DownloadCatalogAdmission
import me.manga.kira.data.download.selection.DownloadCatalogNotReady
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.ReaderProgressConstraints
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.presentation.features.download.data.DownloadingState
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Android Room/KSP and Android BundledSQLiteDriver; the JVM artifact supplies JNI bytes only. */
internal class DownloadWorkerCancellationRows(
    private val storage: CancellationFixtureStorage,
    commit: NativeCommitGate,
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    val operations = DownloadOperationExclusion()
    val catalogAdmission = DownloadWorkerFixtureAdmission(operations)
    private val databasePath = File(storage.root, "worker.db").absolutePath
    private val database =
        lazy {
            Room
                .databaseBuilder<MangaDatabase>(storage.context, databasePath)
                .addCallback(ReaderProgressConstraints)
                .setDriver(CommitObservingDriver(CancellationForeignKeysDriver(BundledSQLiteDriver()), commit))
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(queryDispatcher)
                .build()
        }
    val db: MangaDatabase get() = database.value
    val realDao: ChapterDownloadDao get() = db.chapterDownloadingDao()
    val artifacts: ChapterDownloadArtifacts by lazy {
        val artifactsDao = db.chapterArtifactDao()
        val commits = db.chapterArtifactCommitDao()
        val recovery = ChapterArtifactRecovery(artifactsDao, commits, storage.fileSystem, me.manga.kira.platform.media.AndroidPageMediaInspector())
        ChapterDownloadArtifacts(ChapterArtifacts(artifactsDao, recovery), artifactsDao, commits, recovery, storage.fileSystem)
    }
    private var seeded: DownloadRowsSeed? = null
    val original: DownloadRowsSeed get() = checkNotNull(seeded)
    val manga =
        SavedMangaEntity(
            id = storage.mangaId,
            api = FIXTURE_API,
            language = "en",
            url = "https://example.test/manga/${storage.mangaId}",
            imageUrl = "https://example.test/cover.png",
            title = "Host cancellation fixture",
            description = "",
            status = "ongoing",
            rating = null,
            genres = emptyList(),
            savedTimestamp = 1L,
            lastOpenTimestamp = 2L,
        )

    suspend fun seed() {
        assertEquals(manga.id, db.backupDao().insertMangaRow(manga))
        val saved =
            SavedChapterEntity(
                mangaId = manga.id,
                name = "Chapter 1",
                number = "1",
                url = "https://example.test/chapter/${manga.id}/1",
                date = null,
                isBookmarked = true,
                isRead = true,
                lastReadPage = SAVED_LAST_READ_PAGE,
            )
        val chapterId = db.backupDao().insertChapterRow(saved)
        val download = queuedDownload(saved.copy(id = chapterId))
        seeded = DownloadRowsSeed(saved.copy(id = chapterId), download.copy(id = realDao.insert(download)))
        seedNotification()
        recordClassOrigins()
    }

    private suspend fun seedNotification() {
        db.notificationDao().insertNotificationsList(
            listOf(
                ChapterNotification(
                    api = FIXTURE_API,
                    language = manga.language,
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    mangaImageUrl = manga.imageUrl,
                    mangaUrl = manga.url,
                    chapterId = original.saved.id,
                    chapterNumber = original.saved.number,
                    chapterUrl = original.saved.url,
                ),
            ),
        )
    }

    suspend fun download(): ChapterDownloadEntity = assertNotNull(realDao.getDownloadByChapter(original.saved.id))

    suspend fun saved(): SavedChapterEntity = assertNotNull(db.chapterDao().getChapterByIdSuspend(original.saved.id))

    fun completedDownload(paths: List<String>): ChapterDownloadEntity =
        original.download.copy(
            state = DownloadingState.SUCCESS,
            progress = DOWNLOAD_COMPLETION_PROGRESS,
            errorMsg = null,
            sizeBytes = paths.size * PAGE_PNG.size.toLong(),
        )

    suspend fun assertCompleted(paths: List<String>) {
        assertEquals(completedDownload(paths), download())
        assertEquals(original.saved.copy(localImagePaths = paths, isDownloaded = true), saved())
        val notification = assertNotNull(db.notificationDao().getNotificationByChapterId(original.saved.id))
        assertEquals(paths, notification.localImagePaths)
        assertTrue(notification.isDownloaded)
    }

    fun assertCommittedOnIndependentConnection(
        commit: NativeCommitGate,
        paths: List<String>,
    ) {
        // New physical connection, no old read snapshot and no Room pool or gated execution resource.
        BundledSQLiteDriver().open(databasePath).use { independent ->
            assertNotSame(checkNotNull(commit.writer.get()), independent)
            assertNativeCommittedRows(independent, original, paths)
        }
    }

    private fun recordClassOrigins() {
        val classes =
            listOf(BundledSQLiteDriver::class.java, RoomDatabase::class.java, db.javaClass, realDao.javaClass)
        assertTrue(db.javaClass.name.endsWith("_Impl"))
        assertTrue(realDao.javaClass.name.endsWith("_Impl"))
        classes.forEach(::recordClassOrigin)
        recordOriginalClassResource(BundledSQLiteDriver::class.java, ANDROID_SQLITE_DRIVER_SHA256)
        recordOriginalClassResource(RoomDatabase::class.java, ANDROID_ROOM_DATABASE_SHA256)
    }

    override fun close() {
        if (database.isInitialized()) database.value.close()
    }
}

internal data class DownloadRowsSeed(
    val saved: SavedChapterEntity,
    val download: ChapterDownloadEntity,
)

/** Match production's per-connection FK enforcement, including the typed removal writer. */
private class CancellationForeignKeysDriver(private val actual: SQLiteDriver) : SQLiteDriver by actual {
    override fun open(fileName: String): SQLiteConnection = actual.open(fileName).also { connection ->
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.prepare("PRAGMA foreign_keys").use { statement ->
            check(statement.step() && statement.getLong(0) == 1L)
        }
    }
}

private fun recordClassOrigin(type: Class<*>) {
    val loader = type.classLoader
    val loaderIdentity = loader?.let { "${it.javaClass.name}@${System.identityHashCode(it)}" } ?: "<bootstrap>"
    val domain = type.protectionDomain
    val codeSource = domain?.codeSource
    // Robolectric can define acquired classes with a default domain and no code-source location.
    val location = codeSource?.location?.toExternalForm()
    println(
        "APP75 class-origin=${type.name} loader=$loaderIdentity " +
            "protection-domain-present=${domain != null} code-source-present=${codeSource != null} " +
            "code-source-location=${location ?: "<null>"}",
    )
    if (location != null) {
        check(!location.contains("sqlite-bundled-jvm") && !location.contains("room-runtime-jvm")) {
            "Unexpected JVM class code source for ${type.name}: $location"
        }
    }
}

/** Original resource content is not the origin or bytes of a loaded/instrumented class definition. */
private fun recordOriginalClassResource(
    type: Class<*>,
    expectedSha256: String,
) {
    val resourceName = "/${type.name.replace('.', '/')}.class"
    val resource = checkNotNull(type.getResource(resourceName)) { "Missing original class resource: ${type.name}" }
    val location = resource.toExternalForm()
    check(!location.contains("sqlite-bundled-jvm") && !location.contains("room-runtime-jvm")) {
        "Unexpected JVM original class resource for ${type.name}: $location"
    }
    val bytes = resource.openStream().use { it.readBytes() }
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    println("APP75 original-class-resource=${type.name} url=$location sha256=$sha256")
    assertEquals(expectedSha256, sha256, "Unexpected Android original class resource for ${type.name}")
}

private fun queuedDownload(saved: SavedChapterEntity): ChapterDownloadEntity =
    ChapterDownloadEntity(
        chapterId = saved.id,
        mangaId = saved.mangaId,
        number = saved.number,
        api = FIXTURE_API,
        mangaTitle = "Host cancellation fixture",
        url = saved.url,
        state = DownloadingState.QUEUED,
        progress = 0,
        errorMsg = "previous attempt",
    )

/** Explicit worker-routing control, not selection proof; lifecycle tests retain the same real gate. */
internal class DownloadWorkerFixtureAdmission(private val operations: DownloadOperationExclusion) : DownloadCatalogAdmission {
    val preparations = AtomicInteger()
    val attempts = AtomicInteger()
    var refuse = false

    override suspend fun prepareLocal(): AppResult<Unit> {
        check(currentCoroutineContext()[DownloadOperationExclusion.Operation] == null) { "Preparation must precede operation ownership" }
        preparations.incrementAndGet()
        return AppResult.Success(Unit)
    }

    override suspend fun <T> withAdmittedOperation(block: suspend (DownloadOperationExclusion.Operation) -> T): T =
        operations.withOperation { operation ->
            attempts.incrementAndGet()
            if (refuse) throw DownloadCatalogNotReady()
            block(operation)
        }
}

private const val SAVED_LAST_READ_PAGE = 3
private const val ANDROID_SQLITE_DRIVER_SHA256 = "bd3a4c3dbee4e7ed00eb264b96b9018065efc7370126b14c489b69ff12c62524"
private const val ANDROID_ROOM_DATABASE_SHA256 = "2dadc120546ba35124af7925f4d90843a3a509bb7164557af7742ab183b9d9cc"
