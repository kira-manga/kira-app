package me.manga.kira.sources.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.di.launchLibraryRefreshCompletion
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryActivity
import me.manga.kira.domain.model.library.LibraryAffinity
import me.manga.kira.domain.model.library.LibraryChapterCounts
import me.manga.kira.domain.model.library.LibraryRefreshCompleted
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryMetadataRepository
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersAndNotifyUseCase
import me.manga.kira.domain.usecase.library.RefreshAllLibraryChaptersUseCase
import me.manga.kira.sources.contracts.SourceResponse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Actual shared refresh and owned completion; synthetic storage sentinels, not Room/OS delivery proof. */
internal class EngineConsumerRefreshTestFixture(dispatcher: CoroutineDispatcher) {
    private val http = EngineConsumerTestFixtures.detailsHttp(SourceResponse(503, "upstream unavailable"))
    private val original = savedLibraryManga()
    private var saved = listOf(original)
    private val persistenceCalls = mutableListOf<Pair<List<LibraryRefreshRequest>, Boolean>>()
    private val coverCalls = mutableListOf<Triple<SavedWorkIdentity, WorkLocator, String>>()
    private val completions = mutableListOf<Boolean>()
    private var stamps = 0
    private var lastSuccess = "old success"
    private var actualResult: AppResult<LibraryRefreshCompleted>? = null
    private val library =
        object : LibraryRepository by EngineConsumerEmptyLibrary {
            override fun observeLibrary(): Flow<List<LibraryManga>> = flowOf(saved)

            override suspend fun refresh(
                requests: List<LibraryRefreshRequest>,
                notify: Boolean,
            ): AppResult<List<LibraryRefreshReceipt>> {
                persistenceCalls += requests to notify
                saved = emptyList()
                error("Incomplete engine details must not reach the atomic library writer")
            }
        }
    private val metadata =
        object : LibraryMetadataRepository {
            override suspend fun updateCoverIfChanged(
                owner: SavedWorkIdentity,
                fetched: WorkLocator,
                newCoverUrl: String,
            ): AppResult<Unit> {
                coverCalls += Triple(owner, fetched, newCoverUrl)
                saved = listOf(original.copy(manga = original.manga.copy(coverUrl = newCoverUrl)))
                error("Incomplete engine details must not reach the cover writer")
            }
        }
    private val refresh =
        RefreshAllLibraryChaptersUseCase(
            ObserveLibraryUseCase(library),
            EngineConsumerTestFixtures.fetch(http, dispatcher),
            PersistNewChaptersAndNotifyUseCase(library),
            engineConsumerDispatchers(dispatcher),
            metadata,
        )

    suspend fun run(scope: CoroutineScope) {
        val job =
            launchLibraryRefreshCompletion(
                scope = scope,
                refresh = { refresh().also { actualResult = it } },
                stampLastSuccess = {
                    stamps++
                    lastSuccess = "new success"
                },
                onComplete = { completions += it },
            )
        try {
            job.join()
            assertTrue(job.isCompleted)
        } finally {
            withContext(NonCancellable) { withTimeout(5_000) { job.cancelAndJoin() } }
        }
    }

    fun assertFailedWithoutMutations() {
        val failure = assertIs<AppResult.Failure>(actualResult)
        assertEquals(503, assertIs<AppError.Network.Http>(failure.error).statusCode)
        assertEquals(EngineConsumerTestFixtures.detailsRequests, http.requestedUrls)
        assertTrue(persistenceCalls.isEmpty(), "No atomic chapter/Updates writer, including notify=false fallback")
        assertTrue(coverCalls.isEmpty())
        assertEquals(listOf(original), saved, "Retained parent identity, metadata, progress and affinity stay unchanged")
        assertEquals(listOf(false), completions, "Actual failed refresh must settle its owned completion once")
        assertEquals(0, stamps)
        assertEquals("old success", lastSuccess)
    }
}

private fun savedLibraryManga(): LibraryManga {
    val manga = EngineConsumerTestFixtures.manga
    val time = Instant.fromEpochMilliseconds(1_000)
    return LibraryManga(
        manga = manga,
        identity = SavedWorkIdentity(41, WorkLocator(manga.api, manga.url)),
        activity = LibraryActivity(time, time, time),
        counts = LibraryChapterCounts(total = 1, unread = 0, downloaded = 1, bookmarked = 1),
        affinity = LibraryAffinity(isLiked = true, isWatchingNow = false),
    )
}
