package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.KermitLoggerAdapter
import me.manga.kira.core.logging.Logger
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.contracts.MangaSourceClient

/**
 * **RETAINED-BUT-UNWIRED (2026-06):** this wrapper is no longer used by [DefaultSourceRegistry].
 * Config-backed sources are now served by the bare generic client (generic-ONLY) — a generic failure
 * is surfaced as a clear error and the legacy scraper is never executed for a config-backed source.
 * The class (and its [SourceDebugFlags] hook + `FallbackSourceClientDebugFlagTest`) is kept, not
 * deleted, so a future opt-in per-source legacy fallback can be re-enabled without rebuilding it. It is
 * NOT a legacy scraper — it is composition-root glue.
 *
 * Original behavior (when it was wired): wraps a [primary] source client with a [fallback], so a config-backed
 * is SAFE even when the primary path fails for any reason — Cloudflare challenge, transient network
 * error, a config edge the engine doesn't yet cover. Every verb tries [primary]; on [AppResult.Failure]
 * it transparently retries on [fallback]. This is how a generic config-backed kept a guaranteed legacy floor:
 * `primary = generic`, `fallback = legacy`. The user never saw a regression — at worst the legacy result.
 *
 * Note: cancellation is preserved because the underlying clients re-throw `CancellationException`
 * before mapping to `Failure`, so a cancelled primary call won't trigger a spurious fallback.
 *
 * **Debug switch — [SourceDebugFlags.DISABLE_LEGACY_FALLBACK_FOR_GENERIC_TESTING].** When that flag is
 * ON, this wrapper runs **generic-only**: it does NOT fall back to [fallback]; it returns the primary
 * (generic) outcome verbatim and logs a categorised diagnosis (tag [TAG]) for every verb — so a failing
 * migrated source fails visibly instead of being masked by legacy, and you can tell *why* (Cloudflare /
 * headers / HTTP / JSON-parse / missing config / empty selectors). The flag is read per call, so it can be
 * toggled at runtime. Default OFF → the production safety floor below is unchanged.
 */
class FallbackSourceClient(
    private val primary: MangaSourceClient,
    private val fallback: MangaSourceClient,
    private val disableFallback: () -> Boolean = { SourceDebugFlags.DISABLE_LEGACY_FALLBACK_FOR_GENERIC_TESTING },
    private val log: Logger = KermitLoggerAdapter(),
) : MangaSourceClient {

    override val api: String = primary.api

    override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> {
        val result = primary.home(page)
        if (disableFallback()) return result.also { diagnose("home(page=$page)", it) { v -> v.size } }
        return result.orElse { fallback.home(page) }
    }

    override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> {
        val result = primary.featured(page)
        if (disableFallback()) return result.also { diagnose("featured(page=$page)", it) { v -> v.size } }
        return result.orElse { fallback.featured(page) }
    }

    override suspend fun search(query: String, page: Int): AppResult<List<HomeFeedItem>> {
        val result = primary.search(query, page)
        if (disableFallback()) return result.also { diagnose("search('$query',page=$page)", it) { v -> v.size } }
        return result.orElse { fallback.search(query, page) }
    }

    override suspend fun details(manga: Manga): AppResult<MangaDetails> {
        val result = primary.details(manga)
        if (disableFallback()) return result.also { diagnose("details(${manga.url})", it) { v -> v.chapters.size } }
        return result.orElse { fallback.details(manga) }
    }

    override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> = flow {
        // firstOrNull (not first): a primary that completes with zero emissions yields null → fall back,
        // rather than throwing NoSuchElementException out of the flow. A cancelled primary still
        // propagates CancellationException (no spurious fallback).
        val primaryResult = primary.pages(manga, chapter).firstOrNull()
        if (disableFallback()) {
            // Generic-only: surface the real outcome. A flow that emitted nothing is itself a failure to report.
            val result = primaryResult
                ?: AppResult.Failure(AppError.Unexpected("generic pages() emitted no result"))
            diagnose("pages(${chapter.url})", result) { it.size }
            emit(result)
            return@flow
        }
        // #6: a generic Success with ZERO pages is always a selector mismatch — locked-chapter
        // filtering happens on the chapter LIST (details path), never inside pages(), so a 0-page
        // result here is never legitimate. Require a NON-EMPTY Success to short-circuit; otherwise
        // fall through to the legacy parser instead of handing the reader a blank chapter. (Only
        // pages() gets this guard — empty home/featured/search/details can be legitimate.)
        if (primaryResult is AppResult.Success && primaryResult.value.isNotEmpty()) {
            emit(primaryResult)
        } else {
            emitAll(fallback.pages(manga, chapter))
        }
    }

    private inline fun <T> AppResult<T>.orElse(fallback: () -> AppResult<T>): AppResult<T> =
        if (this is AppResult.Success) this else fallback()

    /**
     * Generic-only diagnostics (only reached when the debug flag is ON). Categorises each verb's outcome
     * so a tester can tell selectors from Cloudflare from a parse error without reading the raw stack.
     */
    private inline fun <T> diagnose(verb: String, result: AppResult<T>, count: (T) -> Int) {
        when (result) {
            is AppResult.Success -> {
                val n = count(result.value)
                if (n == 0) {
                    log.w(TAG, "$api $verb → OK but 0 items (legacy fallback OFF) — likely a selector / JSONPath mismatch, or genuinely empty")
                } else {
                    log.i(TAG, "$api $verb → OK ($n items) [generic]")
                }
            }
            is AppResult.Failure -> log.w(TAG, "$api $verb → FAILED (legacy fallback OFF): ${categorise(result.error)}", result.error.cause)
        }
    }

    private fun categorise(error: AppError): String = when (error) {
        is AppError.Network.Http -> when (error.statusCode) {
            403, 503 -> "HTTP ${error.statusCode} — Cloudflare challenge or missing/expired headers/cookies (cf_clearance)"
            401 -> "HTTP 401 — auth headers/cookies"
            in 500..599 -> "HTTP ${error.statusCode} — upstream server error"
            else -> "HTTP ${error.statusCode}"
        }
        is AppError.Network.Serialization -> "JSON/serialization parse error: ${error.cause?.message ?: "?"}"
        is AppError.Network.NoConnectivity -> "no network connectivity"
        is AppError.Network.Timeout -> "request timed out"
        is AppError.Validation.Required -> "config: '${error.field}' not declared (endpoint/field missing for this verb)"
        is AppError.Unexpected -> {
            val causeName = error.cause?.let { it::class.simpleName.orEmpty() }.orEmpty()
            val looksLikeParse = causeName.contains("Serialization") || causeName.contains("Json")
            val kind = if (looksLikeParse) "JSON parse error" else "engine error"
            "$kind: ${error.message}" + if (causeName.isNotEmpty()) " [$causeName]" else ""
        }
        else -> error::class.simpleName ?: "error"
    }

    private companion object {
        const val TAG = "GenericSourceTest"
    }
}
