package me.manga.kira.data.repository

import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.ArtifactReadableUpdate
import me.manga.kira.data.local.dao.ArtifactRepairParent
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.PageInspection

/** One runtime per fixture/database, reset only when the fixture reopens Room. */
internal class ArtifactTestRuntime(
    val dao: ChapterArtifactDao,
    val commits: ChapterArtifactCommitDao,
    files: AppFileSystem,
    mediaInspector: PageMediaInspector = NoConversionInspector,
) {
    val recovery = ChapterArtifactRecovery(dao, commits, files, mediaInspector)
    val ownership = ChapterArtifacts(dao, recovery)
    val downloads = ChapterDownloadArtifacts(ownership, dao, commits, recovery, files)

    constructor(database: MangaDatabase, files: AppFileSystem, inspector: PageMediaInspector = NoConversionInspector) : this(
        database.chapterArtifactDao(), database.chapterArtifactCommitDao(), files, inspector,
    )
}

/** Adapter only for pre-existing fake-based unit suites; race/persistence proofs use real Room. */
internal fun fakeArtifactRuntime(
    files: AppFileSystem = UnusedArtifactFiles,
    chapters: ChapterDao = FakeChapterDao(),
    downloads: ChapterDownloadDao = FakeChapterDownloadDao(),
    mediaInspector: PageMediaInspector = NoConversionInspector,
): ArtifactTestRuntime {
    val records = FakeArtifactRecords(chapters, downloads)
    return ArtifactTestRuntime(records, records, files, mediaInspector)
}

/** Unrelated fixtures must not accidentally claim conversion/archive validation. */
private object NoConversionInspector : PageMediaInspector {
    override fun inspect(encoded: ByteArray): PageInspection = error("This fixture has no conversion inspector")
    override fun inspect(path: okio.Path): PageInspection = error("This fixture has no conversion inspector")
}

private object UnusedArtifactFiles : AppFileSystem {
    override val filesDir: okio.Path get() = error("This unit fixture does not own chapter files")
    override val cacheDir: okio.Path get() = error("This unit fixture does not own cache files")
    override fun fileSystem(): okio.FileSystem = error("This unit fixture does not own a filesystem")
}

private class FakeArtifactRecords(
    private val chapters: ChapterDao,
    private val downloads: ChapterDownloadDao,
) : ChapterArtifactDao, ChapterArtifactCommitDao {
    private val records = mutableMapOf<Long, ChapterArtifactEntity>()
    private val savedWrites = mutableMapOf<Long, SavedChapterEntity>()
    private val downloadWrites = mutableMapOf<Long, ChapterDownloadEntity?>()

    override suspend fun get(chapterId: Long) = records[chapterId]
    override suspend fun artifact(chapterId: Long) = get(chapterId)
    override suspend fun getUnsettled() = records.values.filter { it.token != null }
    override suspend fun getForManga(mangaId: Long) = records.values.filter { it.mangaId == mangaId }
    override suspend fun savedForManga(mangaId: Long) = chapters.getChaptersByMangaIdR(mangaId)
    override suspend fun saved(chapterId: Long) = savedWrites[chapterId] ?: chapters.getChapterByIdSuspend(chapterId)
    override suspend fun mangaApi(mangaId: Long) = "src"
    override suspend fun download(chapterId: Long): ChapterDownloadEntity? =
        if (downloadWrites.containsKey(chapterId)) downloadWrites[chapterId] else downloads.getDownloadByChapter(chapterId)

    override suspend fun insert(record: ChapterArtifactEntity) {
        check(record.chapterId !in records)
        records[record.chapterId] = record
    }

    override suspend fun update(record: ChapterArtifactEntity): Int {
        if (record.chapterId !in records) return 0
        records[record.chapterId] = record
        return 1
    }

    override suspend fun writeArtifact(record: ChapterArtifactEntity) = update(record)

    override suspend fun insertDownload(row: ChapterDownloadEntity): Long = writeDownload(row)

    override suspend fun writeDownload(row: ChapterDownloadEntity): Long {
        val written = row.copy(id = row.id.takeIf { it > 0 } ?: row.chapterId)
        downloadWrites[row.chapterId] = written
        downloads.updateSize(row.chapterId, row.sizeBytes)
        return written.id
    }

    override suspend fun writeSaved(update: ArtifactReadableUpdate): Int {
        val prior = saved(update.id) ?: return 0
        chapters.updateChapterLocalPaths(update.id, update.localImagePaths)
        savedWrites[update.id] = prior.copy(isDownloaded = update.isDownloaded, localImagePaths = update.localImagePaths)
        return 1
    }

    override suspend fun notifications(chapterId: Long, mangaId: Long, chapterUrl: String, api: String) = emptyList<ChapterNotification>()
    override suspend fun writeNotification(update: ArtifactReadableUpdate): Int = error("No notifications in fake fixture")

    override suspend fun repairParent(mangaId: Long): ArtifactRepairParent? = error("Restored-download repair requires real Room")
    override suspend fun repairHistory(api: String, mangaUrl: String, chapterUrl: String): List<HistoryItemD> =
        error("Restored-download repair requires real Room")
    override suspend fun writeHistory(update: ArtifactReadableUpdate): Int = error("Restored-download repair requires real Room")

    override suspend fun removeDownload(chapterId: Long, downloadId: Long): Int {
        if (download(chapterId)?.id != downloadId) return 0
        downloads.deleteByChapterId(chapterId)
        downloadWrites[chapterId] = null
        return 1
    }

    override suspend fun removeTerminalDownload(
        chapterId: Long,
        downloadId: Long,
        terminalState: me.manga.kira.presentation.features.download.data.DownloadingState,
    ): Int = if (download(chapterId)?.state == terminalState) removeDownload(chapterId, downloadId) else 0

    override suspend fun revoke(chapterId: Long, token: String): Int =
        mutate(chapterId, token) { it.copy(retiring = true) }

    override suspend fun confirmPendingPathOwnership(chapterId: Long, token: String): Int {
        if (get(chapterId)?.retiring != false) return 0
        return mutate(chapterId, token) { it.copy(ownsPendingPath = true) }
    }

    override suspend fun release(chapterId: Long, token: String): Int {
        if (get(chapterId)?.retiredRelativePath != null) return 0
        return mutate(chapterId, token) {
            it.copy(token = null, operation = null, retiring = false, downloadId = null,
                pendingRelativePath = null, pendingSizeBytes = null, ownsPendingPath = false, conversionSourceRoster = null)
        }
    }

    override suspend fun releaseRetiredPath(chapterId: Long, token: String, path: String): Int {
        if (get(chapterId)?.retiredRelativePath != path) return 0
        return mutate(chapterId, token) { it.copy(retiredRelativePath = null) }
    }

    override suspend fun finishRemoval(chapterId: Long, token: String): Int {
        val record = get(chapterId) ?: return 0
        if (record.token != token || record.operation != ChapterArtifactOperation.DELETE) return 0
        records.remove(chapterId)
        return 1
    }

    private fun mutate(chapterId: Long, token: String, action: (ChapterArtifactEntity) -> ChapterArtifactEntity): Int {
        val record = records[chapterId]?.takeIf { it.token == token } ?: return 0
        records[chapterId] = action(record)
        return 1
    }
}
