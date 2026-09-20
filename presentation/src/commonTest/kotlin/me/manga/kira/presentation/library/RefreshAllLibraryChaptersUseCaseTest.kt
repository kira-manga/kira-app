package me.manga.kira.presentation.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryMetadataRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.presentation.testing.FakeLibraryRepository
import me.manga.kira.domain.model.library.LibraryRefreshCompleted
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.presentation.testing.sampleLibraryManga
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real shared orchestration with virtual deadlines; no platform scheduler or database claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshAllLibraryChaptersUseCaseTest {
    @Test
    fun refreshesEverySavedManga_andPersistsNewChapters() =
        runTest {
            val lib = library("A", "B")
            val result =
                useCase(lib) { manga ->
                    AppResult.Success(
                        details(manga, if (manga.title == "A") listOf(ch("1"), ch("2")) else listOf(ch("1"))),
                    )
                }()
            assertEquals(
                LibraryRefreshCompleted(2, 3),
                assertIs<AppResult.Success<LibraryRefreshCompleted>>(result).value,
            )
            assertEquals(listOf("refresh(1,notify=true)", "refresh(1,notify=true)"), lib.calls)
            assertEquals(listOf(1L, 2L), lib.refreshRequests.flatten().map { it.owner.id })
            assertEquals(listOf(2, 1), lib.refreshRequests.flatten().map { it.fetched.details.chapters.size })
        }

    @Test
    fun emptyLibrary_isZeroAndDoesNothing() =
        runTest {
            val lib = library()
            val result = useCase(lib)()
            assertEquals(
                LibraryRefreshCompleted(0, 0),
                assertIs<AppResult.Success<LibraryRefreshCompleted>>(result).value,
            )
            assertTrue(lib.refreshRequests.isEmpty())
        }

    @Test
    fun allFailedAndPartialPasses_areFailures_butKeepSuccessfulSiblingWrites() =
        runTest {
            val error = AppError.Network.Http(503)
            for (successfulSibling in listOf(false, true)) {
                val lib = library("A", "B")
                val result =
                    useCase(lib) { manga ->
                        if (successfulSibling && manga.title == "A") {
                            AppResult.Success(details(manga, listOf(ch("1"))))
                        } else {
                            AppResult.Failure(error)
                        }
                    }()
                assertEquals(error, assertIs<AppResult.Failure>(result).error)
                assertEquals(
                    if (successfulSibling) 1 else 0,
                    lib.refreshRequests.size,
                )
            }
        }

    @Test
    fun knownCombinedPersistenceFailure_doesNotBecomeZeroChapterSuccess() =
        runTest {
            val error = AppError.Storage.Io()
            val lib =
                object : LibraryRepository by library("A") {
                    override suspend fun refresh(
                        requests: List<LibraryRefreshRequest>,
                        notify: Boolean,
                    ): AppResult<List<LibraryRefreshReceipt>> = AppResult.Failure(error)
                }
            assertEquals(error, assertIs<AppResult.Failure>(useCase(lib)()).error)
        }

    @Test
    fun coverFailure_isBestEffort_andNonemptyZeroNewChaptersStillCompletes() =
        runTest {
            val base = library("A")
            val covers = mutableListOf<Pair<SavedWorkIdentity, WorkLocator>>()
            val metadata = object : LibraryMetadataRepository {
                override suspend fun updateCoverIfChanged(
                    owner: SavedWorkIdentity,
                    fetched: WorkLocator,
                    newCoverUrl: String,
                ): AppResult<Unit> {
                    covers += owner to fetched
                    return AppResult.Failure(AppError.Storage.Io())
                }
            }
            val result = useCase(base, libraryMetadata = metadata)()
            assertEquals(
                LibraryRefreshCompleted(1, 0),
                assertIs<AppResult.Success<LibraryRefreshCompleted>>(result).value,
            )
            val request = base.refreshRequests.single().single()
            assertEquals(listOf(request.owner to request.fetched.requested), covers)
            assertEquals("", request.fetched.details.coverUrl, "optional cover failure cannot re-enter the atomic writer")
            assertTrue(request.fetched.details.chapters.isEmpty())
            assertEquals(listOf("refresh(1,notify=true)"), base.calls)
        }

    @Test
    fun unreadLibrary_isFailure_notEmptySuccess() =
        runTest {
            for (timeout in listOf(false, true)) {
                val lib =
                    object : LibraryRepository by library() {
                        override fun observeLibrary(): Flow<List<LibraryManga>> =
                            flow {
                                if (timeout) awaitCancellation() else error("read failed")
                            }
                    }
                val error = assertIs<AppResult.Failure>(useCase(lib)()).error
                if (timeout) assertIs<AppError.Network.Timeout>(error) else assertIs<AppError.Storage.Io>(error)
            }
        }

    @Test
    fun itemTimeout_isFailure_andDoesNotCancelSuccessfulSibling() =
        runTest {
            val lib = library("A", "B")
            val result =
                useCase(lib) { manga ->
                    if (manga.title == "B") awaitCancellation()
                    AppResult.Success(details(manga, listOf(ch("1"))))
                }()
            assertIs<AppError.Network.Timeout>(assertIs<AppResult.Failure>(result).error)
            assertEquals(listOf("A"), lib.refreshRequests.flatten().map { it.fetched.details.title })
        }

    @Test
    fun totalTimeout_retainsCompletedSiblingInActiveBatch_andDoesNotAttemptRemainder() =
        runTest {
            // 32 batches * (27s work + 1s throttle) = 896s. At the 900s deadline, item 160
            // is confirmed, its four siblings are running, and three remaining items are unattempted.
            val lib = library(*(0 until 168).map { it.toString() }.toTypedArray())
            val fetched = mutableListOf<Int>()
            val result =
                useCase(lib) { manga ->
                    val index = manga.title.toInt()
                    fetched += index
                    if (index != 160) delay(27_000)
                    AppResult.Success(details(manga, listOf(ch("1"))))
                }()
            assertIs<AppError.Network.Timeout>(assertIs<AppResult.Failure>(result).error)
            assertEquals((0..164).toSet(), fetched.toSet())
            assertTrue(lib.refreshRequests.flatten().any { it.fetched.details.title == "160" })
            assertEquals(161, lib.refreshRequests.size)
        }

    @Test
    fun externalCancellation_atReadFetchCoverOrPersist_propagatesAndSettlesChildren() =
        runTest {
            for (stage in CancellationStage.entries) assertCancellationAt(stage)
        }

    private suspend fun TestScope.assertCancellationAt(stage: CancellationStage) {
        val reached = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Unit>()

        suspend fun pause() = pauseRefresh(reached, settled)

        val lib = cancellingLibrary(stage, ::pause)
        val metadata = object : LibraryMetadataRepository {
            override suspend fun updateCoverIfChanged(
                owner: SavedWorkIdentity,
                fetched: WorkLocator,
                newCoverUrl: String,
            ): AppResult<Unit> {
                if (stage == CancellationStage.COVER) pause()
                return AppResult.Success(Unit)
            }
        }
        var terminal: AppResult<LibraryRefreshCompleted>? = null
        val job =
            launch {
                terminal =
                    useCase(lib, libraryMetadata = metadata) { manga ->
                        if (stage == CancellationStage.FETCH) pause()
                        AppResult.Success(details(manga))
                    }()
            }
        reached.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(settled.isCompleted)
        assertNull(terminal)
    }

    private fun cancellingLibrary(
        stage: CancellationStage,
        pause: suspend () -> Unit,
    ): LibraryRepository =
        object : LibraryRepository by library("A") {
            override fun observeLibrary(): Flow<List<LibraryManga>> =
                flow {
                    if (stage == CancellationStage.READ) pause()
                    emit(listOf(sampleLibraryManga(title = "A")))
                }

            override suspend fun refresh(
                requests: List<LibraryRefreshRequest>,
                notify: Boolean,
            ): AppResult<List<LibraryRefreshReceipt>> {
                if (stage == CancellationStage.PERSIST) pause()
                return AppResult.Success(requests.map { LibraryRefreshReceipt(it.owner, 0, emptyList()) })
            }
        }

    @Test
    fun ancestorTimeout_isNotClassifiedAsTheUseCasesOwnTimeout() =
        runTest {
            val refresh = useCase(library("A")) { awaitCancellation() }
            assertFailsWith<CancellationException> { withTimeout(100) { refresh() } }
        }

    private enum class CancellationStage {
        READ,
        FETCH,
        COVER,
        PERSIST,
    }

    @Test
    fun networkFailure_skipsOnlyThatFetch_notTheSuccessfulBatch() = runTest {
        val a = sampleLibraryManga(title = "A", id = 11L)
        val b = sampleLibraryManga(title = "B", id = 22L)
        val library = FakeLibraryRepository().apply { emitLibrary(listOf(a, b)) }
        val details = RecordingDetailsRepository().apply {
            respond = { manga ->
                if (manga.url == a.manga.url) AppResult.Failure(AppError.Network.Timeout())
                else AppResult.Success(fetched(manga, 1))
            }
        }

        // A successful sibling commits, but a failed snapshot is never reported as success.
        assertIs<AppError.Network.Timeout>(assertIs<AppResult.Failure>(useCase(library, fetch = details::fetchDetails)()).error)
        assertEquals(listOf(b.identity), library.lastRefreshRequests.map { it.owner })
        assertEquals(listOf("refresh(1,notify=true)"), library.calls)
    }

    @Test
    fun identityStorageFailure_propagatesAndStopsLaterBatches() = runTest {
        val rows = (1L..6L).map { sampleLibraryManga(title = "Work $it", id = it) }
        val error = AppError.Storage.Constraint("ambiguous batch owner")
        val library = FakeLibraryRepository().apply {
            emitLibrary(rows)
            refreshResult = AppResult.Failure(error)
        }
        val details = RecordingDetailsRepository()

        assertEquals(AppResult.Failure(error), useCase(library, fetch = details::fetchDetails)())
        assertEquals(rows.take(5).map { it.manga }, details.requests)
        assertEquals(rows.take(5).map { it.identity }, library.refreshRequests.flatten().map { it.owner })
        assertEquals(List(5) { "refresh(1,notify=true)" }, library.calls)
    }

    @Test
    fun deleteReaddDuringFetch_doesNotSubstituteTheReplacementOwner() = runTest {
        val original = sampleLibraryManga(title = "A", id = 11L)
        val replacement = original.copy(identity = original.identity.copy(id = 99L))
        val error = AppError.Storage.Constraint("stale owner")
        val library = FakeLibraryRepository().apply {
            emitLibrary(listOf(original))
            refreshResult = AppResult.Failure(error)
        }
        val details = RecordingDetailsRepository().apply {
            respond = { manga ->
                library.emitLibrary(listOf(replacement))
                AppResult.Success(fetched(manga, 1).copy(title = "Renamed"))
            }
        }

        assertEquals(AppResult.Failure(error), useCase(library, fetch = details::fetchDetails)())
        val request = library.lastRefreshRequests.single()
        assertEquals(original.identity, request.owner)
        assertEquals(original.identity.locator, request.fetched.requested)
        assertEquals("Renamed", request.fetched.details.title)
    }

    @Test
    fun refreshWritesOnlyBoundedSuccessfulFetchBatches() = runTest {
        val rows = (1L..7L).map { sampleLibraryManga(title = "Work $it", id = it) }
        val library = FakeLibraryRepository().apply { emitLibrary(rows) }
        var active = 0
        var peak = 0
        val details = RecordingDetailsRepository().apply {
            respond = { manga ->
                active++
                peak = maxOf(peak, active)
                delay(10)
                active--
                AppResult.Success(fetched(manga, 1))
            }
        }

        assertEquals(AppResult.Success(LibraryRefreshCompleted(7, 7)), useCase(library, fetch = details::fetchDetails)())
        assertEquals(5, peak, "producer concurrency stays bounded while ready owners commit independently")
        assertEquals(List(7) { "refresh(1,notify=true)" }, library.calls)
        assertEquals(rows.map { it.manga }, details.requests)
        assertEquals(rows.map { it.identity }, library.refreshRequests.flatten().map { it.owner })
    }

    @Test
    fun duplicateSnapshotOwnersFailBeforeAnyFetchOrWrite() = runTest {
        val a = sampleLibraryManga(title = "A", id = 7L)
        val duplicates = listOf(
            listOf(a, sampleLibraryManga(title = "B", id = 7L)),
            listOf(a, a.copy(identity = a.identity.copy(id = 8L))),
        )
        for (rows in duplicates) {
            val library = FakeLibraryRepository().apply { emitLibrary(rows) }
            val details = RecordingDetailsRepository()
            assertIs<AppError.Storage.Constraint>(
                assertIs<AppResult.Failure>(useCase(library, fetch = details::fetchDetails)()).error,
            )
            assertTrue(details.requests.isEmpty())
            assertTrue(library.refreshRequests.isEmpty())
        }
    }
}

private class RecordingDetailsRepository : MangaDetailsRepository {
    val requests = mutableListOf<Manga>()
    var respond: suspend (Manga) -> AppResult<MangaDetails> = { AppResult.Success(fetched(it, 1)) }

    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
        requests += manga
        return respond(manga)
    }
}

private fun fetched(manga: Manga, chapterCount: Int) = MangaDetails(
    api = manga.api,
    language = manga.language,
    title = manga.title,
    url = manga.url,
    coverUrl = "",
    description = "",
    author = "",
    rating = "",
    status = "",
    genres = emptyList(),
    chapters = (1..chapterCount).map { chapter(manga.url, it) },
)

private fun chapter(workUrl: String, number: Int) = Chapter(
    number = number.toString(),
    name = "Chapter $number",
    url = "$workUrl/chapter/$number",
    date = null,
    isDownloaded = false,
    isBookmarked = false,
)
