package me.manga.kira.work

import androidx.work.ListenableWorker.Result
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.sources.contracts.HeaderStore
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FieldSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import me.manga.kira.sources.engine.GenericSourceClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real engine/adapter -> observed work policy, not Room, CoroutineWorker or notification delivery proof. */
class LibraryRefreshEngineConsumerTest {
    private val originalLogFloor = Logger.config.minSeverity

    @Before
    fun silenceAndroidLogging() {
        Logger.setMinSeverity(Severity.Assert)
    }

    @After
    fun restoreLogging() {
        Logger.setMinSeverity(originalLogFloor)
    }

    @Test
    fun requiredPageFailure_doesNotMutateNotifyOrStamp() =
        runTest {
            val manga =
                refreshManga(1).copy(
                    api = WorkerEngineFixture.API,
                    url = WorkerEngineFixture.MANGA_URL,
                    imageUrl = "${WorkerEngineFixture.BASE}/old.jpg",
                )
            val existing =
                SavedChapterEntity(
                    id = 7,
                    mangaId = manga.id,
                    name = "Existing chapter",
                    number = "0",
                    url = "${manga.url}/chapter-0",
                    date = null,
                    isRead = true,
                    lastReadPage = 3,
                )
            val savedChapters = mutableListOf(existing)
            val requests = mutableListOf<String>()
            val actualClient = WorkerEngineFixture.client(requests)
            var coverUpdates = 0
            var chapterReads = 0
            val port =
                LibraryRefreshWorkTestFixtures().apply {
                    libraryFlow = flowOf(listOf(manga))
                    chapterFlow = {
                        chapterReads++
                        flowOf(savedChapters.toList())
                    }
                    fetch = actualClient::details
                    cover = { coverUpdates++ }
                    persist = { _, rows ->
                        savedChapters += rows
                        emptyList()
                    }
                }
            val progress = mutableListOf<LibraryRefreshWorkProgress>()

            val result = LibraryRefreshWork(port, { progress += it }, StandardTestDispatcher(testScheduler)).run()

            assertEquals(Result.failure(), result)
            assertEquals(WorkerEngineFixture.detailsRequests, requests)
            assertEquals(listOf(existing), savedChapters)
            assertEquals(0, coverUpdates)
            assertEquals(0, chapterReads)
            assertTrue(port.persistenceCalls.isEmpty())
            assertTrue(port.persistedNotifications.isEmpty())
            assertTrue(port.displayCalls.isEmpty())
            assertEquals(0, port.stamps)
            assertEquals("old success", port.lastSuccess)
            val terminal = progress.last()
            assertFalse(terminal.isComplete)
            assertEquals(1, terminal.snapshotSize)
            assertEquals(0, terminal.succeeded)
            assertEquals(1, terminal.failed)
            assertEquals(0, terminal.timedOut)
            assertEquals(0, terminal.notAttempted)
            assertEquals(0, terminal.newChapterCount)
            assertEquals(LibraryRefreshWorkStop.EXHAUSTED, terminal.stop)
        }
}

private object WorkerEngineFixture {
    const val API = "engine-consumer"
    const val BASE = "https://engine-consumer.test"
    const val MANGA_URL = "$BASE/series/1"
    val detailsRequests = listOf(MANGA_URL, "$MANGA_URL/chapters?page=1", "$MANGA_URL/chapters?page=2")

    private val config =
        SourceConfig(
            api = API,
            language = "en",
            baseUrl = BASE,
            enabled = true,
            engine = "generic",
            endpoints =
                mapOf(
                    "home" to EndpointSpec("{baseUrl}/home", format = "json", root = "items"),
                    "details" to EndpointSpec("{itemUrl}", format = "json"),
                    "chapters" to
                        EndpointSpec(
                            "{itemUrl}/chapters?page={page}",
                            format = "json",
                            root = "items",
                            pageParam = "page",
                            lastPageLocator = "has_next",
                        ),
                ),
            fields =
                mapOf(
                    "detail.title" to FieldSpec(path = "title"),
                    "detail.cover" to FieldSpec(path = "cover"),
                    "chapter.number" to FieldSpec(path = "number"),
                    "chapter.name" to FieldSpec(path = "name"),
                    "chapter.url" to FieldSpec(path = "url"),
                ),
        )

    fun client(requests: MutableList<String>): GenericSourceClient {
        val validation =
            DefaultSourceConfigValidator(DefaultStrategyRegistry()).validate(
                SourceConfigDocument(schemaVersion = 1, sources = listOf(config)),
            )
        assertTrue(validation.errors.joinToString(), validation.isValid)
        return GenericSourceClient(
            config,
            object : HttpExecutor {
                override suspend fun execute(request: SourceRequest): SourceResponse {
                    requests += request.url
                    return when (request.url) {
                        MANGA_URL -> SourceResponse(200, """{"title":"Fetched title","cover":"$BASE/new.jpg"}""")
                        "$MANGA_URL/chapters?page=1" ->
                            SourceResponse(
                                200,
                                """
                                {"items":[{"number":"1","name":"Chapter 1","url":"$MANGA_URL/chapter-1"}],
                                "has_next":true}
                                """.trimIndent(),
                            )
                        "$MANGA_URL/chapters?page=2" -> SourceResponse(503, "upstream unavailable")
                        else -> error("Unexpected fixture request: ${request.url}")
                    }
                }
            },
            object : HeaderStore {
                override suspend fun headersFor(api: String): Map<String, String> = emptyMap()

                override suspend fun save(api: String, headers: Map<String, String>) = Unit
            },
        )
    }
}
