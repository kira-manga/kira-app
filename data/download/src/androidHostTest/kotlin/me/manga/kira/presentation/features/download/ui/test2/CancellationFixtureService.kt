package me.manga.kira.presentation.features.download.ui.test2

import me.manga.kira.core.cbz.OptimizedCbzManager
import me.manga.kira.core.util.heap.DeviceTier
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.device.DeviceTierProbe
import me.manga.kira.presentation.features.download.domain.ChapterDownloadPersistence
import me.manga.kira.presentation.features.download.domain.ChapterDownloadService
import me.manga.kira.presentation.features.library.domain.LibraryRepository

internal fun fixtureDownloadService(
    storage: CancellationFixtureStorage,
    rows: DownloadWorkerCancellationRows,
    dao: DownloadWorkerCancellationDao,
    transport: CancellationPageTransport,
    sender: CompleteSendDispatcher,
): ChapterDownloadService {
    val files = FileService(storage.fileSystem)
    val library =
        LibraryRepository(
            rows.db.mangaDao(),
            rows.db.chapterDao(),
            rows.db.libraryDeo(),
            rows.db.notificationDao(),
            rows.db.historyDao(),
            files,
        )
    return ChapterDownloadService(
        context = storage.context,
        persistence = ChapterDownloadPersistence(library, dao.notifications, dao, files),
        httpClient = transport.client,
        optimizedCbzManager = OptimizedCbzManager(storage.context, fixtureDeviceTier),
        dataStoreHelper = storage.settings,
        downloadDispatcher = sender,
    )
}

private val fixtureDeviceTier =
    object : DeviceTierProbe {
        override fun detect(): DeviceTier = DeviceTier.LOW
    }
