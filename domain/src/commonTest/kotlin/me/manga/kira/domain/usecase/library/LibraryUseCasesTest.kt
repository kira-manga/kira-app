package me.manga.kira.domain.usecase.library

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
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
            repo.calls.any { it == "addToLibrary(test-api,https://example.test/manga,chapters=2)" },
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
            SavedWorkIdentity(1L, WorkLocator("a", "https://example.test/1")),
            SavedWorkIdentity(2L, WorkLocator("a", "https://example.test/2")),
            SavedWorkIdentity(3L, WorkLocator("a", "https://example.test/3")),
        )

        val result = BulkRemoveFromLibraryUseCase(repo)(keys)

        assertEquals(3, result.getOrNull())
        assertTrue(repo.calls.any { it == "removeAllFromLibrary(3)" }, "calls=${repo.calls}")
    }

    @Test
    fun bulkRemove_forwards_rollback_failure_for_stale_retained_owner() = runTest {
        val failure = AppResult.Failure(AppError.Storage.Constraint("retained-owner-changed"))
        val repo = FakeLibraryRepository().apply { removeAllResult = failure }
        val owners = listOf(SavedWorkIdentity(8L, WorkLocator("a", "https://example.test/old")))

        val result = BulkRemoveFromLibraryUseCase(repo)(owners)

        assertEquals(failure, result)
        assertEquals(owners, repo.lastBulkOwners)
    }

    @Test
    fun bulkRemove_forwards_deduplicated_count() = runTest {
        val owner = SavedWorkIdentity(8L, WorkLocator("a", "https://example.test/one"))
        val repo = FakeLibraryRepository().apply { removeAllResult = AppResult.Success(1) }
        assertEquals(1, BulkRemoveFromLibraryUseCase(repo)(listOf(owner, owner)).getOrNull())
    }

    @Test
    fun toggleLiked_delegates_to_repository() = runTest {
        val repo = FakeLibraryRepository()

        val result = ToggleMangaLikedUseCase(repo)(SavedWorkIdentity(12L, WorkLocator("a", "https://example.test/liked")))

        assertTrue(result.isSuccess)
        assertTrue(repo.calls.any { it == "toggleLiked(12)" }, "calls=${repo.calls}")
    }

    @Test
    fun toggleWatchingNow_delegates_to_repository() = runTest {
        val repo = FakeLibraryRepository()

        val result = ToggleMangaWatchingNowUseCase(repo)(SavedWorkIdentity(13L, WorkLocator("a", "https://example.test/watch")))

        assertTrue(result.isSuccess)
        assertTrue(repo.calls.any { it == "toggleWatchingNow(13)" }, "calls=${repo.calls}")
    }

    @Test
    fun observeInLibrary_forwards_owner_and_explicit_failure() = runTest {
        val repo = FakeLibraryRepository()
        val work = WorkLocator("a", "https://example.test/x")
        val owner = SavedWorkIdentity(7L, work)
        ObserveInLibraryUseCase(repo)(work).test {
            assertEquals(AppResult.Success(null), awaitItem())
            repo.emitMembership(work, AppResult.Success(owner))
            assertEquals(AppResult.Success(owner), awaitItem())
            val failure = AppResult.Failure(AppError.Storage.Constraint("ambiguous"))
            repo.emitMembership(work, failure)
            assertEquals(failure, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun toggleInLibrary_uses_locator_despite_title_and_language_drift() = runTest {
        val repo = FakeLibraryRepository()
        val original = sampleManga()
        val saved = sampleLibraryManga(manga = original, id = 11L)
        repo.emitLibrary(listOf(saved))
        val renamed = original.copy(title = "Renamed", language = "ar")

        assertEquals(false, ToggleInLibraryUseCase(repo)(renamed).getOrNull())
        assertEquals(saved.identity, repo.lastRemovedOwner)
        assertTrue(repo.lastAddedDetails == null)
    }

    @Test
    fun toggleInLibrary_retained_owner_cannot_be_replaced_by_a_new_lookup() = runTest {
        val repo = FakeLibraryRepository()
        val manga = sampleManga()
        val oldOwner = SavedWorkIdentity(10L, WorkLocator(manga.api, manga.url))
        repo.emitLibrary(listOf(sampleLibraryManga(manga, id = 20L)))
        val failure = AppResult.Failure(AppError.Storage.Constraint("retained-owner-changed"))
        repo.removeResult = failure

        assertEquals(failure, ToggleInLibraryUseCase(repo)(manga, retainedOwner = oldOwner))
        assertEquals(oldOwner, repo.lastRemovedOwner)
        assertTrue(repo.calls.none { it.startsWith("get(") || it.startsWith("addToLibrary(") })
    }

    @Test
    fun toggleInLibrary_does_not_rewrite_the_retained_locator_from_display_metadata() = runTest {
        val repo = FakeLibraryRepository()
        val original = sampleManga()
        val owner = SavedWorkIdentity(10L, WorkLocator(original.api, original.url))
        val failure = AppResult.Failure(AppError.Storage.Constraint("unproven retained address"))
        repo.removeResult = failure
        val changedDisplay = original.copy(url = "https://another.test/work")

        assertEquals(failure, ToggleInLibraryUseCase(repo)(changedDisplay, retainedOwner = owner))
        assertEquals(owner, repo.lastRemovedOwner, "the writer must still revalidate the captured address")
        assertTrue(repo.calls.none { it.startsWith("get(") || it.startsWith("addToLibrary(") })
    }
}
