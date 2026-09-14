package me.manga.kira.presentation.features.download.domain

import me.manga.kira.core.cbz.OptimizedCbzManager
import me.manga.kira.platform.storage.DataStoreHelper

/**
 * Android's optional chapter-archive stage: the existing writer and its live format preference.
 * Holds the injected instances without caching the preference, starting work, or changing fallback.
 */
class ChapterDownloadArchive(
    val manager: OptimizedCbzManager,
    val preferences: DataStoreHelper,
)
