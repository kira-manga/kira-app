package me.manga.kira.data.repository.progress

import me.manga.kira.data.local.dao.BackupDao
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.dao.ReaderLegacyCleanupDao
import me.manga.kira.data.local.dao.ReaderProgressDao

/** Progress aggregate storage. BackupDao contributes existing complete saved-owner reads only. */
class ProgressStorage(
    internal val progress: ReaderProgressDao,
    internal val cleanup: ReaderLegacyCleanupDao,
    internal val manga: MangaDao,
    internal val chapters: ChapterDao,
    internal val savedCatalog: BackupDao,
)
