package me.manga.kira.sources.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.manga.kira.core.error.AppError
import me.manga.kira.core.error.TransportErrorMessages
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.states.State as LegacyState
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.ChapterItem as LegacyChapterItem
import me.manga.kira.domain.model.MangaInfo as LegacyMangaInfo
import me.manga.kira.domain.model.MangaItem as LegacyMangaItem
import me.manga.kira.domain.model.PopularManga as LegacyPopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources_repositry.BaseMangaRepository

/**
 * Adapts a single legacy Kotlin source ([BaseMangaRepository]) to the generic [MangaSourceClient]
 * port. It delegates each verb to the legacy repo's `Flow<State<…>>` API, awaits the single terminal
 * state, and maps the legacy models (`MangaItem`/`MangaInfo`/`ChapterItem`/`PopularManga` + raw page
 * URLs) onto pure `:domain` models — so a caller above `:data` sees the same shape whether a source
 * is config-driven or legacy.
 *
 * This is the Stage-0 bridge: every shipped source is wrapped by one of these, routed through the
 * registry, while the config-driven [me.manga.kira.sources.engine.GenericSourceClient] path stays
 * dark until a source is explicitly config-backed. The mapping mirrors the existing `:data` mappers
 * (`MangaDetailsMappers`/`HomeMappers`) but is duplicated here intentionally: those are `internal` to
 * `:data`, and this adapter must not pull `:data` onto the source seam.
 */
