package me.manga.kira.sources.runtime

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.MangaDetailsRepositoryImpl
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.sources.contracts.SourceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Real engine through App registry/repository, separate from shared-refresh completion/stamp policy. */
class GenericSourceDetailsConsumerTest {
    @Test
    fun requiredPageFailure_survivesAdapterRegistryAndRepository() =
        runTest {
            val http = EngineConsumerTestFixtures.detailsHttp(SourceResponse(503, "upstream unavailable"))
            val fetch =
                FetchMangaDetailsUseCase(
                    MangaDetailsRepositoryImpl(
                        engineConsumerDispatchers(StandardTestDispatcher(testScheduler)),
                        EngineConsumerTestFixtures.registry(http),
                    ),
                )

            val result = fetch(EngineConsumerTestFixtures.manga)

            val failure = assertIs<AppResult.Failure>(result)
            assertEquals(503, assertIs<AppError.Network.Http>(failure.error).statusCode)
            assertEquals(EngineConsumerTestFixtures.detailsRequests, http.requestedUrls)
        }

    @Test
    fun terminalPageSuccess_mapsCompleteChapterList() =
        runTest {
            val http = EngineConsumerTestFixtures.detailsHttp(EngineConsumerTestFixtures.terminalPage())
            val fetch =
                FetchMangaDetailsUseCase(
                    MangaDetailsRepositoryImpl(
                        engineConsumerDispatchers(StandardTestDispatcher(testScheduler)),
                        EngineConsumerTestFixtures.registry(http),
                    ),
                )

            val details = assertIs<AppResult.Success<MangaDetails>>(fetch(EngineConsumerTestFixtures.manga)).value

            assertEquals(EngineConsumerTestFixtures.API, details.api)
            assertEquals("en", details.language)
            assertEquals(EngineConsumerTestFixtures.MANGA_URL, details.url)
            assertEquals("Fetched title", details.title)
            assertEquals("${EngineConsumerTestFixtures.BASE}/new.jpg", details.coverUrl)
            assertEquals("Fetched description", details.description)
            assertEquals(listOf("1", "2"), details.chapters.map { it.number })
            assertEquals(listOf("Chapter 1", "Chapter 2"), details.chapters.map { it.name })
            assertEquals(
                listOf("chapter-1", "chapter-2").map { "${EngineConsumerTestFixtures.MANGA_URL}/$it" },
                details.chapters.map { it.url },
            )
            assertEquals(EngineConsumerTestFixtures.detailsRequests, http.requestedUrls)
        }
}
