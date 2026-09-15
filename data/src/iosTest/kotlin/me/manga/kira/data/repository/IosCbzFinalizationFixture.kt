package me.manga.kira.data.repository
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.requireValid
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterCompletionRecords
import me.manga.kira.presentation.features.download.domain.clean.ChapterFinalizer
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.use
import platform.Foundation.NSUUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** One private temporary directory and a genuine file-backed Room database, never production paths. */
internal class IosCbzFinalizationFixture {
    val system: FileSystem = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kira-cbz-finalize-${NSUUID().UUIDString}"
    val appFileSystem: AppFileSystem =
        object : AppFileSystem {
            override val filesDir: Path = root / "files"
            override val cacheDir: Path = root / "cache"

            override fun fileSystem(): FileSystem = system
        }

    init {
        system.createDirectories(root / "files")
    }

    var db: MangaDatabase = openDatabase()
        private set
    val dao: ChapterDownloadDao get() = db.chapterDownloadingDao()
    private var artifactRuntime = ArtifactTestRuntime(db, appFileSystem)

    suspend fun seed(): IosCbzChapter {
        val mangaId =
            db.backupDao().insertMangaRow(
                SavedMangaEntity(
                    api = "test",
                    language = "en",
                    url = "https://example.test/manga/cbz",
                    imageUrl = "",
                    title = "CBZ",
                    description = "",
                    status = "ongoing",
                    rating = null,
                    genres = emptyList(),
                    savedTimestamp = 1L,
                    lastOpenTimestamp = 1L,
                ),
            )
        val chapter =
            SavedChapterEntity(
                mangaId = mangaId,
                name = "Chapter",
                number = "1",
                url = "https://example.test/chapter/1",
                date = null,
            )
        val chapterId = db.backupDao().insertChapterRow(chapter)
        val directory = appFileSystem.chapterDir(mangaId, chapterId)
        system.createDirectories(directory)
        val pages =
            listOf(IOS_FINALIZE_LARGE_PNG, IOS_FINALIZE_SMALL_PNG)
                .mapIndexed { index, bytes ->
                    val path = directory / "page_$index.png"
                    system.write(path) { write(bytes) }
                    path to bytes
                }.toMap()
        val saved = chapter.copy(id = chapterId, localImagePaths = pages.keys.map { it.toString() })
        db.backupDao().updateChapterRow(saved)
        val download =
            ChapterDownloadEntity(
                number = "1",
                chapterId = chapterId,
                mangaId = mangaId,
                api = "test",
                url = chapter.url,
                state = DownloadingState.DOWNLOADED,
                progress = 100,
                sizeBytes = pages.values.sumOf { it.size.toLong() },
            )
        return IosCbzChapter(saved, download.copy(id = dao.insert(download)), pages)
    }

    private fun chapterArtifactsForTest(): ChapterDownloadArtifacts {
        return artifactRuntime.downloads
    }

    fun finalizer(writer: CbzWriter): ChapterFinalizer =
        ChapterFinalizer(
            records =
                ChapterCompletionRecords(
                    downloads = dao,
                    library =
                        LibraryRepository(
                            mangaDao = db.mangaDao(),
                            chapterDao = db.chapterDao(),
                            libraryDeo = db.libraryDeo(),
                            notificationDao = db.notificationDao(),
                            historyDao = db.historyDao(),
                            fileService = FileService(appFileSystem),
                        ),
                    notifications = db.notificationDao(),
                    artifacts = chapterArtifactsForTest(),
                ),
            appFileSystem = appFileSystem,
            cbzWriter = writer,
            dataStore = DataStoreHelper(MapSettings()),
            mediaInspector = IosPageMediaInspector(system = system),
        )

    suspend fun saved(original: IosCbzChapter): SavedChapterEntity =
        assertNotNull(
            db.chapterDao().getChapterByIdSuspend(original.saved.id),
        )

    suspend fun download(original: IosCbzChapter): ChapterDownloadEntity =
        assertNotNull(
            dao.getDownloadByChapter(original.saved.id),
        )

    fun archive(original: IosCbzChapter): Path =
        appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / "chapter_${original.saved.id}.cbz"

    fun assertArchive(
        original: IosCbzChapter,
        expected: List<PageImageMetadata>,
    ) {
        system.openZip(archive(original)).use { zip ->
            val entries = zip.list("/".toPath()).sortedBy { it.name }
            assertEquals(original.pages.size, entries.size)
            assertEquals(listOf("page_0000.png", "page_0001.webp"), entries.map { it.name })
            entries.forEachIndexed { index, entry ->
                val bytes = zip.read(entry) { readByteArray() }
                if (index == 0) assertContentEquals(original.pages.values.first(), bytes)
                assertEquals(expected[index], IosPageMediaInspector().inspect(bytes).requireValid())
            }
        }
    }

    fun reopen() {
        db.close()
        db = openDatabase()
        artifactRuntime = ArtifactTestRuntime(db, appFileSystem)
    }

    fun close() {
        db.close()
        system.deleteRecursively(root)
    }

    private fun openDatabase(): MangaDatabase =
        Room
            .databaseBuilder<MangaDatabase>(name = (root / "cbz.db").toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
}

internal data class IosCbzChapter(
    val saved: SavedChapterEntity,
    val download: ChapterDownloadEntity,
    val pages: Map<Path, ByteArray>,
)

private fun decodeFinalizationPng(encoded: String): ByteArray =
    checkNotNull(encoded.trimIndent().replace("\n", "").decodeBase64()).toByteArray()

/** Authored 512x16384 solid 1-bit PNG; 1112 encoded bytes, no user/provider data. */
private val IOS_FINALIZE_LARGE_PNG: ByteArray =
    decodeFinalizationPng(
        """
    iVBORw0KGgoAAAANSUhEUgAAAgAAAEAAAQAAAAAPl40HAAAEH0lEQVR42u3BAQ0AAADCoPdPbQ43oAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgHcDQPAAAdSTSPsAAAAASUVORK5CYII=
    """,
    )

/** Authored 8x8 solid 1-bit PNG; 68 encoded bytes, no user/provider data. */
private val IOS_FINALIZE_SMALL_PNG: ByteArray =
    decodeFinalizationPng(
        """
    iVBORw0KGgoAAAANSUhEUgAAAAgAAAAIAQAAAADsdIMmAAAAC0lEQVR42mNgQAUAABAAAaoZ+IIAAAAASUVORK5CYII=
    """,
    )
