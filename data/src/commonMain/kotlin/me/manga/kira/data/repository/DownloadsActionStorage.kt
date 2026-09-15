package me.manga.kira.data.repository

import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.dao.ChapterArtifactRepairDao
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.platform.filesystem.AppFileSystem

/** Required local collaborators for download actions; all belong to the same database/runtime. */
class DownloadsActionStorage(
    val downloads: ChapterDownloadDao,
    val chapters: ChapterDao,
    val files: AppFileSystem,
    val artifacts: ChapterArtifacts,
    repairs: ChapterArtifactRepairDao,
) {
    internal val missingMetadata = MissingDownloadMetadataRepair(repairs, files, artifacts)
}
