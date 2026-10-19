package me.manga.kira.sources.runtime

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.MangaDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Real engine through App registry/repository and the shared refresh/completion consumer. */
class GenericSourceDetailsConsumerTest {
    @Test
    fun requiredPageFailure_reachesSharedRefreshAndCompletesFalse() =
        runTest {
            val fixture = EngineConsumerRefreshTestFixture(StandardTestDispatcher(testScheduler))
            fixture.run(this)
            fixture.assertFailedWithoutMutations()
        }

    @Test
    fun terminalPageSuccess_mapsCompleteChapterList() =
        runTest {
            val http = EngineConsumerTestFixtures.detailsHttp(EngineConsumerTestFixtures.terminalPage())
            val fetch = EngineConsumerTestFixtures.fetch(http, StandardTestDispatcher(testScheduler))

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
