package me.manga.kira.data.repository
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import com.russhwolf.settings.MapSettings
import me.manga.kira.core.cache.HttpCacheClearer
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.domain.clean.ChapterCompletionRecords
import me.manga.kira.presentation.features.download.domain.clean.ChapterFinalizer
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import okio.Path
import okio.Path.Companion.toPath
import me.manga.kira.presentation.features.settings.domain.SettingsRepository as LegacySettingsRepository

/** JVM-host proof of the shared mobile callers/real Room writes, not a Desktop writer test. */
internal fun DownloadRecoveryFixture.settingsConverter(writer: CbzWriter): SettingsRepositoryImpl =
    SettingsRepositoryImpl(
        legacy =
            LegacySettingsRepository(SharedPrefsHelper(MapSettings()), DataStoreHelper(MapSettings()), appFileSystem),
        dispatchers = CbzCallerDispatchers,
        dataStore = DataStoreHelper(MapSettings()),
        conversion =
            DownloadedChapterConversion(
                chapters = db.chapterDao(),
                archives = writer,
                manga = db.mangaDao(),
                downloads = dao,
                files = appFileSystem,
                artifacts = artifactRuntime.ownership,
                commits = artifactRuntime.commits,
                operations = downloadOperations,
            ),
        httpCache = HttpCacheClearer { },
    )

internal fun DownloadRecoveryFixture.finalizer(
    writer: CbzWriter,
    dataStore: DataStoreHelper = DataStoreHelper(MapSettings()),
): ChapterFinalizer =
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
        dataStore = dataStore,
        mediaInspector = DesktopPageMediaInspector(system = fs),
    )

internal fun DownloadRecoveryFixture.installValidPages(original: RetainedDownload): Map<Path, ByteArray> =
    original.saved.localImagePaths.associate { stored ->
        val path = stored.toPath()
        fs.write(path) { write(CBZ_CALLER_PNG) }
        path to CBZ_CALLER_PNG
    }

internal fun DownloadRecoveryFixture.installPreviousArchive(
    original: RetainedDownload,
    pages: List<ByteArray> = listOf(CBZ_CALLER_PNG),
): Pair<Path, ByteArray> {
    val path = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / "chapter_${original.saved.id}.cbz"
    val bytes = cbzCallerArchiveBytes(pages)
    fs.write(path) { write(bytes) }
    return path to bytes
}

/** Failure injection only at the writer boundary; the native writer has separate platform tests. */
internal class CbzCallerWriter(
    private val convert: suspend (List<Path>, Long) -> Path,
) : CbzWriter {
    val requests = mutableListOf<Pair<Long, List<Path>>>()

    override suspend fun createCbz(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
    ): Path = error("unused")

    override suspend fun createCbzWithSplitting(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
    ): Path {
        requests += chapterId to imagePaths.toList()
        return convert(imagePaths, chapterId)
    }

    override suspend fun createCbzWithSplittingRetainingSources(
        imagePaths: List<Path>,
        mangaId: Long,
        chapterId: Long,
        quality: Int,
        maxHeight: Int,
        maxMemoryBytes: Long,
    ): Path {
        requests += chapterId to imagePaths.toList()
        return convert(imagePaths, chapterId)
    }
}


internal fun DownloadRecoveryFixture.chapterArtifactsForTest(): ChapterDownloadArtifacts {
    return artifactRuntime.downloads
}
