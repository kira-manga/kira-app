package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.SavedWorkDetails
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.SavedMangaDetailsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cache-first contract for [ListChaptersUseCase] — the Reader's chapter list.
 *
 * Bug being guarded against (2026-06): a Library-opened manga re-fetched its chapter list from the
 * network even though the list was already saved in Room, and the network list's URLs could differ
 * from the saved chapter the user clicked — so `currentChapterIndex` resolved to -1 and a non-last
 * chapter wrongly rendered as the last (Next/Prev disabled). The fix makes the use case return the
 * saved Room list for an in-library manga (identical to what Details shows) and only hit the network
 * for a manga that has no saved list.
 */
class ListChaptersUseCaseTest {

    private val manga = Manga(
        api = "src", language = "en", title = "Naruto",
        url = "https://x/naruto", coverUrl = "", rating = null, genres = emptyList(),
    )

    private fun chapter(url: String) = Chapter(
        number = url.substringAfterLast('/'), name = "", url = url,
        date = null, isDownloaded = false, isBookmarked = false,
    )

    private fun detailsWith(chapters: List<Chapter>) = MangaDetails(
        api = "src", language = "en", title = "Naruto", url = "https://x/naruto",
        coverUrl = "", description = "", author = "", rating = "", status = "",
        genres = emptyList(), chapters = chapters,
    )

    private class FakeNetworkDetails(
        private val result: AppResult<MangaDetails>,
    ) : MangaDetailsRepository {
        var fetchCount = 0
            private set

        override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
            fetchCount++
            return result
        }
    }

    private class FakeSavedDetails(
        private val result: Flow<AppResult<SavedWorkDetails?>>,
    ) : SavedMangaDetailsRepository {
        val requested = mutableListOf<WorkLocator>()

        override fun observeSavedDetails(work: WorkLocator): Flow<AppResult<SavedWorkDetails?>> {
            requested += work
            return result
        }
    }

    private fun savedDetails(saved: MangaDetails?): FakeSavedDetails = FakeSavedDetails(
        flowOf(AppResult.Success(saved?.let { SavedWorkDetails(SavedWorkIdentity(7, WorkLocator(it.api, it.url)), it) })),
    )

    @Test
    fun inLibraryMangaReturnsSavedChaptersWithoutHittingNetwork() = runTest {
        val savedChapters = listOf(chapter("https://x/c1"), chapter("https://x/c2"), chapter("https://x/c3"))
        // Network would return a DIVERGENT list (different URLs) — must NOT be consulted at all.
        val network = FakeNetworkDetails(AppResult.Success(detailsWith(listOf(chapter("https://x/OTHER")))))
        val useCase = ListChaptersUseCase(network, savedDetails(detailsWith(savedChapters)))

        val result = useCase(manga)

        assertTrue(result is AppResult.Success)
        assertEquals(savedChapters, result.value)
        assertEquals(0, network.fetchCount, "saved chapters present -> network fetch must be skipped")
    }

    @Test
    fun notInLibraryFallsThroughToNetwork() = runTest {
        val networkChapters = listOf(chapter("https://x/n1"), chapter("https://x/n2"))
        val network = FakeNetworkDetails(AppResult.Success(detailsWith(networkChapters)))
        val useCase = ListChaptersUseCase(network, savedDetails(saved = null))

        val result = useCase(manga)

        assertTrue(result is AppResult.Success)
        assertEquals(networkChapters, result.value)
        assertEquals(1, network.fetchCount)
    }

    @Test
    fun savedButEmptyChapterListFallsThroughToNetwork() = runTest {
        val networkChapters = listOf(chapter("https://x/n1"))
        val network = FakeNetworkDetails(AppResult.Success(detailsWith(networkChapters)))
        // Saved row exists but has no chapters yet (e.g. quick-added, not opened) -> network fills it.
        val useCase = ListChaptersUseCase(network, savedDetails(detailsWith(emptyList())))

        val result = useCase(manga)

        assertTrue(result is AppResult.Success)
        assertEquals(networkChapters, result.value)
        assertEquals(1, network.fetchCount)
    }

    @Test
    fun notInLibraryNetworkFailureSurfacesFailure() = runTest {
        val failure = AppResult.Failure(AppError.Unexpected("boom"))
        val network = FakeNetworkDetails(failure)
        val useCase = ListChaptersUseCase(network, savedDetails(saved = null))

        val result = useCase(manga)

        assertFalse(result is AppResult.Success)
        assertEquals(1, network.fetchCount)
    }

    @Test
    fun lookupUsesRequestedLocatorDespiteRenamedMetadata() = runTest {
        val chapters = listOf(chapter("https://x/saved"))
        val saved = savedDetails(detailsWith(chapters))
        val network = FakeNetworkDetails(AppResult.Success(detailsWith(emptyList())))

        val result = ListChaptersUseCase(network, saved)(manga.copy(title = "Renamed", language = "ar"))

        assertEquals(AppResult.Success(chapters), result)
        assertEquals(listOf(WorkLocator(manga.api, manga.url)), saved.requested)
        assertEquals(0, network.fetchCount)
    }

    @Test
    fun identityConflictAndReadinessFailureDoNotBecomeCacheMisses() = runTest {
        val failures = listOf(
            AppError.Storage.Constraint("saved owner conflict"),
            AppError.Storage.Io(IllegalStateException("selection not ready")),
        )
        for (error in failures) {
            val network = FakeNetworkDetails(AppResult.Success(detailsWith(emptyList())))
            val saved = FakeSavedDetails(flowOf(AppResult.Failure(error)))

            assertEquals(AppResult.Failure(error), ListChaptersUseCase(network, saved)(manga))
            assertEquals(0, network.fetchCount, "an explicit failure cannot authorize a network fallback")
        }
    }

    @Test
    fun thrownCacheReadFailureStaysTypedWithoutNetworkFallback() = runTest {
        val cause = IllegalStateException("local read failed")
        val network = FakeNetworkDetails(AppResult.Success(detailsWith(emptyList())))
        val saved = FakeSavedDetails(flow { throw cause })

        assertEquals(AppResult.Failure(AppError.Storage.Io(cause)), ListChaptersUseCase(network, saved)(manga))
        assertEquals(0, network.fetchCount)
    }

    @Test
    fun cacheCancellationPropagatesWithoutNetworkFallback() = runTest {
        val cancelled = CancellationException("entry replaced")
        val network = FakeNetworkDetails(AppResult.Success(detailsWith(emptyList())))
        val saved = FakeSavedDetails(flow { throw cancelled })

        assertSame(cancelled, assertFailsWith<CancellationException> { ListChaptersUseCase(network, saved)(manga) })
        assertEquals(0, network.fetchCount)
    }
}
