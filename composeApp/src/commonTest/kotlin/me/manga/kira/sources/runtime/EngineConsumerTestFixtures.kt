package me.manga.kira.sources.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.MangaDetailsRepositoryImpl
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceBaseUrlProvider
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FieldSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import me.manga.kira.sources.engine.GenericSourceClient
import kotlin.test.assertTrue

/** Only catalog/HTTP/provider ports are synthetic; registry, adapter and external engine are real. */
internal object EngineConsumerTestFixtures {
    const val API = "engine-consumer"
    const val BASE = "https://engine-consumer.test"
    const val MANGA_URL = "$BASE/series/1"
    const val PAGE_ONE_URL = "$MANGA_URL/chapters?page=1"
    const val PAGE_TWO_URL = "$MANGA_URL/chapters?page=2"
    const val HOME_URL = "$BASE/home?page=1"
    const val FEATURED_URL = "$BASE/featured?page=1"

    val manga = Manga(API, "en", "Saved title", MANGA_URL, "$BASE/old.jpg", null, emptyList())
    val detailsRequests = listOf(MANGA_URL, PAGE_ONE_URL, PAGE_TWO_URL)

    private val config =
        SourceConfig(
            api = API,
            language = "en",
            baseUrl = BASE,
            enabled = true,
            engine = "generic",
            endpoints =
                mapOf(
                    "home" to EndpointSpec("{baseUrl}/home?page={page}", format = "json", root = "items"),
                    "featured" to EndpointSpec("{baseUrl}/featured?page={page}", format = "json", root = "items"),
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
                    "item.title" to FieldSpec(path = "title"),
                    "item.url" to FieldSpec(path = "url"),
                    "item.cover" to FieldSpec(path = "cover"),
                    "detail.title" to FieldSpec(path = "title"),
                    "detail.cover" to FieldSpec(path = "cover"),
                    "detail.description" to FieldSpec(path = "description"),
                    "chapter.number" to FieldSpec(path = "number"),
                    "chapter.name" to FieldSpec(path = "name"),
                    "chapter.url" to FieldSpec(path = "url"),
                ),
        )

    fun registry(
        http: HttpExecutor,
        provider: SourceBaseUrlProvider? = null,
    ): DefaultSourceRegistry {
        val document = SourceConfigDocument(schemaVersion = 1, sources = listOf(config))
        val validation = DefaultSourceConfigValidator(DefaultStrategyRegistry()).validate(document)
        assertTrue(validation.isValid, validation.errors.joinToString())
        return DefaultSourceRegistry(StaticSourceUpdateManager(document)) {
            GenericSourceClient(it, http, NoopHeaderStore(), baseUrlProvider = provider)
        }
    }

    fun detailsHttp(pageTwo: SourceResponse): EngineConsumerHttp =
        EngineConsumerHttp(
            mapOf(
                MANGA_URL to
                    SourceResponse(
                        200,
                        """{"title":"Fetched title","cover":"$BASE/new.jpg","description":"Fetched description"}""",
                    ),
                PAGE_ONE_URL to SourceResponse(200, chapterPage("1", hasNext = true)),
                PAGE_TWO_URL to pageTwo,
            ),
        )

    fun fetch(http: HttpExecutor, dispatcher: CoroutineDispatcher): FetchMangaDetailsUseCase =
        FetchMangaDetailsUseCase(
            MangaDetailsRepositoryImpl(engineConsumerDispatchers(dispatcher), registry(http)),
        )

    fun terminalPage(): SourceResponse = SourceResponse(200, chapterPage("2", hasNext = false))

    fun homeHttp(): EngineConsumerHttp {
        val body = """{"items":[{"title":"Home title","url":"$MANGA_URL","cover":"$BASE/new.jpg"}]}"""
        return EngineConsumerHttp(
            mapOf(HOME_URL to SourceResponse(200, body), FEATURED_URL to SourceResponse(200, body)),
        )
    }

    private fun chapterPage(
        number: String,
        hasNext: Boolean,
    ): String =
        """
        {"items":[{"number":"$number","name":"Chapter $number","url":"$MANGA_URL/chapter-$number"}],
        "has_next":$hasNext}
        """.trimIndent()
}

internal class EngineConsumerHttp(
    private val responses: Map<String, SourceResponse>,
) : HttpExecutor {
    val requestedUrls = mutableListOf<String>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requestedUrls += request.url
        return responses[request.url] ?: error("Unexpected fixture request: ${request.url}")
    }
}

internal fun engineConsumerDispatchers(dispatcher: CoroutineDispatcher): DispatcherProvider =
    object : DispatcherProvider {
        override val main = dispatcher
        override val mainImmediate = dispatcher
        override val default = dispatcher
        override val io = dispatcher
        override val unconfined = dispatcher
    }

/** Unused consumer ports fail loudly; scenarios override only their observed library/writer inputs. */
internal object EngineConsumerEmptyLibrary : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryManga>> = flowOf(emptyList())

    override fun observeMembership(work: WorkLocator): Flow<AppResult<SavedWorkIdentity?>> = unusedLibraryOperation()

    override suspend fun get(work: WorkLocator): AppResult<LibraryManga?> = unusedLibraryOperation()

    override suspend fun addToLibrary(fetched: FetchedWorkDetails): AppResult<SavedWorkIdentity> = unusedLibraryOperation()

    override suspend fun refresh(
        requests: List<LibraryRefreshRequest>,
        notify: Boolean,
    ): AppResult<List<LibraryRefreshReceipt>> = unusedLibraryOperation()

    override suspend fun removeFromLibrary(owner: SavedWorkIdentity): AppResult<Unit> = unusedLibraryOperation()

    override suspend fun removeAllFromLibrary(owners: List<SavedWorkIdentity>): AppResult<Int> = unusedLibraryOperation()

    override suspend fun toggleLiked(owner: SavedWorkIdentity): AppResult<Unit> = unusedLibraryOperation()

    override suspend fun toggleWatchingNow(owner: SavedWorkIdentity): AppResult<Unit> = unusedLibraryOperation()

    override suspend fun markOpened(owner: SavedWorkIdentity): AppResult<Unit> = unusedLibraryOperation()

    private fun unusedLibraryOperation(): Nothing = error("Unexpected library operation in engine-consumer fixture")
}
