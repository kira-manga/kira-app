package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.platform.filesystem.AppFileSystem

/**
 * The durable recovery state of the background queue: Room rows, manifests, and chapter files.
 * These are the existing injected stores, not a cache or transaction; the engine retains all
 * mutex ownership, atomic manifest publication, and cancellation/deletion decisions.
 */
class BackgroundDownloadStorage(
    val downloads: ChapterDownloadDao,
    val manifests: DownloadManifestStore,
    val files: AppFileSystem,
)
