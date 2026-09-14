package me.manga.kira.presentation.features.download.ui.test2

/** Existing fixture handles only; this bundle does not own lifecycle or service policy. */
internal class CancellationFixtureServiceInputs(
    val storage: CancellationFixtureStorage,
    val rows: DownloadWorkerCancellationRows,
    val dao: DownloadWorkerCancellationDao,
    val transport: CancellationPageTransport,
    val sender: CompleteSendDispatcher,
)
