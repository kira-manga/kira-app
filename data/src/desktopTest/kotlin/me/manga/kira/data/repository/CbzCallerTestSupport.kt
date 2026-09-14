package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.domain.clean.ChapterFinalizer
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import okio.ByteString.Companion.decodeBase64
import okio.Path
import okio.Path.Companion.toPath
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.manga.kira.presentation.features.settings.domain.SettingsRepository as LegacySettingsRepository

/** JVM-host proof of the shared mobile callers/real Room writes, not a Desktop writer test. */
internal fun DownloadRecoveryFixture.settingsConverter(writer: CbzWriter): SettingsRepositoryImpl =
    SettingsRepositoryImpl(
        legacy = LegacySettingsRepository(SharedPrefsHelper(MapSettings()), DataStoreHelper(MapSettings()), appFileSystem),
        dispatchers = CbzCallerDispatchers,
        dataStore = DataStoreHelper(MapSettings()),
        chapterDao = db.chapterDao(),
        cbzWriter = writer,
        mangaDao = db.mangaDao(),
        chapterDownloadDao = dao,
        appFileSystem = appFileSystem,
    )

internal fun DownloadRecoveryFixture.finalizer(
    writer: CbzWriter,
    dataStore: DataStoreHelper = DataStoreHelper(MapSettings()),
): ChapterFinalizer = ChapterFinalizer(
    dao = dao,
    libraryRepository = LibraryRepository(
        mangaDao = db.mangaDao(),
        chapterDao = db.chapterDao(),
        libraryDeo = db.libraryDeo(),
        notificationDao = db.notificationDao(),
        historyDao = db.historyDao(),
        fileService = FileService(appFileSystem),
    ),
    notificationDao = db.notificationDao(),
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
    val bytes = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            pages.forEachIndexed { index, page ->
                zip.putNextEntry(ZipEntry("page_${index.toString().padStart(4, '0')}.png"))
                zip.write(page)
                zip.closeEntry()
            }
        }
        buffer.toByteArray()
    }
    fs.write(path) { write(bytes) }
    return path to bytes
}

/** Failure injection only at the writer boundary; the native writer has separate platform tests. */
internal class CbzCallerWriter(private val convert: suspend (List<Path>, Long) -> Path) : CbzWriter {
    val requests = mutableListOf<Pair<Long, List<Path>>>()

    override suspend fun createCbz(imagePaths: List<Path>, mangaId: Long, chapterId: Long, quality: Int): Path = error("unused")

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
}

private object CbzCallerDispatchers : DispatcherProvider {
    override val main get() = Dispatchers.Unconfined
    override val mainImmediate get() = Dispatchers.Unconfined
    override val default get() = Dispatchers.Unconfined
    override val io get() = Dispatchers.Unconfined
    override val unconfined get() = Dispatchers.Unconfined
}

private val CBZ_CALLER_PNG: ByteArray = checkNotNull(
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAIAQAAAADsdIMmAAAAC0lEQVR42mNgQAUAABAAAaoZ+IIAAAAASUVORK5CYII="
        .decodeBase64(),
).toByteArray()
