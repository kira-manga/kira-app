package me.manga.kira.sources.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.source.contracts.SourceChapter as SharedChapter
import me.manga.kira.source.contracts.SourceEngineError
import me.manga.kira.source.contracts.SourceEngineResult
import me.manga.kira.source.contracts.SourceFilterSelections as SharedFilterSelections
import me.manga.kira.source.contracts.SourceHttpMethod as SharedHttpMethod
import me.manga.kira.source.contracts.SourceMangaRef
import me.manga.kira.source.contracts.SourceRequest as SharedRequest
import me.manga.kira.source.contracts.SourceResponse as SharedResponse
import me.manga.kira.source.contracts.model.SourceConfig as SharedSourceConfig
import me.manga.kira.source.engine.GenericSourceEngine
import me.manga.kira.sources.contracts.CloudflareChallengeSignal
import me.manga.kira.sources.contracts.HeaderStore
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceBaseUrlProvider
import me.manga.kira.sources.contracts.SourceHttpMethod
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.model.SourceConfig

/**
 * App-domain adapter for the shared config-driven engine.
 *
 * Source execution, extraction, filtering, pagination, and request composition live in the pinned
 * `me.manga.kira.source:source-engine` package used by both the app and backend previews. This
 * adapter owns only app-specific ports, result/error translation, and domain-model mapping.
 *
 * A configuration failure or runtime failure is returned to the caller. No legacy source adapter
 * is inferred, and an absent catalog source cannot become executable through this class.
 */
class GenericSourceClient(
    config: SourceConfig,
    http: HttpExecutor,
    headerStore: HeaderStore,
    cloudflare: CloudflareChallengeSignal? = null,
    baseUrlProvider: SourceBaseUrlProvider? = null,
) : MangaSourceClient {

    private val engine =
        GenericSourceEngine(
            config = config.toSharedConfig(),
            http = { request -> http.execute(request.toAppRequest()).toSharedResponse() },
            headerStore = { api -> headerStore.headersFor(api) },
            cloudflare = cloudflare?.let { signal -> { api, url -> signal.onChallenge(api, url) } },
            baseUrlProvider = baseUrlProvider?.let { provider -> { api -> provider.baseUrlFor(api) } },
        )

    override val api: String = engine.api

    override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> =
        engine.home(page).toAppResult { items ->
            items.map { item ->
                HomeFeedItem(
                    api = item.api,
                    language = item.language,
                    title = item.title,
                    url = item.url,
                    coverUrl = item.coverUrl,
                    rating = item.rating,
                    genres = item.genres,
                    recentChapters =
                        item.recentChapters.map { chapter ->
                            HomeChapterRef(
                                number = chapter.number,
                                url = chapter.url,
                                isDownloaded = false,
                            )
                        },
                )
            }
        }

    override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> =
        engine.featured(page).toAppResult { items ->
            items.map { item ->
                FeaturedManga(
                    api = item.api,
                    language = item.language,
                    title = item.title,
                    url = item.url,
                    coverUrl = item.coverUrl,
                )
            }
        }

    override suspend fun search(
        query: String,
        page: Int,
        filters: FilterSelections,
    ): AppResult<List<HomeFeedItem>> =
        engine
            .search(
                query = query,
                page = page,
                filters = SharedFilterSelections(filters.byId),
            ).toAppResult { items ->
                items.map { item ->
                    HomeFeedItem(
                        api = item.api,
                        language = item.language,
                        title = item.title,
                        url = item.url,
                        coverUrl = item.coverUrl,
                        rating = item.rating,
                        genres = item.genres,
                        recentChapters =
                            item.recentChapters.map { chapter ->
                                HomeChapterRef(
                                    number = chapter.number,
                                    url = chapter.url,
                                    isDownloaded = false,
                                )
                            },
                    )
                }
            }

    override suspend fun details(manga: Manga): AppResult<MangaDetails> =
        engine.details(manga.toSharedRef()).toAppResult { details ->
            MangaDetails(
                api = details.api,
                language = details.language,
                title = details.title,
                url = details.url,
                coverUrl = details.coverUrl,
                description = details.description,
                author = details.author,
                rating = details.rating,
                status = details.status,
                genres = details.genres,
                chapters =
                    details.chapters.map { chapter ->
                        Chapter(
                            number = chapter.number,
                            name = chapter.name,
                            url = chapter.url,
                            date = chapter.date,
                            isDownloaded = false,
                            isBookmarked = false,
                        )
                    },
            )
        }

    override fun pages(
        manga: Manga,
        chapter: Chapter,
    ): Flow<AppResult<List<Page>>> =
        flow {
            emit(
                engine
                    .pages(
                        manga = manga.toSharedRef(),
                        chapter =
                            SharedChapter(
                                number = chapter.number,
                                name = chapter.name,
                                url = chapter.url,
                                date = chapter.date,
                            ),
                    ).toAppResult { pages ->
                        pages.map { page -> Page(url = page.url, headers = page.headers) }
                    },
            )
        }
}

private val configBridgeJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * The app and shared engine deliberately use separate contract packages. Their current serialized
 * SourceConfig shape is covered by cross-repository tests, so the boundary conversion uses that
 * shape instead of maintaining a second, error-prone hand-written field map.
 */
private fun SourceConfig.toSharedConfig(): SharedSourceConfig {
    val raw = configBridgeJson.encodeToString(SourceConfig.serializer(), this)
    return configBridgeJson.decodeFromString(SharedSourceConfig.serializer(), raw)
}

private fun SharedRequest.toAppRequest(): SourceRequest =
    SourceRequest(
        url = url,
        method =
            when (method) {
                SharedHttpMethod.GET -> SourceHttpMethod.GET
                SharedHttpMethod.POST_FORM -> SourceHttpMethod.POST_FORM
                SharedHttpMethod.POST_JSON -> SourceHttpMethod.POST_JSON
            },
        headers = headers,
        formBody = formBody,
        jsonBody = jsonBody,
    )

private fun me.manga.kira.sources.contracts.SourceResponse.toSharedResponse(): SharedResponse =
    SharedResponse(
        status = status,
        body = body,
        headers = headers,
        finalUrl = finalUrl,
    )

private fun Manga.toSharedRef(): SourceMangaRef =
    SourceMangaRef(
        api = api,
        language = language,
        title = title,
        url = url,
        coverUrl = coverUrl,
    )

private inline fun <T, R> SourceEngineResult<T>.toAppResult(transform: (T) -> R): AppResult<R> =
    when (this) {
        is SourceEngineResult.Success -> AppResult.Success(transform(value))
        is SourceEngineResult.Failure -> AppResult.Failure(error.toAppError())
    }

private fun SourceEngineError.toAppError(): AppError =
    when (this) {
        is SourceEngineError.Required -> AppError.Validation.Required(field)
        is SourceEngineError.Http -> AppError.Network.Http(status)
        SourceEngineError.NoConnectivity -> AppError.Network.NoConnectivity()
        SourceEngineError.Timeout -> AppError.Network.Timeout()
        SourceEngineError.InvalidResponse -> AppError.Network.Serialization()
        is SourceEngineError.Unexpected -> AppError.Unexpected("generic source error ($category)")
    }
