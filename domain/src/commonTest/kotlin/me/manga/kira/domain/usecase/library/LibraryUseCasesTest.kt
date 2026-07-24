package me.manga.kira.domain.usecase.library

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.testing.FakeLibraryRepository
import me.manga.kira.domain.testing.sampleChapter
import me.manga.kira.domain.testing.sampleLibraryManga
import me.manga.kira.domain.testing.sampleManga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioural tests for the library-repository-backed use cases, exercised against
 * [FakeLibraryRepository]. These pin the bits with real logic — the toggle add/remove branch, the
 * bulk-remove empty short-circuit + count forwarding, and the thin delegations — so a future
 * refactor that, say, drops the empty-keys guard or inverts the toggle branch fails loudly.
 */
class LibraryUseCasesTest {

    private fun details(
        manga: Manga,
        chapters: List<me.manga.kira.domain.model.Chapter> = emptyList(),
    ) = MangaDetails(
        api = manga.api,
        language = manga.language,
        title = manga.title,
        url = manga.url,
        coverUrl = manga.coverUrl,
        description = "A complete description",
        author = "Author",
        rating = "4.8 / 5",
        status = "Ongoing",
        genres = manga.genres,
        chapters = chapters,
    )

    @Test
    fun observeLibrary_emits_the_seeded_rows() = runTest {
        val repo = FakeLibraryRepository()
        val rows = listOf(sampleLibraryManga(manga = sampleManga(title = "A"), unreadCount = 2))
        repo.emitLibrary(rows)

        ObserveLibraryUseCase(repo)().test {
            assertEquals(rows, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun toggleInLibrary_adds_when_absent_and_returns_true() = runTest {
        val repo = FakeLibraryRepository() // empty library
        val manga = sampleManga(title = "Absent")

        val result = ToggleInLibraryUseCase(repo)(manga, details(manga))

        assertEquals(true, result.getOrNull())
        assertTrue(repo.calls.any { it.startsWith("addToLibrary(") }, "expected add; calls=${repo.calls}")
        assertTrue(repo.calls.none { it.startsWith("removeFromLibrary(") }, "must not remove; calls=${repo.calls}")
    }

    @Test
    fun toggleInLibrary_add_threads_the_complete_details_to_the_repository() = runTest {
        val repo = FakeLibraryRepository() // empty library → add branch
        val manga = sampleManga(title = "Absent")
        val chapters = listOf(sampleChapter(number = "1"), sampleChapter(number = "2"))
        val details = details(manga, chapters)

        val result = ToggleInLibraryUseCase(repo)(manga, details)

        assertEquals(true, result.getOrNull())
        assertTrue(
            repo.calls.any { it == "addToLibrary(test-api,en,Absent,chapters=2)" },
            "add must forward the chapter list to the repository; calls=${repo.calls}",
        )
        assertEquals(details, repo.lastAddedDetails, "the complete fetched details must reach the repo")
    }

    @Test
    fun toggleInLibrary_add_without_details_fails_closed() = runTest {
        val repo = FakeLibraryRepository()
        val manga = sampleManga(title = "QuickAdd")

        val result = ToggleInLibraryUseCase(repo)(manga)

        assertEquals(AppError.Validation.Required("mangaDetails"), result.errorOrNull())
        assertTrue(repo.lastAddedDetails == null, "a partial row must never be persisted")
        assertTrue(repo.calls.none { it.startsWith("addToLibrary(") }, "calls=${repo.calls}")
    }

    @Test
    fun toggleInLibrary_removes_when_present_and_returns_false() = runTest {
        val repo = FakeLibraryRepository()
        val manga = sampleManga(api = "a", language = "en", title = "Present")
        repo.emitLibrary(listOf(sampleLibraryManga(manga = manga)))

        val result = ToggleInLibraryUseCase(repo)(manga)

        assertEquals(false, result.getOrNull())
        assertTrue(repo.calls.any { it.startsWith("removeFromLibrary(") }, "expected remove; calls=${repo.calls}")
        assertTrue(repo.calls.none { it.startsWith("addToLibrary(") }, "must not add; calls=${repo.calls}")
    }

    @Test
    fun bulkRemove_short_circuits_on_empty_without_touching_repository() = runTest {
        val repo = FakeLibraryRepository()

        val result = BulkRemoveFromLibraryUseCase(repo)(emptyList())

        assertEquals(0, result.getOrNull())
        assertTrue(repo.calls.isEmpty(), "empty bulk-remove must not touch the repo; calls=${repo.calls}")
    }

    @Test
    fun bulkRemove_forwards_the_targeted_count() = runTest {
        val repo = FakeLibraryRepository()
        val keys = listOf(
            MangaKey("a", "en", "One"),
            MangaKey("a", "en", "Two"),
            MangaKey("a", "en", "Three"),
        )

        val result = BulkRemoveFromLibraryUseCase(repo)(keys)

        assertEquals(3, result.getOrNull())
        assertTrue(repo.calls.any { it == "removeAllFromLibrary(3)" }, "calls=${repo.calls}")
    }

    @Test
    fun bulkRemove_forwards_the_repos_actual_purged_count_when_below_targeted() = runTest {
        // #21: when some selected keys are already gone (no saved_manga row), the repo purges fewer
        // rows than were targeted and returns that TRUE count. The use case must forward the repo's
        // count verbatim — NOT keys.size — so the "Removed N items" toast reflects what was removed.
        val repo = FakeLibraryRepository().apply { removeAllPurgedCount = 2 }
        val keys = listOf(
            MangaKey("a", "en", "One"),
            MangaKey("a", "en", "Two"),
            MangaKey("a", "en", "Gone"), // already removed → repo skips it
        )

        val result = BulkRemoveFromLibraryUseCase(repo)(keys)

        assertEquals(2, result.getOrNull(), "must forward the repo's actual purged count, not keys.size=3")
        assertTrue(repo.calls.any { it == "removeAllFromLibrary(3)" }, "calls=${repo.calls}")
    }

    @Test
    fun toggleLiked_delegates_to_repository() = runTest {
        val repo = FakeLibraryRepository()

        val result = ToggleMangaLikedUseCase(repo)(MangaKey("a", "en", "Liked"))

        assertTrue(result.isSuccess)
        assertTrue(repo.calls.any { it == "toggleLiked(Liked)" }, "calls=${repo.calls}")
    }

    @Test
    fun toggleWatchingNow_delegates_to_repository() = runTest {
        val repo = FakeLibraryRepository()

        val result = ToggleMangaWatchingNowUseCase(repo)(MangaKey("a", "en", "Watch"))

        assertTrue(result.isSuccess)
        assertTrue(repo.calls.any { it == "toggleWatchingNow(Watch)" }, "calls=${repo.calls}")
    }

    @Test
    fun observeInLibrary_forwards_the_membership_flow() = runTest {
        val repo = FakeLibraryRepository()

        ObserveInLibraryUseCase(repo)(api = "a", language = "en", title = "X").test {
            assertEquals(false, awaitItem())
            repo.emitInLibrary(true)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
