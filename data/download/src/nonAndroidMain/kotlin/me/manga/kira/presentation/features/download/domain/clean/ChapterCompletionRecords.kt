package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.presentation.features.library.domain.LibraryRepository

/**
 * Durable copies of a completed chapter: its queue ledger, saved-library row, and notification row.
 * [ChapterFinalizer] still owns their write order and cancel/delete guards; this group neither makes
 * the separate writes transactional nor changes which row is authoritative.
 */
class ChapterCompletionRecords(
    val downloads: ChapterDownloadDao,
    val library: LibraryRepository,
    val notifications: NotificationDao,
    val artifacts: ChapterDownloadArtifacts,
)
