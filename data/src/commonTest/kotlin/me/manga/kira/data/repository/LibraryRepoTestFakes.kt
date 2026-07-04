package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.ChapterIdUrl
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository

/**
 * Shared no-op [ChapterDao] for `:data` repository tests. Only the id-by-url lookups carry
 * behaviour (configurable url→id map, for the notify path); everything else is inert.
 *
 * [mangaIdByUrl] (2026-07 audit) declares which manga OWNS a url so the mangaId-scoped batch
 * lookup mirrors the real DAO's `WHERE mangaId = :mangaId` — previously the fake ignored the
 * scope, leaving the wrong-manga notification guard untestable. A url with no declared owner is
 * treated as belonging to whichever manga queries it (keeps single-manga tests unchanged).
 */
open class FakeChapterDao(
    private val idsByUrl: Map<String, Long> = emptyMap(),
    private val mangaIdByUrl: Map<String, Long> = emptyMap(),
) : ChapterDao {
    override suspend fun getAllDownloadedChapters(): List<SavedChapterEntity> = emptyList()

    override fun getChaptersByMangaId(mangaId: Long): Flow<List<SavedChapterEntity>> = flowOf(emptyList())

    override suspend fun insertChaptersSafely(chapters: List<SavedChapterEntity>): List<Long> = emptyList()

    override suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long> = emptyList()

    override suspend fun insertAll(chapters: List<SavedChapterEntity>) = Unit

    override suspend fun getChapterIdByUrl(url: String): Long? = idsByUrl[url]

    override suspend fun getChapterIdsByUrlsBatch(urls: List<String>): List<Long> = urls.mapNotNull { idsByUrl[it] }

    override suspend fun getChapterIdUrlPairsBatch(urls: List<String>): List<ChapterIdUrl> =
        urls.mapNotNull { url -> idsByUrl[url]?.let { ChapterIdUrl(id = it, url = url) } }

    override suspend fun getChapterIdUrlPairsForMangaBatch(
        mangaId: Long,
        urls: List<String>,
    ): List<ChapterIdUrl> =
        urls.mapNotNull { url ->
            idsByUrl[url]
                ?.takeIf { mangaIdByUrl.getOrElse(url) { mangaId } == mangaId }
                ?.let { ChapterIdUrl(id = it, url = url) }
        }

    override suspend fun markChapterDownloaded(chapterId: Long) = Unit

    override suspend fun toggleChapterBookmark(chapterId: Long) = Unit

    override suspend fun markChapterAsRead(
        chapterId: Long,
        currentTime: Long,
    ) = Unit

    override suspend fun markChapterIsNew(chapterId: Long) = Unit

    override fun getChapterById(chapterId: Long): Flow<SavedChapterEntity?> = flowOf(null)

    override fun getChapterByUrl(url: String): Flow<SavedChapterEntity?> = flowOf(null)

    override suspend fun getChapterByIdSuspend(chapterId: Long): SavedChapterEntity? = null

    override suspend fun updateChapterLocalPaths(
        chapterId: Long,
        paths: List<String>,
    ) = Unit

    override suspend fun markChaptersNotDownloaded(
        ids: List<Long>,
        emptyList: List<String>,
    ) = Unit

    override suspend fun deleteChapterById(chapterId: Long) = Unit

    override suspend fun markChaptersReadBatch(chapterIds: List<Long>) = Unit

    override suspend fun toggleChaptersReadBatch(chapterIds: List<Long>) = Unit

    override suspend fun toggleChaptersBookmarkBatch(chapterIds: List<Long>) = Unit

    // B6 (#1) interface growth.
    override suspend fun getChaptersByMangaIdR(mangaId: Long): List<SavedChapterEntity> = emptyList()

    override suspend fun updateChapter(chapter: SavedChapterEntity) = Unit
}

/** Shared [NotificationDao] that records inserted notification rows; everything else is inert. */
class RecordingNotificationDao : NotificationDao {
    val inserted = mutableListOf<ChapterNotification>()

    override suspend fun insertNotificationsList(notifications: List<ChapterNotification>): List<Long> {
        inserted += notifications
        return notifications.indices.map { it.toLong() }
    }

    override suspend fun updateMangaImageUrl(
        mangaId: Long,
        newImageUrl: String,
    ) = Unit

    override suspend fun updateNotification(notification: ChapterNotification) = Unit

    override fun getAllNotifications(): Flow<List<ChapterNotification>> = flowOf(emptyList())

    override suspend fun markAllAsRead() = Unit

    override suspend fun deleteNotification(notification: ChapterNotification) = Unit

    override suspend fun deleteAllNotifications() = Unit

    override suspend fun getNotificationByChapterId(chapterId: Long): ChapterNotification? = null

    // B6 (#1) interface growth.
    override suspend fun getNotificationsByApi(api: String): List<ChapterNotification> = emptyList()
}

/** Shared [HistoryDao] that records cover-URL rewrites (updateCoverIfChanged path); rest is inert. */
class RecordingHistoryDao : HistoryDao {
    val coverUpdatesById = mutableListOf<Pair<Long, String>>()
    val coverUpdatesByUrl = mutableListOf<Pair<String, String>>()

    override fun getAllHistory(): Flow<List<HistoryItemD>> = flowOf(emptyList())

