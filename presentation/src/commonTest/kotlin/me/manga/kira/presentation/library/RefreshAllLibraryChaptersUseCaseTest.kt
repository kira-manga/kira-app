package me.manga.kira.presentation.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersAndNotifyUseCase
import me.manga.kira.domain.usecase.library.RefreshAllLibraryChaptersUseCase
import me.manga.kira.presentation.testing.FakeLibraryRepository
import me.manga.kira.presentation.testing.sampleLibraryManga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1 cross-platform refresh-all: iterates EVERY saved manga, fetches each, and persists the new
 * chapters (the same persist primitive Details refresh uses). This is the in-process refresh
 * Desktop/iOS run; the test verifies the orchestration independent of the platform scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshAllLibraryChaptersUseCaseTest {

    // Build the provider from runTest's own scheduler so the use case's inter-batch delay(1000) is
    // VIRTUAL (auto-advanced by runTest) instead of a real wall-clock wait that would hang the test.
    private fun TestScope.dispatchers(): DispatcherProvider {
        val d = UnconfinedTestDispatcher(testScheduler)
        return object : DispatcherProvider {
            override val main = d
            override val mainImmediate = d
            override val default = d
            override val io = d
            override val unconfined = d
        }
    }

    private class FakeDetailsRepo(private val chaptersByTitle: Map<String, List<Chapter>>) : MangaDetailsRepository {
        override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> = AppResult.Success(
            MangaDetails(
                api = manga.api, language = manga.language, title = manga.title, url = manga.url,
                coverUrl = "", description = "", author = "", rating = "", status = "",
                genres = emptyList(), chapters = chaptersByTitle[manga.title] ?: emptyList(),
            ),
        )
    }

    private fun ch(n: String) = Chapter(number = n, name = n, url = "c/$n", date = null, isDownloaded = false, isBookmarked = false)

    @Test
    fun refreshesEverySavedManga_andPersistsNewChapters() = runTest {
        val lib = FakeLibraryRepository().apply {
            emitLibrary(listOf(sampleLibraryManga(title = "A"), sampleLibraryManga(title = "B")))
        }
        val details = FakeDetailsRepo(
            mapOf("A" to listOf(ch("1"), ch("2")), "B" to listOf(ch("1"))),
        )
        val useCase = RefreshAllLibraryChaptersUseCase(
            observeLibrary = ObserveLibraryUseCase(lib),
            fetchDetails = FetchMangaDetailsUseCase(details),
            persistAndNotify = PersistNewChaptersAndNotifyUseCase(lib),
            libraryRepo = lib,
            dispatchers = dispatchers(),
        )

        val result = useCase()

        assertTrue(result is AppResult.Success)
        // The fake persistNewChaptersAndNotify returns fetched.size; A=2 + B=1 = 3.
        assertEquals(3, (result as AppResult.Success).value)
        // Refresh-all uses the persist-AND-NOTIFY path (so new chapters surface in Notifications).
        assertEquals(1, lib.calls.count { it == "persistNewChaptersAndNotify(A,fetched=2)" })
        assertEquals(1, lib.calls.count { it == "persistNewChaptersAndNotify(B,fetched=1)" })
    }

    @Test
    fun emptyLibrary_isZeroAndDoesNothing() = runTest {
        val lib = FakeLibraryRepository() // empty
        val useCase = RefreshAllLibraryChaptersUseCase(
            observeLibrary = ObserveLibraryUseCase(lib),
            fetchDetails = FetchMangaDetailsUseCase(FakeDetailsRepo(emptyMap())),
            persistAndNotify = PersistNewChaptersAndNotifyUseCase(lib),
            libraryRepo = lib,
            dispatchers = dispatchers(),
        )

        val result = useCase()

        assertEquals(0, (result as AppResult.Success).value)
        assertTrue(lib.calls.none { it.startsWith("persistNewChaptersAndNotify") })
    }
}
