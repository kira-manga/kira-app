package me.manga.kira.presentation.details

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.ChapterIdResolver
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository

/** No initial scoped emission: tests can inspect the rebind gap before the new Room query returns. */
internal class OwnerDownloads : DownloadsRepository {
    private val streams = mutableMapOf<String, MutableSharedFlow<List<DownloadedChapter>>>()
    private val latest = linkedMapOf<String, List<DownloadedChapter>>()
    private val global = MutableStateFlow<List<DownloadedChapter>>(emptyList())
    val scopedOwners = mutableListOf<Manga>()
    val cancelledOwners = mutableListOf<String>()
    var globalSubscriptions = 0
        private set

    override fun observeAll(): Flow<List<DownloadedChapter>> = global.onStart { globalSubscriptions++ }

    override fun observeForManga(manga: Manga): Flow<List<DownloadedChapter>> =
        stream(manga.url)
            .onStart { scopedOwners += manga }
            .onCompletion { cancelledOwners += manga.url }

    suspend fun publish(
        manga: Manga,
        rows: List<DownloadedChapter>,
    ) {
        latest[manga.url] = rows
        global.value = latest.values.flatten()
        stream(manga.url).emit(rows)
    }

    private fun stream(url: String): MutableSharedFlow<List<DownloadedChapter>> =
        streams.getOrPut(url) {
            MutableSharedFlow()
        }
}

/** Requests are recorded before the gate suspends, proving the real use-case resolver was entered. */
internal class OwnerResolver(
    private val idsByMangaUrl: Map<String, Long>,
) : ChapterIdResolver {
    val singleRequests = mutableListOf<Pair<Manga, String>>()
    val bulkRequests = mutableListOf<Pair<Manga, List<String>>>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun resolveChapterId(
        manga: Manga,
        chapterUrl: String,
    ): Long? {
        singleRequests += manga to chapterUrl
        gate?.await()
        return id(manga, chapterUrl)
    }

    override suspend fun resolveChapterIds(
        manga: Manga,
        chapterUrls: List<String>,
    ): Map<String, Long> {
        bulkRequests += manga to chapterUrls.toList()
        gate?.await()
        return chapterUrls.mapNotNull { url -> id(manga, url)?.let { url to it } }.toMap()
    }

    private fun id(
        manga: Manga,
        chapterUrl: String,
    ): Long? =
        idsByMangaUrl[manga.url]?.let { first ->
            when (chapterUrl) {
                CHAPTER_URL -> first
                SECOND_CHAPTER_URL -> first + 1
                else -> null
            }
        }
}

internal class OwnerDownloadActions : DownloadsActionRepository {
    val runningCancelled = mutableListOf<Pair<Long, Long>>()
    val queuedCancelled = mutableListOf<Long>()
    val enqueued = mutableListOf<Triple<Long, String, String>>()
    val enqueueGates = mutableMapOf<Long, CompletableDeferred<Unit>>()
    val fileDeleteAttempts = mutableListOf<Long>()
    val failingDeletes = mutableSetOf<Long>()
    val deleteOrder = mutableListOf<String>()

    override suspend fun enqueueDownload(
        chapterId: Long,
        mangaTitle: String,
        api: String,
    ): Result<Unit> {
        enqueued += Triple(chapterId, mangaTitle, api)
        enqueueGates[chapterId]?.await()
        return Result.success(Unit)
    }

    override suspend fun cancelDownload(chapterId: Long): Result<Unit> {
        queuedCancelled += chapterId
        return Result.success(Unit)
    }

    override suspend fun cancelRunningDownload(
        chapterId: Long,
        mangaId: Long,
    ): Result<Unit> {
        runningCancelled += chapterId to mangaId
        return Result.success(Unit)
    }

    override suspend fun retryDownload(chapterId: Long): Result<Unit> = error("unused")

    override suspend fun cancelAllDownloads(): Result<Unit> = error("unused")

    override suspend fun deleteDownload(chapterId: Long): Result<Unit> = error("unused")

    override suspend fun deleteDownloadedChapter(chapterId: Long): Result<Unit> {
        fileDeleteAttempts += chapterId
        deleteOrder += "files:$chapterId"
        return if (chapterId in failingDeletes) {
            Result.failure(IllegalStateException("file deletion failed"))
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun reconcileInterrupted(): Result<Unit> = error("unused")
}