class LegacyKotlinSourceClient(
    private val repo: BaseMangaRepository,
) : MangaSourceClient {

    override val api: String = repo.API

    override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = source {
        repo.initSite()
        val flow = if (page <= 1) repo.fetchMangaHomeF(repo.getBaseUrl()) else repo.fetchMoreManga(page, null)
        flow.awaitTerminal().toResult { items -> items.map { it.toHomeFeedItem() } }
    }

    override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = source {
        // The legacy BaseMangaRepository exposes no paged-featured API, so any page > 1 would
        // silently re-fetch page 1 (a wrong-but-Success that both duplicates rows and suppresses
        // the generic fallback). Fail fast instead so a future paginating caller gets a visible error.
        if (page > 1) return@source AppResult.Failure(AppError.Validation.OutOfRange("page"))
        repo.initSite()
        repo.fetchPopularManga(repo.getBaseUrl()).awaitTerminal().toResult { items -> items.map { it.toFeatured() } }
    }

    override suspend fun search(query: String, page: Int): AppResult<List<HomeFeedItem>> = source {
        // The legacy BaseMangaRepository exposes no paged-search API (see featured() above) — fail
        // fast on page > 1 rather than silently returning page-1 results again.
        if (page > 1) return@source AppResult.Failure(AppError.Validation.OutOfRange("page"))
        repo.fetchSearchDataF(SearchType.Normal(query)).awaitTerminal().toResult { items -> items.map { it.toHomeFeedItem() } }
    }

    override suspend fun details(manga: Manga): AppResult<MangaDetails> = source {
        repo.fetchMangaChaptersF(manga.url).awaitTerminal().toResult { it.toDetails() }
    }

    override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> = flow {
        emit(
            source {
                repo.fetchChapterDataF(chapter.url).awaitTerminal()
                    .toResult { urls -> urls.map { Page(url = it, headers = repo.defaultHeaders) } }
            },
        )
    }

    // --- legacy plumbing --------------------------------------------------------------------------

    /** Run a legacy call, re-throwing cancellation and classifying any other throwable like `:data`. */
    private suspend fun <R> source(block: suspend () -> AppResult<R>): AppResult<R> = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        AppResult.Failure(classifyThrowable(t))
    }

    /**
     * Collect a legacy source flow down to its single terminal (non-`Loading`) state. Mirrors
     * `:data`'s `awaitTerminalState()`: a full `collect` (not early-cancelling `.first`) avoids the
     * "Flow exception transparency is violated" trap that bites sources emitting terminal states from
     * inside a `try/catch`. The legacy flows emit `Loading` then exactly one terminal state.
     */
    private suspend fun <T> Flow<LegacyState<T>>.awaitTerminal(): LegacyState<T> {
        var terminal: LegacyState<T>? = null
        collect { state -> if (state !is LegacyState.Loading) terminal = state }
        return terminal ?: LegacyState.Error(code = 0, message = "source flow completed without a terminal state")
    }

    private fun <T, R> LegacyState<T>.toResult(map: (T) -> R): AppResult<R> = when (this) {
        is LegacyState.Success -> AppResult.Success(map(data))
        is LegacyState.Error -> AppResult.Failure(toAppError())
        LegacyState.Loading -> AppResult.Failure(AppError.Unexpected("source produced no terminal state"))
    }

    /**
     * Classify a legacy `State.Error` exactly like `:data`'s `HomeMappers.toAppError` /
     * `MangaDetailsRepositoryImpl.toAppError`: HTTP codes in 400..599 → `Network.Http`; otherwise
     * inspect the message for connectivity/timeout signatures (legacy sources emit `State.Error(0, …)`
     * with the raw exception message). Keeps the surfaced `AppError` buckets aligned with the rest of
     * the rework `:data` boundary.
     */
    private fun LegacyState.Error.toAppError(): AppError {
        val status = code ?: 0
        if (status in 400..599) return AppError.Network.Http(statusCode = status)
        val raw = message.lowercase()
        return when {
            // Cloudflare/anti-bot interstitial whose status was lost (code 0): re-surface as 403 so the
            // UI routes to the WebView solver — matches MangaDetailsRepositoryImpl.toAppError exactly.
            isChallengeMessage(raw) -> AppError.Network.Http(statusCode = 403)
            TransportErrorMessages.isConnectivityMessage(raw) ->
                AppError.Network.NoConnectivity()
            TransportErrorMessages.isTimeoutMessage(raw) -> AppError.Network.Timeout()
            else -> AppError.Unexpected(message = message)
        }
    }

    /** Mirror of `:data`'s `classifyThrowable` — same Cloudflare/connectivity/timeout heuristics for thrown errors. */
    private fun classifyThrowable(t: Throwable): AppError {
        val raw = (t.message ?: "").lowercase()
        return when {
            isChallengeMessage(raw) -> AppError.Network.Http(statusCode = 403)
            TransportErrorMessages.isConnectivityMessage(raw) ->
                AppError.Network.NoConnectivity(cause = t)
            TransportErrorMessages.isTimeoutMessage(raw) -> AppError.Network.Timeout(cause = t)
            else -> AppError.Unexpected(message = t.message ?: t::class.simpleName.orEmpty(), cause = t)
        }
    }

    /** Conservative Cloudflare/anti-bot signatures, mirroring MangaDetailsRepositoryImpl.isChallengeMessage. */
    private fun isChallengeMessage(raw: String): Boolean = raw.containsAny(
        "cloudflare", "just a moment", "checking your browser", "attention required",
        "cf-ray", "cf_chl", "ddos-guard", "ddos guard", "403 forbidden", "access denied",
    )

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

    // --- legacy → domain mappers (mirror :data's HomeMappers/MangaDetailsMappers) -----------------

    private fun LegacyMangaItem.toHomeFeedItem(): HomeFeedItem = HomeFeedItem(
        api = api,
        language = language,
        title = title,
        url = url,
        coverUrl = imageUrl,
        rating = rating,
        genres = genres,
        recentChapters = chapters?.map { it.toHomeChapterRef() } ?: emptyList(),
    )

    private fun LegacyChapterItem.toHomeChapterRef(): HomeChapterRef =
        HomeChapterRef(number = number, url = url, isDownloaded = isDownloaded)

    private fun LegacyPopularManga.toFeatured(): FeaturedManga =
        FeaturedManga(api = api, language = language, title = title, url = url, coverUrl = imageUrl)

    private fun LegacyMangaInfo.toDetails(): MangaDetails = MangaDetails(
        api = api,
        language = language,
        title = title,
        url = url,
        coverUrl = imageUrl,
        description = description,
        author = author,
        rating = rating,
        status = status,
        genres = genres,
        chapters = chapters.map { it.toChapter() },
    )

    private fun LegacyChapterItem.toChapter(): Chapter =
        Chapter(number = number, name = name, url = url, date = date, isDownloaded = isDownloaded, isBookmarked = isBookmarked)
}
