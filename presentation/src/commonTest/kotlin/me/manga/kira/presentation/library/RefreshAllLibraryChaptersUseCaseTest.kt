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
            assertEquals(1, lib.calls.count { it == "persistNewChaptersAndNotify(A,fetched=2)" })
            assertEquals(1, lib.calls.count { it == "persistNewChaptersAndNotify(B,fetched=1)" })
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
            assertTrue(lib.calls.none { it.startsWith("persistNewChaptersAndNotify") })
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
                    lib.calls.count { it.startsWith("persistNewChaptersAndNotify") },
                )
            }
        }

    @Test
    fun knownCombinedPersistenceFailure_doesNotBecomeZeroChapterSuccess() =
        runTest {
            val error = AppError.Storage.Io()
            val lib =
                object : LibraryRepository by library("A") {
                    override suspend fun persistNewChaptersAndNotify(
                        manga: Manga,
                        fetched: List<Chapter>,
                    ): AppResult<Int> = AppResult.Failure(error)
                }
            assertEquals(error, assertIs<AppResult.Failure>(useCase(lib)()).error)
        }

    @Test
    fun coverFailure_isBestEffort_andNonemptyZeroNewChaptersStillCompletes() =
        runTest {
            val base = library("A")
            val lib =
                object : LibraryRepository by base {
                    override suspend fun updateCoverIfChanged(
                        api: String,
                        language: String,
                        title: String,
                        newCoverUrl: String,
                    ): AppResult<Unit> = AppResult.Failure(AppError.Storage.Io())
                }
            val result = useCase(lib)()
            assertEquals(
                LibraryRefreshCompleted(1, 0),
                assertIs<AppResult.Success<LibraryRefreshCompleted>>(result).value,
            )
            assertTrue(base.calls.contains("persistNewChaptersAndNotify(A,fetched=0)"))
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
            assertTrue(lib.calls.contains("persistNewChaptersAndNotify(A,fetched=1)"))
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
            assertTrue(lib.calls.contains("persistNewChaptersAndNotify(160,fetched=1)"))
            assertEquals(161, lib.calls.count { it.startsWith("persistNewChaptersAndNotify") })
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
        var terminal: AppResult<LibraryRefreshCompleted>? = null
        val job =
            launch {
                terminal =
                    useCase(lib) { manga ->
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

            override suspend fun updateCoverIfChanged(
                api: String,
                language: String,
                title: String,
                newCoverUrl: String,
            ): AppResult<Unit> {
                if (stage == CancellationStage.COVER) pause()
                return AppResult.Success(Unit)
            }

            override suspend fun persistNewChaptersAndNotify(
                manga: Manga,
                fetched: List<Chapter>,
            ): AppResult<Int> {
                if (stage == CancellationStage.PERSIST) pause()
                return AppResult.Success(0)
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
}