    override suspend fun getHistoryByApi(api: String): List<HistoryItemD> = emptyList()

    override suspend fun updateMangaImageUrl(
        mangaId: Long,
        newImageUrl: String,
    ) {
        coverUpdatesById += mangaId to newImageUrl
    }

    override suspend fun updateMangaImageUrlByUrl(
        mangaUrl: String,
        newImageUrl: String,
    ) {
        coverUpdatesByUrl += mangaUrl to newImageUrl
    }

    override suspend fun getHistoryItemByMangaUrl(mangaUrl: String): HistoryItemD? = null

    override suspend fun insertHistory(historyItemD: HistoryItemD) = Unit

    override suspend fun updateHistory(historyItemD: HistoryItemD) = Unit

    override suspend fun deleteHistory(historyItemD: HistoryItemD) = Unit

    override suspend fun deleteAllHistory() = Unit

    override suspend fun updateHistoryItem(
        id: Long,
        chapterUrl: String,
        chapterTitle: String,
        isDownloaded: Boolean,
        localImagePaths: List<String>,
        lastReadDate: LocalDateTime,
        lastReadPage: Int,
        totalPages: Int,
    ) = Unit
}

/** Read-progress fake that records which chapter urls had their resume position cleared. */
class RecordingReadProgressRepository : ReadProgressRepository {
    val cleared = mutableListOf<String>()

    override suspend fun save(
        chapterUrl: String,
        pageIndex: Int,
    ) = Unit

    override suspend fun load(chapterUrl: String): Int? = null

    override suspend fun clear(chapterUrl: String) {
        cleared += chapterUrl
    }
}

/** In-memory [ChapterDownloadDao] exposing only the active-download lookup the purge path uses. */
open class FakeChapterDownloadDao(
    private val activeByManga: Map<Long, List<Long>> = emptyMap(),
) : ChapterDownloadDao {
    override suspend fun getActiveDownloadChapterIdsForManga(
        mangaId: Long,
        runningState: DownloadingState,
        compressingState: DownloadingState,
        queuedState: DownloadingState,
    ): List<Long> = activeByManga[mangaId].orEmpty()

    override suspend fun insert(download: ChapterDownloadEntity): Long = 0L

    override suspend fun insertAll(downloads: List<ChapterDownloadEntity>): List<Long> = emptyList()

    override suspend fun getNextQueuedChapter(queuedState: DownloadingState): ChapterDownloadEntity? = null

    override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? = null

    override suspend fun deleteByChapterId(chapterId: Long) = Unit

    override fun getAllQueuedChapterIds(queuedState: DownloadingState): Flow<List<Long>> = flowOf(emptyList())

    override suspend fun updateStateAndProgress(
        id: Long,
        state: DownloadingState,
        progress: Int,
        errorMsg: String?,
    ) = Unit

    override suspend fun updateProgress(
        id: Long,
        progress: Int,
    ) = Unit

    override suspend fun updateState(
        id: Long,
        state: DownloadingState,
    ) = Unit

    override suspend fun updateStateChId(
        id: Long,
        state: DownloadingState,
    ) = Unit

    override suspend fun claimQueuedAsRunning(
        id: Long,
        runningState: DownloadingState,
        queuedState: DownloadingState,
    ): Int = 1

    override suspend fun setErrorMsg(
        id: Long,
        errorMsg: String?,
    ) = Unit

    override suspend fun markAllRunningOrQueuedAsFailed(
        runningState: DownloadingState,
        queuedState: DownloadingState,
        compressingState: DownloadingState,
        downloadedState: DownloadingState,
        failedState: DownloadingState,
    ) = Unit

    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = flowOf(emptyList())

    override suspend fun countByState(state: DownloadingState): Int = 0

    override suspend fun getQueuedChapters(queuedState: DownloadingState): List<ChapterDownloadEntity> = emptyList()

    override suspend fun reEnqueueInterrupted(
        excludeChapterId: Long,
        runningState: DownloadingState,
        compressingState: DownloadingState,
        queuedState: DownloadingState,
    ) = Unit

    override suspend fun requeueIfInFlight(
        chapterId: Long,
        runningState: DownloadingState,
        compressingState: DownloadingState,
        queuedState: DownloadingState,
    ) = Unit

    override suspend fun getCompletedWithoutSize(successState: DownloadingState): List<ChapterDownloadEntity> = emptyList()

    override suspend fun updateSize(
        id: Long,
        sizeBytes: Long,
    ) = Unit
}

/** [DownloadRepository] fake recording the chapters whose in-flight download was cancelled. */
open class FakeDownloadRepository : DownloadRepository {
    val cancelledRunning = mutableListOf<Pair<Long, Long>>() // (chapterId, mangaId)

    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = flowOf(emptyList())

    override suspend fun enqueueChapterDownload(
        chapter: SavedChapterEntity,
        title: String,
        mangaApi: String,
    ) = Unit

    override suspend fun deleteDownload(chapterId: Long) = Unit

    override suspend fun onCancel(chapterId: Long) = Unit

    override suspend fun cancelARunningChapter(
        chapterId: Long,
        mangaId: Long,
    ) {
        cancelledRunning += chapterId to mangaId
    }

    override suspend fun cancelAllDownloads() = Unit

    override suspend fun reconcileInterruptedDownloads() = Unit
}
