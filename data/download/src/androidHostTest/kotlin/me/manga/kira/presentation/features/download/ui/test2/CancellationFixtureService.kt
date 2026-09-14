package me.manga.kira.presentation.features.download.ui.test2

import me.manga.kira.core.cbz.OptimizedCbzManager
import me.manga.kira.core.util.heap.DeviceTier
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.device.DeviceTierProbe
import me.manga.kira.platform.media.AndroidPageMediaInspector
import me.manga.kira.presentation.features.download.domain.ChapterDownloadPersistence
import me.manga.kira.presentation.features.download.domain.ChapterDownloadService
import me.manga.kira.presentation.features.library.domain.LibraryRepository

/** Keeps the existing five-argument App75 call and its default Android manager unchanged. */
internal fun fixtureDownloadService(
    storage: CancellationFixtureStorage,
    rows: DownloadWorkerCancellationRows,
    dao: DownloadWorkerCancellationDao,
    transport: CancellationPageTransport,
    sender: CompleteSendDispatcher,
): ChapterDownloadService {
    val inputs = CancellationFixtureServiceInputs(storage, rows, dao, transport, sender)
    return fixtureDownloadService(inputs)
}

internal fun fixtureDownloadService(
    inputs: CancellationFixtureServiceInputs,
    manager: OptimizedCbzManager = OptimizedCbzManager(inputs.storage.context, fixtureDeviceTier),
): ChapterDownloadService {
    val files = FileService(inputs.storage.fileSystem)
    val library =
        LibraryRepository(
            inputs.rows.db.mangaDao(),
            inputs.rows.db.chapterDao(),
            inputs.rows.db.libraryDeo(),
            inputs.rows.db.notificationDao(),
            inputs.rows.db.historyDao(),
            files,
        )
    return ChapterDownloadService(
        context = inputs.storage.context,
        persistence = ChapterDownloadPersistence(library, inputs.dao.notifications, inputs.dao, files),
        httpClient = inputs.transport.client,
        optimizedCbzManager = manager,
        dataStoreHelper = inputs.storage.settings,
        mediaInspector = AndroidPageMediaInspector(),
        downloadDispatcher = inputs.sender,
    )
}

private val fixtureDeviceTier =
    object : DeviceTierProbe {
        override fun detect(): DeviceTier = DeviceTier.LOW
    }
