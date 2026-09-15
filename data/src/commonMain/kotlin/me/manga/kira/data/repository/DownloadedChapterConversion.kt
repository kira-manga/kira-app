package me.manga.kira.data.repository

import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.AppFileSystem

/**
 * The I/O dependencies of Settings' existing-download conversion, separate from preferences/cache.
 * Chapters select the loose-page inputs, manga supplies progress titles, downloads protects active
 * work and stores refreshed sizes, and archives/files publish and measure the converted output.
 * The repository retains progress, cancellation, per-chapter error isolation, and all write ordering.
 */
class DownloadedChapterConversion(
    val chapters: ChapterDao,
    val archives: CbzWriter,
    val manga: MangaDao,
    val downloads: ChapterDownloadDao,
    val files: AppFileSystem,
)
