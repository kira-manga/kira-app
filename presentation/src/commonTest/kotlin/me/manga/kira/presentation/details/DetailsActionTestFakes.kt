package me.manga.kira.presentation.details

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.domain.repository.ChapterBookmarkRepository
import me.manga.kira.domain.repository.ChapterDeletionRepository
import me.manga.kira.domain.repository.ChapterIdResolver
import me.manga.kira.domain.repository.ChapterNewBadgeRepository
import me.manga.kira.domain.repository.CompressionDeferralRepository
import me.manga.kira.domain.repository.ConnectivityRepository
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.repository.MarkChapterReadRepository

internal object NoAdultClassifier : AdultContentClassifier {
    override fun isAdultContent(
        api: String,
        genres: List<String>,
    ): Boolean = false
}

/** Records VM inputs only; the real matching policy is tested at the composition root. */
internal class RecordingAdultClassifier(
    private val adultInput: Pair<String, List<String>>,
) : AdultContentClassifier {
    val inputs = mutableListOf<Pair<String, List<String>>>()

    override fun isAdultContent(
        api: String,
        genres: List<String>,
    ): Boolean {
        val input = api to genres.toList()
        inputs += input
        return input == adultInput
    }
}

internal object NullChapterIdResolver : ChapterIdResolver {
    override suspend fun resolveChapterId(
        manga: Manga,
        chapterUrl: String,
    ): Long? = null

    override suspend fun resolveChapterIds(
        manga: Manga,
        chapterUrls: List<String>,
    ): Map<String, Long> = emptyMap()
}

internal class FixedChapterIdResolver(
    private val id: Long,
) : ChapterIdResolver {
    override suspend fun resolveChapterId(
        manga: Manga,
        chapterUrl: String,
    ): Long = id

    override suspend fun resolveChapterIds(
        manga: Manga,
        chapterUrls: List<String>,
    ): Map<String, Long> =
        chapterUrls.associateWith {
            id
        }
}

internal object NoopDownloadsActionRepository : DownloadsActionRepository {
    override suspend fun enqueueDownload(
        chapterId: Long,
        mangaTitle: String,
        api: String,
    ) = Result.success(Unit)

    override suspend fun retryDownload(chapterId: Long) = Result.success(Unit)

    override suspend fun cancelDownload(chapterId: Long) = Result.success(Unit)

    override suspend fun cancelRunningDownload(
        chapterId: Long,
        mangaId: Long,
    ) = Result.success(Unit)

    override suspend fun cancelAllDownloads() = Result.success(Unit)

    override suspend fun deleteDownload(chapterId: Long) = Result.success(Unit)

    override suspend fun deleteDownloadedChapter(chapterId: Long) = Result.success(Unit)

    override suspend fun reconcileInterrupted() = Result.success(Unit)
}

/** Records real use-case boundary calls without feeding synthetic completion back into the flows. */
internal class RecordingDownloadsActionRepository : DownloadsActionRepository by NoopDownloadsActionRepository {
    val enqueued = mutableListOf<Triple<Long, String, String>>()
    val deletedChapters = mutableListOf<Long>()
    var beforeEnqueue: suspend () -> Unit = {}

    override suspend fun enqueueDownload(
        chapterId: Long,
        mangaTitle: String,
        api: String,
    ): Result<Unit> {
        beforeEnqueue()
        enqueued += Triple(chapterId, mangaTitle, api)
        return Result.success(Unit)
    }

    override suspend fun deleteDownloadedChapter(chapterId: Long): Result<Unit> {
        deletedChapters += chapterId
        return Result.success(Unit)
    }
}

internal object NoopMarkChapterReadRepository : MarkChapterReadRepository {
    override suspend fun markRead(
        manga: Manga,
        chapterUrl: String,
    ) = Unit

    override suspend fun toggleRead(
        manga: Manga,
        chapterUrl: String,
    ) = Unit

    override suspend fun markRead(
        manga: Manga,
        chapterUrls: List<String>,
    ) = Unit
}

/** Records all read mutations and keeps bulk batches distinct from single/toggle calls. */
internal class RecordingMarkChapterReadRepository : MarkChapterReadRepository {
    val read = mutableListOf<Pair<Manga, String>>()
    val bulkReads = mutableListOf<Pair<Manga, List<String>>>()

