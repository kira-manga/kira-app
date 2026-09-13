package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.MarkChapterReadRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract test for [MarkChapterReadUseCase] (Reader-convergence R3b).
 *
 * Pins that the use case delegates the mark to the repository with the given url. The strangler-fig
 * identity-resolution + not-in-library no-op + non-incognito-gating live in the `:data` impl
 * (`MarkChapterReadRepositoryImpl`, over the legacy Room store) and are out of scope for this
 * pure-`:domain` delegation test.
 */
class MarkChapterReadUseCaseTest {

    private val manga = Manga(
        api = "src", language = "en", title = "Manga", url = "https://src/manga",
        coverUrl = "", rating = null, genres = emptyList(),
    )

    private class FakeMarkChapterReadRepository : MarkChapterReadRepository {
        val marked = mutableListOf<Pair<Manga, String>>()
        val toggled = mutableListOf<Pair<Manga, String>>()
        val bulkMarked = mutableListOf<Pair<Manga, List<String>>>()

        override suspend fun markRead(manga: Manga, chapterUrl: String) {
            marked += manga to chapterUrl
        }

        override suspend fun toggleRead(manga: Manga, chapterUrl: String) {
            toggled += manga to chapterUrl
        }

        override suspend fun markRead(manga: Manga, chapterUrls: List<String>) {
            bulkMarked += manga to chapterUrls
        }
    }

    @Test
    fun markRead_delegates_to_repository_with_url() = runTest {
        val repo = FakeMarkChapterReadRepository()
        val useCase = MarkChapterReadUseCase(repo)

        useCase(manga, "https://src/ch/1")

        assertEquals(listOf(manga to "https://src/ch/1"), repo.marked)
    }
}
