package me.manga.kira.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryChapterNotification
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.contracts.MangaSourceClient

/** Models the facade's real return contracts, not a second implementation of refresh policy. */
internal class LibraryRefreshWorkTestFixtures : LibraryRefreshWorkPort {
    var libraryFlow: Flow<List<SavedMangaEntity>> = flowOf(listOf(refreshManga(1)))
    var missingSource = false
    var fetch: suspend (Manga) -> AppResult<MangaDetails> = { AppResult.Success(refreshDetails(it)) }
    var cover: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var persist: suspend (LibraryRefreshRequest) -> AppResult<LibraryRefreshReceipt> =
        { AppResult.Success(refreshReceipt(it)) }
    var display: suspend (List<LibraryChapterNotification>) -> Unit = {}
    var lastSuccess = "old success"
    var stamps = 0
    var stamp: suspend () -> Unit = {
        lastSuccess = "new success"
        stamps++
    }
    val persistenceCalls = mutableListOf<LibraryRefreshRequest>()
    val persistedNotifications = mutableListOf<List<LibraryChapterNotification>>()
    val displayCalls = mutableListOf<List<LibraryChapterNotification>>()
    val coverCalls = mutableListOf<Triple<SavedWorkIdentity, WorkLocator, String>>()

    override fun library() = libraryFlow

    override suspend fun updateCover(
        owner: SavedWorkIdentity,
        fetched: WorkLocator,
        coverUrl: String,
    ): AppResult<Unit> {
        coverCalls += Triple(owner, fetched, coverUrl)
        return cover()
    }

    override suspend fun persistNotifications(request: LibraryRefreshRequest): AppResult<LibraryRefreshReceipt> {
        persistenceCalls += request
        return persist(request).also {
            if (it is AppResult.Success) persistedNotifications += it.value.notifications
        }
    }

    override suspend fun displayNotifications(notifications: List<LibraryChapterNotification>) {
        displayCalls += notifications
        display(notifications)
    }

    override suspend fun stampLastSuccess() = stamp()

    override fun source(api: String): MangaSourceClient? {
        val apiKey = api
        return if (missingSource) {
            null
        } else {
            object : MangaSourceClient {
                override val api = apiKey

                override suspend fun details(manga: Manga) = fetch(manga)

                override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = error("unused")

                override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = error("unused")

                override suspend fun search(
                    query: String,
                    page: Int,
                    filters: FilterSelections,
                ): AppResult<List<HomeFeedItem>> = error("unused")

                override fun pages(
                    manga: Manga,
                    chapter: Chapter,
                ): Flow<AppResult<List<Page>>> = error("unused")
            }
        }
    }
}

/** Synthetic port payload only, not a Room/IGNORE/identity implementation. */
private fun refreshReceipt(request: LibraryRefreshRequest): LibraryRefreshReceipt {
    val details = request.fetched.details
    val manga = Manga(details.api, details.language, details.title, details.url, details.coverUrl, null, details.genres)
    val notifications = details.chapters.asReversed().mapIndexed { index, chapter ->
        LibraryChapterNotification(index + 101L, index + 201L, manga, chapter)
    }
    return LibraryRefreshReceipt(request.owner, notifications.size, notifications)
}

internal fun refreshManga(id: Long) =
    SavedMangaEntity(
        id = id,
        api = "test",
        language = "en",
        url = "m/$id",
        imageUrl = "old",
        title = "$id",
        description = "",
        status = "",
        rating = null,
        genres = emptyList(),
        savedTimestamp = 0,
        lastOpenTimestamp = 0,
    )

internal fun refreshDetails(
    manga: Manga,
    count: Int = 1,
) = MangaDetails(
    api = manga.api,
    language = manga.language,
    title = manga.title,
    url = manga.url,
    coverUrl = "new",
    description = "",
    author = "",
    rating = "",
    status = "",
    genres = emptyList(),
    chapters = (1..count).map { Chapter("$it", "$it", "${manga.url}/c/$it", null, false, false) },
)

internal fun cancellingRefreshWork(
    stage: Int,
    pause: suspend () -> Nothing,
) = LibraryRefreshWorkTestFixtures().apply {
    when (stage) {
        0 -> libraryFlow = flow { pause() }
        1 -> persist = { pause() }
        2 ->
            stamp = {
                lastSuccess = "committed"
                pause()
            }
        3 -> persist = { pause() }
        4 -> display = { pause() }
        5 -> cover = { pause() }
    }
}

internal fun failingUpdatesRefreshWork(
    expires: Boolean,
    settled: CompletableDeferred<Unit>,
) = LibraryRefreshWorkTestFixtures().apply {
    persist = {
        delay(25_000)
        try {
            if (expires) awaitCancellation() else error("fixture_updates_rejected")
        } finally {
            settled.complete(Unit)
        }
    }
}

/** A bounded cleanup suspension makes owner settlement observable, without a detached job. */
internal suspend fun awaitRefreshCancellation(
    entered: CompletableDeferred<Unit>,
    settled: CompletableDeferred<Unit>,
): Nothing =
    try {
        entered.complete(Unit)
        awaitCancellation()
    } finally {
        withContext(NonCancellable) {
            delay(1)
            settled.complete(Unit)
        }
    }