    override suspend fun markRead(
        manga: Manga,
        chapterUrl: String,
    ) {
        read += manga to chapterUrl
    }

    override suspend fun toggleRead(
        manga: Manga,
        chapterUrl: String,
    ) {
        read += manga to chapterUrl
    }

    override suspend fun markRead(
        manga: Manga,
        chapterUrls: List<String>,
    ) {
        bulkReads += manga to chapterUrls.toList()
        read += chapterUrls.map { manga to it }
    }
}

internal object NoopChapterBookmarkRepository : ChapterBookmarkRepository {
    override fun observeBookmark(
        manga: Manga,
        chapterUrl: String,
    ): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun toggleBookmark(
        manga: Manga,
        chapterUrl: String,
    ): Boolean = true

    override suspend fun toggleBookmark(
        manga: Manga,
        chapterUrls: List<String>,
    ) = Unit
}

/** #4: drives DetailsState.isOnline. Default online so it never blocks the existing tests. */
internal class FakeConnectivityRepository(
    online: Boolean = true,
) : ConnectivityRepository {
    private val flow = MutableStateFlow(online)

    override fun observeIsOnline(): Flow<Boolean> = flow
}

internal class FakeCompressionDeferralRepository(
    private val deferred: Flow<Boolean>,
) : CompressionDeferralRepository {
    override fun observeLowPowerDeferral(): Flow<Boolean> = deferred
}

/** #11: records manga_open events so a test can assert it fires once per identity. */
internal class RecordingAnalyticsPort : AnalyticsPort {
    val mangaOpens = mutableListOf<Pair<String, String>>()
    var appOpens = 0
        private set

    override fun logAppOpen() {
        appOpens++
    }

    override fun logMangaOpen(
        api: String,
        title: String,
        sourceScreen: String,
    ) {
        mangaOpens += api to title
    }
}

internal class RecordingChapterNewBadgeRepository : ChapterNewBadgeRepository {
    val cleared = mutableListOf<Pair<Manga, String>>()

    override suspend fun clearNew(
        manga: Manga,
        chapterUrl: String,
    ) {
        cleared += manga to chapterUrl
    }
}

internal class RecordingChapterDeletionRepository : ChapterDeletionRepository {
    val deleted = mutableListOf<Long>()

    override suspend fun deleteChapter(chapterId: Long) {
        deleted += chapterId
    }
}

internal object EmptyDownloadsRepository : DownloadsRepository {
    override fun observeAll(): Flow<List<DownloadedChapter>> = MutableStateFlow(emptyList())

    override fun observeForManga(manga: Manga): Flow<List<DownloadedChapter>> = MutableStateFlow(emptyList())
}

/**
 * PFIX-DLPROGRESS: a downloads repo whose emissions a test can push through `rows` to mimic the
 * reactive Room `chapter_downloads` flow ticking (each `updateProgress` / state transition the
 * worker writes re-emits here). Used to verify the Details row reflects live state+progress.
 */
internal class FakeDownloadsRepository : DownloadsRepository {
    val rows = MutableStateFlow<List<DownloadedChapter>>(emptyList())

    override fun observeAll(): Flow<List<DownloadedChapter>> = rows

    override fun observeForManga(manga: Manga): Flow<List<DownloadedChapter>> =
        if (manga.url == "https://x/naruto") rows else MutableStateFlow(emptyList())
}

/**
 * Resolves a controlled set of chapter `url` → Room `id` mappings (unknown urls → null),
 * including both the single and batched resolution used by the real enqueue use cases.
 */
internal class MapChapterIdResolver(
    private val byUrl: Map<String, Long>,
) : ChapterIdResolver {
    override suspend fun resolveChapterId(
        manga: Manga,
        chapterUrl: String,
    ): Long? = byUrl[chapterUrl]

    override suspend fun resolveChapterIds(
        manga: Manga,
        chapterUrls: List<String>,
    ): Map<String, Long> = chapterUrls.mapNotNull { url -> byUrl[url]?.let { url to it } }.toMap()
}

