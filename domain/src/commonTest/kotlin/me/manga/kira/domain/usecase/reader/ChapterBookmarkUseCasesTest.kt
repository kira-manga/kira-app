package me.manga.kira.domain.usecase.reader

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.ChapterBookmarkRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the chapter-bookmark use cases (Phase 6.4.x.bookmark, task #217).
 *
 * Pins that [ObserveChapterBookmarkUseCase] forwards the repository's bookmark flow verbatim and
 * that [ToggleChapterBookmarkUseCase] delegates the toggle to the repository with the given url.
 * The strangler-fig identity-resolution + not-in-library fallback live in the `:data` impl
 * (`ChapterBookmarkRepositoryImpl`, over the legacy Room store) and are out of scope for these
 * pure-`:domain` delegation tests.
 */
class ChapterBookmarkUseCasesTest {

    private val manga = Manga(
        api = "src", language = "en", title = "Manga", url = "https://src/manga",
        coverUrl = "", rating = null, genres = emptyList(),
    )

    private class FakeChapterBookmarkRepository(
        initial: Boolean = false,
    ) : ChapterBookmarkRepository {
        val state = MutableStateFlow(initial)
        val toggled = mutableListOf<Pair<Manga, String>>()
        val observed = mutableListOf<Pair<Manga, String>>()

        override fun observeBookmark(manga: Manga, chapterUrl: String): Flow<Boolean> {
            observed += manga to chapterUrl
            return state
        }

        override suspend fun toggleBookmark(manga: Manga, chapterUrl: String): Boolean {
            toggled += manga to chapterUrl
            state.value = !state.value
            return true
        }

        override suspend fun toggleBookmark(manga: Manga, chapterUrls: List<String>) {
            toggled += chapterUrls.map { manga to it }
        }
    }

    @Test
    fun observe_forwards_repository_flow() = runTest {
        val repo = FakeChapterBookmarkRepository(initial = false)
        val useCase = ObserveChapterBookmarkUseCase(repo)

        useCase(manga, "https://src/ch/1").test {
            assertEquals(false, awaitItem())
            repo.state.value = true
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(manga to "https://src/ch/1"), repo.observed)
    }

    @Test
    fun toggle_delegates_to_repository_with_url() = runTest {
        val repo = FakeChapterBookmarkRepository(initial = false)
        val useCase = ToggleChapterBookmarkUseCase(repo)

        useCase(manga, "https://src/ch/1")

        assertEquals(listOf(manga to "https://src/ch/1"), repo.toggled)
        assertTrue(repo.state.value)
    }
}
