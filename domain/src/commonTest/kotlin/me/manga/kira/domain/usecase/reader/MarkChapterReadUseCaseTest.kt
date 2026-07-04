package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.test.runTest
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

    private class FakeMarkChapterReadRepository : MarkChapterReadRepository {
        val markedUrls = mutableListOf<String>()
        val toggledUrls = mutableListOf<String>()
        val bulkMarkedUrls = mutableListOf<List<String>>()

        override suspend fun markRead(chapterUrl: String) {
            markedUrls += chapterUrl
        }

        override suspend fun toggleRead(chapterUrl: String) {
            toggledUrls += chapterUrl
        }

        override suspend fun markRead(chapterUrls: List<String>) {
            bulkMarkedUrls += chapterUrls
        }
    }

    @Test
    fun markRead_delegates_to_repository_with_url() = runTest {
        val repo = FakeMarkChapterReadRepository()
        val useCase = MarkChapterReadUseCase(repo)

        useCase("https://src/ch/1")

        assertEquals(listOf("https://src/ch/1"), repo.markedUrls)
    }
}
