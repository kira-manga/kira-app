package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.MangaDetailsRepositoryImpl
import me.manga.kira.di.launchLibraryRefreshCompletion
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.library.LibraryRefreshCompleted
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersAndNotifyUseCase
import me.manga.kira.domain.usecase.library.RefreshAllLibraryChaptersUseCase
import me.manga.kira.sources.contracts.SourceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Real Engine-to-owned-completion composition; persistence ports are not Room or native BGTask proof. */
class GenericSourceLibraryRefreshConsumerTest {
    @Test
    fun requiredPageFailure_settlesFalseWithoutPersistenceCoverOrSuccessStamp() =
        runTest {
            val fixture = RefreshConsumerFixture(this, SourceResponse(503, "upstream unavailable"))
            val previousChapters = fixture.library.savedChapters.toList()

            fixture.run()

            val failure = assertIs<AppResult.Failure>(fixture.result)
            assertEquals(503, assertIs<AppError.Network.Http>(failure.error).statusCode)
            assertEquals(1, fixture.library.snapshotReads)
            assertEquals(EngineConsumerTestFixtures.detailsRequests, fixture.http.requestedUrls)
            assertEquals(previousChapters, fixture.library.savedChapters)
            assertTrue(fixture.library.persistenceCalls.isEmpty())
            assertTrue(fixture.library.coverCalls.isEmpty())
            assertEquals(0, fixture.stamps)
            assertEquals("old success", fixture.lastSuccess)
            assertEquals(listOf(false), fixture.completions)
            assertEquals(listOf("complete:false"), fixture.events)
        }

    @Test
    fun terminalPageSuccess_completesWholeSnapshotAndStampsBeforeSuccessCallback() =
        runTest {
            val fixture = RefreshConsumerFixture(this, EngineConsumerTestFixtures.terminalPage())

            fixture.run()

            val completed = assertIs<AppResult.Success<LibraryRefreshCompleted>>(fixture.result).value
            assertEquals(LibraryRefreshCompleted(1, 2), completed)
            assertEquals(1, fixture.library.snapshotReads)
            assertEquals(EngineConsumerTestFixtures.detailsRequests, fixture.http.requestedUrls)
            val (manga, chapters) = fixture.library.persistenceCalls.single()
            assertEquals(EngineConsumerTestFixtures.manga, manga)
            assertEquals(listOf("1", "2"), chapters.map { it.number })
            assertEquals(
                listOf("chapter-1", "chapter-2").map { "${EngineConsumerTestFixtures.MANGA_URL}/$it" },
                chapters.map { it.url },
            )
            assertEquals(listOf("0", "1", "2"), fixture.library.savedChapters.map { it.number })
            assertEquals(listOf("${EngineConsumerTestFixtures.BASE}/new.jpg"), fixture.library.coverCalls)
            assertEquals(1, fixture.stamps)
            assertEquals("new success", fixture.lastSuccess)
            assertEquals(listOf(true), fixture.completions)
            assertEquals(listOf("cover", "persist", "stamp", "complete:true"), fixture.events)
        }
}

private class RefreshConsumerFixture(
    private val owner: TestScope,
    pageTwo: SourceResponse,
) {
    val http = EngineConsumerTestFixtures.detailsHttp(pageTwo)
    val events = mutableListOf<String>()
    val library = RefreshConsumerLibrary(events)
    val completions = mutableListOf<Boolean>()
    var result: AppResult<LibraryRefreshCompleted>? = null
    var stamps = 0
    var lastSuccess = "old success"

    private val dispatchers = engineConsumerDispatchers(StandardTestDispatcher(owner.testScheduler))
    private val refresh =
        RefreshAllLibraryChaptersUseCase(
            observeLibrary = ObserveLibraryUseCase(library),
            fetchDetails =
                FetchMangaDetailsUseCase(
                    MangaDetailsRepositoryImpl(dispatchers, EngineConsumerTestFixtures.registry(http)),
                ),
            persistAndNotify = PersistNewChaptersAndNotifyUseCase(library),
            libraryRepo = library,
            dispatchers = dispatchers,
        )

    suspend fun run() {
        val job =
            launchLibraryRefreshCompletion(
                scope = owner,
                refresh = { refresh().also { result = it } },
                stampLastSuccess = {
                    stamps++
                    lastSuccess = "new success"
                    events += "stamp"
                },
                onComplete = {
                    completions += it
                    events += "complete:$it"
                },
            )
        job.join()
        assertTrue(job.isCompleted)
    }
}

/** Records the existing library ports; does not duplicate refresh accounting or persistence policy. */
private class RefreshConsumerLibrary(private val events: MutableList<String>) : LibraryRepository {
    private val manga = EngineConsumerTestFixtures.manga
    private val existing =
        Chapter(
            number = "0",
            name = "Previously saved chapter",
            url = "${manga.url}/chapter-0",
            date = null,
            isDownloaded = false,
            isBookmarked = true,
            isRead = true,
        )
    val savedChapters = mutableListOf(existing)
    val persistenceCalls = mutableListOf<Pair<Manga, List<Chapter>>>()
    val coverCalls = mutableListOf<String>()
    var snapshotReads = 0

    override fun observeLibrary(): Flow<List<LibraryManga>> {
        snapshotReads++
        val epoch = Instant.fromEpochMilliseconds(0)
        return flowOf(
            listOf(
                LibraryManga(
                    manga = manga,
                    addedAt = epoch,
                    unreadCount = 0,
                    hasDownloads = false,
                    totalChapters = 1,
                    lastReadAt = epoch,
                    lastOpenedAt = epoch,
                    bookmarkedCount = 1,
                    downloadedCount = 0,
                    isLiked = false,
                    isWatchingNow = false,
                ),
            ),
        )
    }

    override suspend fun persistNewChaptersAndNotify(manga: Manga, fetched: List<Chapter>): AppResult<Int> {
        persistenceCalls += manga to fetched.toList()
        savedChapters += fetched
        events += "persist"
        return AppResult.Success(fetched.size)
    }

    override suspend fun updateCoverIfChanged(
        api: String,
        language: String,
        title: String,
        newCoverUrl: String,
    ): AppResult<Unit> {
        coverCalls += newCoverUrl
        events += "cover"
        return AppResult.Success(Unit)
    }

    override fun observeIsInLibrary(api: String, language: String, title: String): Flow<Boolean> = unexpected()

    override suspend fun get(api: String, language: String, title: String): AppResult<LibraryManga?> = unexpected()

    override suspend fun addToLibrary(details: MangaDetails): AppResult<Unit> = unexpected()

    override suspend fun persistNewChapters(
        api: String,
        mangaUrl: String,
        fetched: List<Chapter>,
    ): AppResult<Int> = unexpected()

    override suspend fun removeFromLibrary(api: String, language: String, title: String): AppResult<Unit> = unexpected()

    override suspend fun removeAllFromLibrary(keys: List<MangaKey>): AppResult<Int> = unexpected()

    override suspend fun toggleLiked(key: MangaKey): AppResult<Unit> = unexpected()

    override suspend fun toggleWatchingNow(key: MangaKey): AppResult<Unit> = unexpected()

    override suspend fun markOpened(api: String, language: String, title: String): AppResult<Unit> = unexpected()

    private fun unexpected(): Nothing = error("Operation outside the shared-refresh consumer fixture")
}
